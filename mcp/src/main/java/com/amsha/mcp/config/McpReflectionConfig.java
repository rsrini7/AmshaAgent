package com.amsha.mcp.config;

import com.amsha.mcp.bridge.McpToolDescriptor;
import com.amsha.mcp.bridge.McpToolRequest;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Centrally registers MCP DTOs and beans for reflection in native image.
 * Matches §6.7 invariant rule.
 */
@RegisterForReflection(targets = {McpToolDescriptor.class, McpToolRequest.class})
public class McpReflectionConfig {}
