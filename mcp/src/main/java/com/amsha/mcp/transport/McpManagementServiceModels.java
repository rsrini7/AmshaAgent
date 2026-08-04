package com.amsha.mcp.transport;

import java.util.List;

import com.amsha.persistence.api.McpConnectionToolPolicyDto;

/** Response models shared by the MCP management service and its adapters. */
public abstract class McpManagementServiceModels {
    public record ConnectionSnapshot(
            String name,
            String endpoint,
            String state,
            String transport,
            long enabledToolCount,
            long discoveredToolCount,
            long revision,
            List<McpConnectionToolPolicyDto> tools) {}

    public record DiscoveryPreview(String name, String endpoint, String transport, List<ToolPreview> tools) {}

    public record ToolPreview(String name, String description, String inputSchema, String schemaHash) {}

    public record DisconnectResult(boolean deregistered, int toolsRemoved) {}

    public record ApprovalExecution(String taskId, String operation, Object result) {}
}
