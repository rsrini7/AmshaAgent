package com.amsha.core.tool;

import java.util.Objects;

import com.amsha.core.security.ToolOperation;
import com.amsha.core.tools.ToolCategory;

/** Public description of a dynamically registered tool. */
public record DynamicToolDescriptor(
        String name,
        String description,
        String inputSchema,
        ToolCategory category,
        boolean requiresApproval,
        ToolOperation requiredOperation) {

    public DynamicToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(requiredOperation, "requiredOperation must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema;
    }
}
