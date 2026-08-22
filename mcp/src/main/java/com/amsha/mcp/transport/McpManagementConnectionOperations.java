package com.amsha.mcp.transport;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.tools.ToolCategory;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.security.api.OutboundPurpose;

/** Executes the governed reconnect path for the MCP management façade. */
final class McpManagementConnectionOperations {
    private final McpClientService clientService;
    private final McpConnectionLifecycleService lifecycleService;
    private final McpManagementSupport support;

    McpManagementConnectionOperations(
            McpClientService clientService,
            McpConnectionLifecycleService lifecycleService,
            McpManagementSupport support) {
        this.clientService = clientService;
        this.lifecycleService = lifecycleService;
        this.support = support;
    }

    McpManagementService.ConnectionSnapshot connect(
            String userId,
            String name,
            boolean preApproved,
            String approvalToken,
            OutboundPurpose approvalPurpose,
            Long expectedRevision,
            String approvalTaskId) {
        McpManagementSupport.requireUser(userId);
        McpConnectionDto connection = support.findConnection(userId, name);
        support.checkApprovalRevision(connection, expectedRevision, approvalTaskId);
        Set<String> enabledTools = support.enabledToolNames(connection, userId);
        lifecycleService.updateState(
                connection.id(),
                userId,
                McpManagementService.STATE_CONNECTING,
                true,
                connection.approvalTaskId(),
                null);
        support.clearRegisteredTools(userId, name);
        try {
            List<McpClientService.DiscoveredTool> tools = support.runInUserVaultScope(userId, () -> {
                support.configureStoredHeaders(connection, userId);
                if (approvalToken == null) {
                    return clientService.connectForUserWithEnabledTools(
                            name,
                            userId,
                            connection.endpoint(),
                            ToolCategory.valueOf(connection.category()),
                            Map.of(),
                            preApproved,
                            enabledTools);
                }
                return clientService.connectForUserWithEnabledTools(
                        name,
                        userId,
                        connection.endpoint(),
                        ToolCategory.valueOf(connection.category()),
                        Map.of(),
                        approvalToken,
                        approvalPurpose,
                        enabledTools);
            });
            support.replacePolicies(connection.id(), userId, tools);
            lifecycleService.updateState(
                    connection.id(), userId, McpManagementService.STATE_CONNECTED, true, null, null);
            support.audit(
                    userId, SecurityEventType.MCP_SERVER_CONNECTED, "server=" + name + " tools=" + enabledTools.size());
            return lifecycleService
                    .findByNameAndUserId(name, userId)
                    .map(support::snapshot)
                    .orElseThrow();
        } catch (RuntimeException exception) {
            lifecycleService.updateState(
                    connection.id(),
                    userId,
                    McpManagementService.STATE_DEGRADED,
                    true,
                    null,
                    McpManagementSupport.classify(exception));
            throw exception;
        }
    }
}
