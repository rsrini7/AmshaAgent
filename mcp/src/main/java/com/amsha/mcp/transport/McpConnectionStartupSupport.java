package com.amsha.mcp.transport;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionStorePort;

/** Startup-only reconciliation for durable MCP state and dynamic capabilities. */
final class McpConnectionStartupSupport {

    private McpConnectionStartupSupport() {}

    static int reconcilePendingApprovals(McpConnectionStorePort store, AuditService auditService) {
        int reconciled = 0;
        for (McpConnectionDto connection : store.listPendingApprovals()) {
            String state = connection.enabled() ? "DISCONNECTED" : "PAUSED";
            McpConnectionDto updated = new McpConnectionDto(
                    connection.id(),
                    connection.userId(),
                    connection.name(),
                    connection.transport(),
                    connection.endpoint(),
                    connection.category(),
                    connection.enabled(),
                    state,
                    null,
                    connection.revision() + 1,
                    "APPROVAL_LOST_ON_RESTART",
                    connection.createdAt(),
                    Instant.now());
            if (store.updateStateIfRevision(updated, connection.revision())) {
                auditService.recordMandatory(
                        AgentSentinels.SYSTEM_SESSION,
                        connection.userId(),
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.APPROVAL_DENIED,
                        "server=" + connection.name() + " reason=APPROVAL_LOST_ON_RESTART");
                reconciled++;
            }
        }
        return reconciled;
    }

    static void clearRegisteredTools(DynamicToolCapabilityRegistry registry, String userId, String serverName) {
        if (registry == null) return;
        registry.getAll(userId).stream()
                .filter(tool -> serverName.equals(tool.mcpServerName()))
                .map(DynamicToolCapability::toolName)
                .forEach(toolName -> registry.deregister(userId, toolName));
    }

    static void reconcileToolPolicies(
            DynamicToolCapabilityRegistry registry,
            McpConnectionStorePort store,
            McpConnectionDto connection,
            Set<String> enabledToolNames) {
        if (registry == null) return;
        Set<String> registeredToolNames = registry.getAll(connection.userId()).stream()
                .filter(tool -> connection.name().equals(tool.mcpServerName()))
                .map(DynamicToolCapability::toolName)
                .collect(Collectors.toSet());
        enabledToolNames.stream()
                .filter(toolName -> !registeredToolNames.contains(toolName))
                .forEach(toolName -> store.setToolEnabled(connection.id(), connection.userId(), toolName, false));
    }
}
