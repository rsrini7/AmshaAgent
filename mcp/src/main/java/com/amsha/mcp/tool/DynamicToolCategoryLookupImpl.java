package com.amsha.mcp.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.tool.DynamicToolCategoryLookup;
import com.amsha.tool.api.ToolCategory;

@ApplicationScoped
public class DynamicToolCategoryLookupImpl implements DynamicToolCategoryLookup {
    @Inject
    DynamicToolCapabilityRegistry registry;

    @Override
    public Optional<ToolCategory> categoryFor(String toolName) {
        return registry.find(toolName).map(DynamicToolCapability::category);
    }
}
