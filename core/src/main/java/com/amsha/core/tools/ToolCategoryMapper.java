package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.agent.role.ToolCapability;

@ApplicationScoped
public class ToolCategoryMapper {

    @Inject
    ToolRegistry toolRegistry;

    public ToolCategory categoryFor(String toolName) {
        if (toolName == null) return ToolCategory.INTERNAL;
        String base = toolName.contains(":") ? toolName.split(":", 2)[1] : toolName;

        // 1. Check if a registered agent tool declares its own category
        if (toolRegistry != null) {
            Optional<AgentTool> tool = toolRegistry.findTool(base);
            if (tool.isPresent()) {
                ToolCategory declared = tool.get().category();
                if (declared != ToolCategory.INTERNAL) {
                    return declared;
                }
            }
        }

        // 2. Fall back to ToolCapability for built-in tools
        return ToolCapability.fromToolName(base).map(ToolCapability::category).orElse(ToolCategory.INTERNAL);
    }
}
