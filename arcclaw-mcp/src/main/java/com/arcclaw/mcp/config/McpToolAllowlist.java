package com.arcclaw.mcp.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;

/**
 * restrictive allowlist for which internal tools are exposed via MCP.
 */
@ConfigMapping(prefix = "arcclaw.mcp")
public interface McpToolAllowlist {
    /**
     * ToolCapability.toolName() values of internal tools to expose via MCP.
     * Uses toolName() (lowercase) values to match external MCP conventions.
     * "github" matches ToolCapability.GITHUB.toolName().
     * "memory_search" matches ToolCapability.MEMORY_SEARCH.toolName().
     */
    @WithDefault("github,memory_search")
    List<String> exposedTools();

    /**
     * Globally enable/disable the MCP server capability.
     */
    @WithDefault("true")
    boolean serverEnabled();
}
