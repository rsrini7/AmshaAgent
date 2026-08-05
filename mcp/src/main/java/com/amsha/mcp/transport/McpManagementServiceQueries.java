package com.amsha.mcp.transport;

import java.util.List;

import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;

/** Read-only MCP management queries kept separate from lifecycle mutations. */
final class McpManagementServiceQueries {
    private final McpConnectionLifecycleService lifecycleService;
    private final McpManagementSupport support;

    McpManagementServiceQueries(McpConnectionLifecycleService lifecycleService, McpManagementSupport support) {
        this.lifecycleService = lifecycleService;
        this.support = support;
    }

    List<McpManagementService.ConnectionSnapshot> list(String userId) {
        if (McpManagementSupport.isBlank(userId)) return List.of();
        return lifecycleService.listByUserId(userId).stream()
                .map(support::snapshot)
                .toList();
    }

    List<McpManagementService.ToolPreview> listTools(String userId, String name) {
        McpConnectionDto connection = support.findConnection(userId, name);
        return lifecycleService.listToolPolicies(connection.id(), userId).stream()
                .filter(McpConnectionToolPolicyDto::available)
                .map(policy -> new McpManagementService.ToolPreview(
                        policy.toolName(), policy.description(), policy.inputSchema(), policy.schemaHash()))
                .toList();
    }
}
