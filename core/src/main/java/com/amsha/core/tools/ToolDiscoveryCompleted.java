package com.amsha.core.tools;

/**
 * Synchronous CDI event fired by ToolRegistry once initial tool discovery and caching completes.
 *
 * @param discoveredToolCount the number of tools discovered and registered
 */
public record ToolDiscoveryCompleted(int discoveredToolCount) {}
