package com.amsha.core.tool;

import java.util.Objects;

import com.amsha.core.security.ToolOperation;
import com.amsha.core.tools.ToolCategory;

/** Validated admission metadata for a dynamically registered (non-ToolRegistry) tool. */
public record DynamicToolAdmission(
        String toolName, ToolCategory category, boolean requiresApproval, ToolOperation requiredOperation) {

    public DynamicToolAdmission {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(requiredOperation, "requiredOperation must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}
