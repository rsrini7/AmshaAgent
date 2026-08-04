package com.amsha.mcp.transport;

import java.util.List;
import java.util.Map;

import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.tool.McpTransportType;

/** Owns throwaway discovery and first durable connection setup. */
final class McpManagementConnectionSetup {

    private final McpClientService clientService;
    private final McpConnectionLifecycleService lifecycleService;
    private final McpManagementSupport support;

    McpManagementConnectionSetup(
            McpClientService clientService,
            McpConnectionLifecycleService lifecycleService,
            McpManagementSupport support) {
        this.clientService = clientService;
        this.lifecycleService = lifecycleService;
        this.support = support;
    }

    McpManagementService.DiscoveryPreview discover(
            String userId, String name, String endpoint, Map<String, String> headers, boolean preApproved) {
        return discoverInternal(userId, name, endpoint, headers, preApproved, null);
    }

    McpManagementService.DiscoveryPreview discoverWithApproval(
            String userId, String name, String endpoint, Map<String, String> headers, String approvalToken) {
        return discoverInternal(userId, name, endpoint, headers, false, approvalToken);
    }

    private McpManagementService.DiscoveryPreview discoverInternal(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            boolean preApproved,
            String approvalToken) {
        McpManagementSupport.requireUser(userId);
        McpManagementSupport.requireName(name);
        Map<String, String> safeHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        if (approvalToken == null) lifecycleService.validateHttpRegistration(endpoint, safeHeaders);
        try {
            List<McpClientService.DiscoveredTool> tools = support.runInUserVaultScope(
                    userId,
                    () -> approvalToken == null
                            ? clientService.discoverForUser(
                                    name, userId, endpoint, ToolCategory.NETWORK, safeHeaders, preApproved)
                            : clientService.discoverForUser(
                                    name, userId, endpoint, ToolCategory.NETWORK, safeHeaders, approvalToken));
            support.audit(userId, SecurityEventType.MCP_CONNECTION_REQUESTED, "server=" + name + " operation=discover");
            return new McpManagementService.DiscoveryPreview(
                    name,
                    endpoint,
                    McpTransportType.HTTP_SSE.name(),
                    tools.stream()
                            .map(tool -> new McpManagementService.ToolPreview(
                                    tool.name(),
                                    tool.description(),
                                    tool.inputSchema(),
                                    McpManagementSupport.sha256(tool.inputSchema())))
                            .toList());
        } finally {
            clientService.clearHttpConnectionState(name, userId);
        }
    }

    McpManagementService.ConnectionSnapshot connectNew(
            String userId, String name, String endpoint, Map<String, String> headers, boolean preApproved) {
        return connectNewInternal(userId, name, endpoint, headers, ToolCategory.NETWORK, preApproved, null);
    }

    McpManagementService.ConnectionSnapshot connectNewWithApproval(
            String userId, String name, String endpoint, Map<String, String> headers, String approvalToken) {
        return connectNewInternal(userId, name, endpoint, headers, ToolCategory.NETWORK, false, approvalToken);
    }

    McpManagementService.ConnectionSnapshot registerFromApprovedCheckpoint(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            String approvalToken,
            ToolCategory category) {
        return connectNewInternal(userId, name, endpoint, headers, category, false, approvalToken);
    }

    private McpManagementService.ConnectionSnapshot connectNewInternal(
            String userId,
            String name,
            String endpoint,
            Map<String, String> headers,
            ToolCategory category,
            boolean preApproved,
            String approvalToken) {
        McpManagementSupport.requireUser(userId);
        McpManagementSupport.requireName(name);
        Map<String, String> safeHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        if (approvalToken == null) lifecycleService.validateHttpRegistration(endpoint, safeHeaders);
        List<McpClientService.DiscoveredTool> tools = support.runInUserVaultScope(
                userId,
                () -> approvalToken == null
                        ? clientService.discoverForUser(name, userId, endpoint, category, safeHeaders, preApproved)
                        : clientService.discoverForUser(name, userId, endpoint, category, safeHeaders, approvalToken));
        McpConnectionLifecycleService.PersistedConnection persisted =
                lifecycleService.persistSuccessfulHttp(name, endpoint, category, userId, safeHeaders);
        if (persisted.connection() == null) {
            clientService.clearHttpConnectionState(name, userId);
            throw new McpConnectionException("MCP persistence backend is unavailable");
        }
        support.runInUserVaultScope(
                userId, () -> clientService.configureHeaderSecretRefs(name, userId, persisted.headerSecretRefs()));
        support.replacePolicies(persisted.connection().id(), userId, tools);
        lifecycleService.updateState(
                persisted.connection().id(), userId, McpManagementService.STATE_CONNECTED, true, null, null);
        support.audit(userId, SecurityEventType.MCP_SERVER_CONNECTED, "server=" + name + " tools=0");
        return lifecycleService
                .findByNameAndUserId(name, userId)
                .map(support::snapshot)
                .orElseThrow();
    }
}
