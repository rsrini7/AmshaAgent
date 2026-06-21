package com.arcclaw.mcp.config;

import com.arcclaw.mcp.bridge.McpToolDescriptor;
import com.arcclaw.mcp.bridge.McpToolRequest;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Centrally registers MCP DTOs and beans for reflection in native image.
 * Matches §6.7 invariant rule.
 */
@RegisterForReflection(targets = {McpToolDescriptor.class, McpToolRequest.class})
public class McpReflectionConfig {}
