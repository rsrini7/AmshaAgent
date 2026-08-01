package com.amsha.mcp.tool;

import java.util.Objects;

import com.amsha.core.security.ToolOperation;
import com.amsha.core.tools.ToolCategory;

public record DynamicToolCapability(
        String toolName,
        String description,
        String mcpServerName,
        McpTransportType transport,
        boolean requiresApproval,
        String inputSchema,
        ToolCategory category,
        ToolOperation requiredOperation) {

    /** Convenience: NETWORK category with NETWORK_OUTBOUND operation. */
    public DynamicToolCapability(
            String toolName,
            String description,
            String mcpServerName,
            McpTransportType transport,
            boolean requiresApproval,
            String inputSchema) {
        this(
                toolName,
                description,
                mcpServerName,
                transport,
                requiresApproval,
                inputSchema,
                ToolCategory.NETWORK,
                ToolOperation.NETWORK_OUTBOUND);
    }

    /** Convenience: derive requiredOperation from category via registration defaults. */
    public DynamicToolCapability(
            String toolName,
            String description,
            String mcpServerName,
            McpTransportType transport,
            boolean requiresApproval,
            String inputSchema,
            ToolCategory category) {
        this(
                toolName,
                description,
                mcpServerName,
                transport,
                requiresApproval,
                inputSchema,
                category,
                DynamicToolOperationDefaults.requireFor(category));
    }

    public DynamicToolCapability {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(mcpServerName, "mcpServerName must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(requiredOperation, "requiredOperation must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}
