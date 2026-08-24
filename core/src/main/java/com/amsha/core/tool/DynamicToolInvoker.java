package com.amsha.core.tool;

import java.util.Map;

import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

/** Executes a dynamically registered tool after the agent loop has admitted it. */
public interface DynamicToolInvoker extends DynamicToolCatalog {
    ToolResponse invoke(String toolName, Map<String, Object> params, ToolExecutionContext context);
}
