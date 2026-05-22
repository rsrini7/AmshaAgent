package com.arcclaw.mcp.bridge;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.mcp.tool.DynamicToolCapability;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.transport.McpClientService;
import com.arcclaw.mcp.transport.PodmanMcpTransport;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ExternalMcpToolAdapter {
    private static final Logger log = Logger.getLogger(ExternalMcpToolAdapter.class);

    @Inject PodmanMcpTransport podmanMcpTransport;
    @Inject McpClientService mcpClientService;
    @Inject DynamicToolCapabilityRegistry registry;
    @Inject AuditService auditService;

    public String invoke(String toolName, String paramsJson) {
        DynamicToolCapability cap = registry.find(toolName)
            .orElseThrow(() -> new McpToolNotFoundException("Dynamic MCP tool not registered: " + toolName));

        log.debugf("Invoking dynamic tool %s via %s", toolName, cap.transport());

        auditService.record("mcp-external", "system", "system",
                SecurityEventType.MCP_TOOL_INVOKED,
                "tool=" + toolName + " server=" + cap.mcpServerName() + " transport=" + cap.transport());

        return switch (cap.transport()) {
            case STDIO -> podmanMcpTransport.invoke(cap.mcpServerName(), toolName, paramsJson)
                .await().atMost(Duration.ofSeconds(30));
            case HTTP_SSE -> mcpClientService.invoke(cap.mcpServerName(), toolName, paramsJson);
        };
    }
}
