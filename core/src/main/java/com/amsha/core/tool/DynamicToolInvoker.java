package com.amsha.core.tool;

import java.util.Map;

import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;

/** Executes a dynamically registered tool after the agent loop has admitted it. */
public interface DynamicToolInvoker extends DynamicToolCatalog {
    ToolResponse invoke(String toolName, Map<String, Object> params, ToolExecutionContext context);
}
