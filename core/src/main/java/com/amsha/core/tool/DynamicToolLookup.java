package com.amsha.core.tool;

public interface DynamicToolLookup {
    /**
     * Returns true if a dynamic MCP tool with this name is currently registered.
     * Called by ToolInvocationGuard as a second lookup after the static
     * ToolCapability enum check misses.
     */
    boolean isDynamicToolAllowed(String toolName);
}
