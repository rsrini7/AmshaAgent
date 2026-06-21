package com.amsha.mcp.config;

import java.util.List;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * restrictive allowlist for which internal tools are exposed via MCP.
 */
@ConfigMapping(prefix = "amsha.mcp")
public interface McpToolAllowlist {
    /**
     * ToolCapability.toolName() values of internal tools to expose via MCP.
     * Uses toolName() (lowercase) values to match external MCP conventions.
     * "github" matches ToolCapability.GITHUB.toolName().
     * "recall_memory_search" matches ToolCapability.RECALL_MEMORY_SEARCH.toolName().
     * "archival_memory_search" matches ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName().
     */
    @WithDefault("github,recall_memory_search,archival_memory_search")
    List<String> exposedTools();

    /**
     * Globally enable/disable the MCP server capability.
     */
    @WithDefault("true")
    boolean serverEnabled();
}
