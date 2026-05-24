package com.arcclaw.core.tools;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.ToolCapability;

@ApplicationScoped
public class ToolCategoryMapper {

    public ToolCategory categoryFor(String toolName) {
        if (toolName == null) return ToolCategory.INTERNAL;
        String base = toolName.contains(":") ? toolName.split(":")[1] : toolName;
        return ToolCapability.fromToolName(base)
            .map(ToolCapability::category)
            .orElse(ToolCategory.INTERNAL);
    }
}
