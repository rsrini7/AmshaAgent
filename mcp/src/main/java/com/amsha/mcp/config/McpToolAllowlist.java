package com.amsha.mcp.config;

import java.util.List;
import java.util.Optional;

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

    /**
     * Interval to check health of active SSE/HTTP outbound MCP connections.
     */
    @WithDefault("15000")
    long healthCheckIntervalMs();

    /**
     * HMAC-SHA-256 secret for ten-minute, single-use, user-bound channel-to-UI MCP
     * setup links. Absent (default) creates a process-local secret that invalidates
     * outstanding links on restart; production deployments should set a stable value.
     */
    Optional<String> setupTokenSecret();

    /**
     * Optional absolute base URL for setup links issued through remote channels.
     * Empty values keep links relative to the current Arc UI origin.
     */
    Optional<String> setupUiBaseUrl();

    /**
     * Podman/Stdio MCP transport settings.
     */
    Podman podman();

    interface Podman {
        /**
         * Interval to check health of active Podman/Stdio outbound MCP connections.
         */
        @WithDefault("15000")
        long healthCheckIntervalMs();
    }
}
