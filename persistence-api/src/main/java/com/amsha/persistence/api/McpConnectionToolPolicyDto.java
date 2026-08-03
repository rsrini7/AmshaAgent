package com.amsha.persistence.api;

import java.time.Instant;

/** Non-secret, user-scoped policy and bounded discovery metadata for one MCP tool. */
public record McpConnectionToolPolicyDto(
        String id,
        String connectionId,
        String userId,
        String toolName,
        boolean enabled,
        String description,
        String inputSchema,
        String schemaHash,
        Instant discoveredAt,
        long revision,
        Instant updatedAt) {

    public McpConnectionToolPolicyDto {
        requireText(id, "id");
        requireText(connectionId, "connectionId");
        requireText(userId, "userId");
        requireText(toolName, "toolName");
        if (toolName.length() > 256) {
            throw new IllegalArgumentException("toolName exceeds maximum length");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema;
        schemaHash = schemaHash == null ? "" : schemaHash;
        if (description.length() > 8_192) {
            throw new IllegalArgumentException("description exceeds maximum length");
        }
        if (inputSchema.length() > 131_072) {
            throw new IllegalArgumentException("inputSchema exceeds maximum length");
        }
        if (discoveredAt == null) {
            throw new IllegalArgumentException("discoveredAt is required");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
