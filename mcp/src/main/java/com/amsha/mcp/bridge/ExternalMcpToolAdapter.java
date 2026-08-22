package com.amsha.mcp.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tool.DynamicToolInvoker;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.PodmanMcpTransport;
import com.amsha.tool.api.DynamicToolDescriptor;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ExternalMcpToolAdapter implements DynamicToolInvoker {
    private static final Logger log = Logger.getLogger(ExternalMcpToolAdapter.class);

    @Inject
    PodmanMcpTransport podmanMcpTransport;

    @Inject
    McpClientService mcpClientService;

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AuditService auditService;

    public String invoke(String toolName, String paramsJson) {
        String scope = WorkspaceContext.getUserIdOrUnknown();
        DynamicToolCapability cap = registry.find(scope, toolName)
                .orElseThrow(() -> new McpToolNotFoundException("Dynamic MCP tool not registered: " + toolName));
        if (!registry.acquireInvocation(scope, toolName)) {
            throw new McpToolNotFoundException("Dynamic MCP tool is disabled: " + toolName);
        }

        log.debugf("Invoking dynamic tool %s via %s", toolName, cap.transport());

        auditService.record(
                "mcp-external",
                "system",
                "system",
                SecurityEventType.MCP_TOOL_INVOKED,
                "tool=" + toolName + " server=" + cap.mcpServerName() + " transport=" + cap.transport());

        try {
            return switch (cap.transport()) {
                case STDIO -> invokeStdio(cap.mcpServerName(), toolName, paramsJson, null);
                case HTTP_SSE -> mcpClientService.invoke(cap.mcpServerName(), toolName, paramsJson);
            };
        } finally {
            registry.releaseInvocation(scope, toolName);
        }
    }

    @Override
    public Collection<DynamicToolDescriptor> listTools() {
        return registry.listTools();
    }

    @Override
    public ToolResponse invoke(String toolName, Map<String, Object> params, ToolExecutionContext context) {
        try {
            String paramsJson = objectMapper.writeValueAsString(params == null ? Map.of() : params);
            String result = invokeForUser(context.userId(), toolName, paramsJson);
            auditService.record(
                    context.sessionId(),
                    context.userId(),
                    context.agentRole(),
                    SecurityEventType.TOOL_EXECUTED,
                    "tool=" + toolName);
            return ToolResponse.success(result);
        } catch (RuntimeException | IOException e) {
            log.warnf("Dynamic MCP tool '%s' failed: %s", toolName, e.getClass().getSimpleName());
            return ToolResponse.failure(1, "Dynamic MCP tool execution failed");
        }
    }

    private String invokeForUser(String userId, String toolName, String paramsJson) {
        DynamicToolCapability cap = registry.find(userId, toolName)
                .orElseThrow(() -> new McpToolNotFoundException("Dynamic MCP tool not registered: " + toolName));
        if (!registry.acquireInvocation(userId, toolName)) {
            throw new McpToolNotFoundException("Dynamic MCP tool is disabled: " + toolName);
        }
        log.debugf("Invoking dynamic tool %s via %s", toolName, cap.transport());
        auditService.record(
                "mcp-external",
                userId,
                "system",
                SecurityEventType.MCP_TOOL_INVOKED,
                "tool=" + toolName + " server=" + cap.mcpServerName() + " transport=" + cap.transport());
        try {
            return switch (cap.transport()) {
                case STDIO -> invokeStdio(cap.mcpServerName(), toolName, paramsJson, userId);
                case HTTP_SSE -> mcpClientService.invoke(cap.mcpServerName(), toolName, paramsJson, userId);
            };
        } finally {
            registry.releaseInvocation(userId, toolName);
        }
    }

    private String invokeStdio(String serverName, String toolName, String paramsJson, String userId) {
        if (mcpClientService.hasStdioServer(serverName)) {
            if (userId != null) {
                return mcpClientService.invoke(serverName, toolName, paramsJson, userId);
            } else {
                return mcpClientService.invoke(serverName, toolName, paramsJson);
            }
        }
        return podmanMcpTransport
                .invoke(serverName, toolName, paramsJson)
                .await()
                .atMost(Duration.ofSeconds(30));
    }
}
