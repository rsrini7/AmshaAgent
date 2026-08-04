package com.amsha.mcp.config;

import com.amsha.mcp.bridge.McpToolDescriptor;
import com.amsha.mcp.bridge.McpToolRequest;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.transport.HeaderMetadata;
import com.amsha.mcp.transport.HeaderSecretReference;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Centrally registers MCP DTOs and beans for reflection in native image.
 * Matches §6.7 invariant rule.
 */
@RegisterForReflection(
        classNames = {
            "com.amsha.mcp.transport.McpClientService$DiscoveredTool",
            "com.amsha.mcp.transport.McpManagementService$ConnectionSnapshot",
            "com.amsha.mcp.transport.McpManagementService$ApprovalExecution",
            "com.amsha.mcp.transport.McpManagementService$DiscoveryPreview",
            "com.amsha.mcp.transport.McpManagementService$ToolPreview",
            "com.amsha.mcp.transport.McpConfigImportService$ImportPreview",
            "com.amsha.mcp.transport.McpConfigImportService$ImportEntry",
            "com.amsha.mcp.transport.McpApprovalService$PendingApproval",
            "com.amsha.mcp.transport.McpSetupTokenService$SetupGrant"
        },
        targets = {
            McpToolDescriptor.class,
            McpToolRequest.class,
            DynamicToolCapability.class,
            HeaderSecretReference.class,
            HeaderMetadata.class
        })
public class McpReflectionConfig {}
