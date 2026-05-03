package com.arcclaw.mcp.tool;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.mcp.transport.McpClientService;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Component A4 - McpSelfRegisterTool.
 * Allows agents to dynamically register new MCP servers.
 *
 * This tool requires manual approval (HITL) as it can grant the agent
 * access to new capabilities and external systems.
 */
@ApplicationScoped
public class McpSelfRegisterTool implements AgentTool {

    private static final Pattern SAFE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    @Inject
    McpClientService mcpClientService;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.REGISTER_MCP_SERVER.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.REGISTER_MCP_SERVER.description();
    }

    @Override
    public boolean requiresSandbox() {
        // Transport logic itself doesn't require sandbox, 
        // but the tool execution it enables might.
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        // ALWAYS require manual approval for registering new tools.
        return true;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("name", "The unique name for this MCP server (alphanumeric, underscores, hyphens).")
            .addEnumProperty("transport", List.of("http", "stdio"), "Transport type: http (SSE) or stdio (local process).")
            .addStringProperty("url", "The URL for HTTP/SSE transport (e.g., http://localhost:3000/mcp).")
            .addStringProperty("command", "The command to run for STDIO transport (e.g., npx, python, docker).")
            .addStringProperty("args", "Comma-separated arguments for the command.")
            .addStringProperty("env", "Environment variables (KEY=VALUE lines).")
            .required("name", "transport")
            .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId;
        String sessionId;

        userId = ctx.userId();
        sessionId = ctx.sessionId();

        if (userId == null || userId.isBlank() || sessionId == null) {
            return ToolResponse.failure(403, "Security violation: tenant context not bound.");
        }

        String name = (String) params.get("name");
        if (name == null || !SAFE_NAME_PATTERN.matcher(name).matches()) {
            return ToolResponse.failure(400, "Invalid name. Must match " + SAFE_NAME_PATTERN.pattern());
        }

        String transportStr = (String) params.get("transport");

        try {
            if ("http".equalsIgnoreCase(transportStr)) {
                String url = (String) params.get("url");
                if (url == null || url.isBlank()) {
                    return ToolResponse.failure(400, "url is required for http transport.");
                }
                mcpClientService.connect(name, url);
            } else if ("stdio".equalsIgnoreCase(transportStr)) {
                String command = (String) params.get("command");
                if (command == null || command.isBlank()) {
                    return ToolResponse.failure(400, "command is required for stdio transport.");
                }
                
                String argsStr = (String) params.get("args");
                List<String> argsList = (argsStr != null && !argsStr.isBlank()) 
                    ? Arrays.stream(argsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList() 
                    : List.of();
                
                String envStr = (String) params.get("env");
                Map<String, String> envMap = new HashMap<>();
                if (envStr != null && !envStr.isBlank()) {
                    for (String line : envStr.split("\n")) {
                        int eqIdx = line.indexOf('=');
                        if (eqIdx > 0) {
                            envMap.put(line.substring(0, eqIdx).trim(), line.substring(eqIdx + 1).trim());
                        }
                    }
                }
                
                mcpClientService.connectStdio(name, command, argsList, envMap);
            } else {
                return ToolResponse.failure(400, "Unsupported transport: " + transportStr);
            }

            auditService.record(sessionId, userId, ctx.agentRole(), 
                SecurityEventType.TOOL_EXECUTED, "MCP server registered: " + name);

            return ToolResponse.success("MCP server '" + name + "' registered successfully. tools/list handshake completed.");

        } catch (Exception e) {
            return ToolResponse.failure(502, "MCP connection failed: " + e.getMessage());
        }
    }
}
