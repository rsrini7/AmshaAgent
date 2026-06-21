package com.arcclaw.mcp.bridge;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.tools.ToolSchemaProvider;
import com.arcclaw.mcp.config.McpToolAllowlist;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class McpToolBridge {
    private static final Logger log = Logger.getLogger(McpToolBridge.class);

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    McpToolAllowlist allowlist;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolSchemaProvider schemaProvider;

    private final Map<String, String> schemaCache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmSchemaCache() {
        log.info("McpToolBridge: Warming schema cache for exposed tools...");
        allowlist.exposedTools().forEach(name -> {
            AgentTool tool = toolRegistry
                    .findTool(name)
                    .orElseThrow(() -> new IllegalStateException(
                            "McpToolBridge: exposed tool '" + name + "' is not registered in ToolRegistry (§4.1)"));
            try {
                schemaCache.put(name, schemaProvider.getJsonSchema(tool));
            } catch (Exception e) {
                log.errorf("Failed to cache MCP schema for tool '%s': %s", name, LogUtil.rootMessage(e));
            }
        });
    }

    public void clearCache() {
        schemaCache.clear();
        log.info("McpToolBridge: Schema cache cleared.");
    }

    public List<McpToolDescriptor> getExposedTools() {
        return allowlist.exposedTools().stream()
                .map(toolNameStr -> Arrays.stream(ToolCapability.values())
                        .filter(cap -> cap.toolName().equals(toolNameStr))
                        .findFirst()
                        .map(cap -> new McpToolDescriptor(cap.toolName(), cap.description(), buildInputSchema(cap)))
                        .orElseGet(() -> {
                            log.warnf(
                                    "McpToolAllowlist entry '%s' has no matching ToolCapability.toolName() - skipped",
                                    toolNameStr);
                            return null;
                        }))
                .filter(Objects::nonNull)
                .toList();
    }

    @Inject
    AuditService auditService;

    public String executeTool(McpToolRequest request) {
        String toolName = request.toolName();
        String paramsJson = request.paramsJson();
        if (!allowlist.exposedTools().contains(toolName)) {
            throw new McpToolNotAllowedException("Tool '" + toolName + "' not in allowlist");
        }

        String mcpClientId = request.getHeader("X-MCP-Client-ID");
        String mcpSessionId = request.getHeader("X-MCP-Session-ID");
        String mcpCapabilities = request.getHeader("X-MCP-Capabilities");

        String sessionId = "mcp-" + mcpClientId + "-" + mcpSessionId;
        String userId = "mcp:" + mcpClientId;
        String role = mapCapabilitiesToRole(mcpCapabilities);

        auditService.record(
                sessionId,
                userId,
                role,
                SecurityEventType.TOOL_INVOKED,
                "Invoked " + toolName + " by " + mcpClientId + " [session=" + mcpSessionId + "]");

        try {
            return WorkspaceContext.run(sessionId, userId, "mcp", AutonomyLevel.SUPERVISED, role, () -> {
                ToolResponse response = toolRegistry.invokeTool(toolName, paramsJson, "mcp-server");
                return response.success() ? response.output() : "Error: " + response.output();
            });
        } catch (Exception e) {
            log.errorf("Error invoking tool '%s': %s", toolName, LogUtil.rootMessage(e));
            return "Error: " + e.getMessage();
        }
    }

    private String mapCapabilitiesToRole(String capabilities) {
        if (capabilities != null && capabilities.contains("code_generation")) {
            return AgentRoleEnum.CODER.id();
        }
        if (capabilities != null && capabilities.contains("web_navigation")) {
            return AgentRoleEnum.RESEARCHER.id();
        }
        return AgentRoleEnum.COORDINATOR.id();
    }

    private String buildInputSchema(ToolCapability cap) {
        String toolName = cap.toolName();
        return schemaCache.computeIfAbsent(toolName, name -> {
            AgentTool tool = toolRegistry
                    .findTool(name)
                    .orElseThrow(() -> new IllegalStateException("Tool not found in registry: " + name));
            try {
                return schemaProvider.getJsonSchema(tool);
            } catch (Exception e) {
                log.errorf("Failed to serialize schema for tool '%s': %s", name, LogUtil.rootMessage(e));
                return "{\"type\":\"object\"}";
            }
        });
    }
}
