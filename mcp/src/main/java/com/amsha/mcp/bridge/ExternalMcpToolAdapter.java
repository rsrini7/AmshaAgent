package com.amsha.mcp.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.PodmanMcpTransport;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ExternalMcpToolAdapter {
    private static final Logger log = Logger.getLogger(ExternalMcpToolAdapter.class);

    @Inject
    PodmanMcpTransport podmanMcpTransport;

    @Inject
    McpClientService mcpClientService;

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Inject
    AuditService auditService;

    public String invoke(String toolName, String paramsJson) {
        DynamicToolCapability cap = registry.find(toolName)
                .orElseThrow(() -> new McpToolNotFoundException("Dynamic MCP tool not registered: " + toolName));

        log.debugf("Invoking dynamic tool %s via %s", toolName, cap.transport());

        auditService.record(
                "mcp-external",
                "system",
                "system",
                SecurityEventType.MCP_TOOL_INVOKED,
                "tool=" + toolName + " server=" + cap.mcpServerName() + " transport=" + cap.transport());

        return switch (cap.transport()) {
            case STDIO ->
                podmanMcpTransport
                        .invoke(cap.mcpServerName(), toolName, paramsJson)
                        .await()
                        .atMost(Duration.ofSeconds(30));
            case HTTP_SSE -> mcpClientService.invoke(cap.mcpServerName(), toolName, paramsJson);
        };
    }
}
