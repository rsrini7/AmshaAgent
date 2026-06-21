package com.amsha.mcp.tool;

import java.util.Objects;

public record DynamicToolCapability(
        String toolName,
        String description,
        String mcpServerName,
        McpTransportType transport,
        boolean requiresApproval,
        String inputSchema) {
    public DynamicToolCapability {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(mcpServerName, "mcpServerName must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}
