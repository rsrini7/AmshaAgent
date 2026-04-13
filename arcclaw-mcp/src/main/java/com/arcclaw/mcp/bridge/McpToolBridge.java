package com.arcclaw.mcp.bridge;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.mcp.config.McpToolAllowlist;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpToolBridge {
    private static final Logger log = Logger.getLogger(McpToolBridge.class);

    @Inject ToolRegistry toolRegistry;
    @Inject McpToolAllowlist allowlist;

    public List<McpToolDescriptor> getExposedTools() {
        return allowlist.exposedTools().stream()
            .map(toolNameStr -> Arrays.stream(ToolCapability.values())
                .filter(cap -> cap.toolName().equals(toolNameStr))
                .findFirst()
                .map(cap -> new McpToolDescriptor(
                    cap.toolName(),
                    cap.description(),
                    buildInputSchema(cap)
                ))
                .orElseGet(() -> {
                    log.warnf("McpToolAllowlist entry '%s' has no matching ToolCapability.toolName() — skipped", toolNameStr);
                    return null;
                }))
            .filter(Objects::nonNull)
            .toList();
    }

    @Inject AuditService auditService;

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
            "Invoked " + toolName + " by " + mcpClientId + " [session=" + mcpSessionId + "]"
        );

        try {
            return WorkspaceContext.run(
                sessionId,
                userId,
                "mcp",
                AutonomyLevel.SUPERVISED,
                role,
                () -> {
                    ToolResponse response = toolRegistry.invokeTool(toolName, paramsJson, "mcp-server");
                    return response.success() ? response.output() : "Error: " + response.output();
                }
            );
        } catch (Exception e) {
            log.errorf("Error invoking tool '%s': %s", toolName, e.getMessage());
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
        return switch (cap) {
            case GITHUB -> "{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\"},\"params\":{\"type\":\"object\"}},\"required\":[\"operation\"]}";
            case MEMORY_SEARCH -> "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";
            default -> "{\"type\":\"object\"}";
        };
    }
}
