package com.amsha.core.tool;

import java.util.Optional;

import com.amsha.core.tools.ToolCategory;

/** Resolves the execution category declared by a discovered dynamic tool. */
public interface DynamicToolCategoryLookup {
    Optional<ToolCategory> categoryFor(String toolName);
}
