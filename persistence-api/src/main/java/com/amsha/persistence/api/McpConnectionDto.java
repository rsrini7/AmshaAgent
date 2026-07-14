package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Durable, non-secret metadata for an outbound MCP connection.
 *
 * <p>Transport remains a string so this persistence API does not depend on the MCP module.
 * MCP transport code validates it against its local transport enum before use.</p>
 */
public record McpConnectionDto(
        String id,
        String userId,
        String name,
        String transport,
        String endpoint,
        String category,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public McpConnectionDto {
        requireText(id, "id");
        requireText(userId, "userId");
        requireText(name, "name");
        requireText(transport, "transport");
        requireText(endpoint, "endpoint");
        requireText(category, "category");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
