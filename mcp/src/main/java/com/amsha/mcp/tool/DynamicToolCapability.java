package com.amsha.mcp.tool;

import java.util.Objects;

import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record DynamicToolCapability(
        String toolName,
        String description,
        String mcpServerName,
        McpTransportType transport,
        boolean requiresApproval,
        String inputSchema,
        ToolCategory category,
        ToolOperation requiredOperation) {

    public static final int MAX_TOOL_NAME_LENGTH = 128;
    public static final int MAX_SERVER_NAME_LENGTH = 128;
    public static final int MAX_DESCRIPTION_LENGTH = 4_096;
    public static final int MAX_INPUT_SCHEMA_LENGTH = 16_384;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Convenience: NETWORK category with NETWORK_OUTBOUND operation. */
    public DynamicToolCapability(
            String toolName,
            String description,
            String mcpServerName,
            McpTransportType transport,
            boolean requiresApproval,
            String inputSchema) {
        this(
                toolName,
                description,
                mcpServerName,
                transport,
                requiresApproval,
                inputSchema,
                ToolCategory.NETWORK,
                ToolOperation.NETWORK_OUTBOUND);
    }

    /** Convenience: derive requiredOperation from category via registration defaults. */
    public DynamicToolCapability(
            String toolName,
            String description,
            String mcpServerName,
            McpTransportType transport,
            boolean requiresApproval,
            String inputSchema,
            ToolCategory category) {
        this(
                toolName,
                description,
                mcpServerName,
                transport,
                requiresApproval,
                inputSchema,
                category,
                DynamicToolOperationDefaults.requireFor(category));
    }

    public DynamicToolCapability {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(mcpServerName, "mcpServerName must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(requiredOperation, "requiredOperation must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (toolName.length() > MAX_TOOL_NAME_LENGTH) {
            throw new IllegalArgumentException("toolName exceeds maximum length of " + MAX_TOOL_NAME_LENGTH);
        }
        if (mcpServerName.isBlank()) {
            throw new IllegalArgumentException("mcpServerName must not be blank");
        }
        if (mcpServerName.length() > MAX_SERVER_NAME_LENGTH) {
            throw new IllegalArgumentException("mcpServerName exceeds maximum length of " + MAX_SERVER_NAME_LENGTH);
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH);
        }
        if (inputSchema.length() > MAX_INPUT_SCHEMA_LENGTH) {
            throw new IllegalArgumentException("inputSchema exceeds maximum length of " + MAX_INPUT_SCHEMA_LENGTH);
        }
        validateInputSchema(inputSchema);
    }

    private static void validateInputSchema(String inputSchema) {
        try {
            JsonNode schema = OBJECT_MAPPER.readTree(inputSchema);
            if (schema == null || !schema.isObject()) {
                throw new IllegalArgumentException("inputSchema must be a JSON object");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("inputSchema must be valid JSON", e);
        }
    }
}
