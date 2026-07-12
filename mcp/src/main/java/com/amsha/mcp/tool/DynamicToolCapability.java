package com.amsha.mcp.tool;

import java.util.Objects;

import com.amsha.core.tools.ToolCategory;

public record DynamicToolCapability(
        String toolName,
        String description,
        String mcpServerName,
        McpTransportType transport,
        boolean requiresApproval,
        String inputSchema,
        ToolCategory category) {
    public DynamicToolCapability(
            String toolName,
            String description,
            String mcpServerName,
            McpTransportType transport,
            boolean requiresApproval,
            String inputSchema) {
        this(toolName, description, mcpServerName, transport, requiresApproval, inputSchema, ToolCategory.NETWORK);
    }

    public DynamicToolCapability {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(mcpServerName, "mcpServerName must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(category, "category must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}
