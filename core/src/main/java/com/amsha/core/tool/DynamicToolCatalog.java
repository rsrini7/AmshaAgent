package com.amsha.core.tool;

import java.util.Collection;
import java.util.Optional;

/** Provides the dynamically registered tools visible in the current workspace scope. */
public interface DynamicToolCatalog {
    Collection<DynamicToolDescriptor> listTools();

    default Optional<DynamicToolDescriptor> findTool(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return listTools().stream().filter(tool -> toolName.equals(tool.name())).findFirst();
    }
}
