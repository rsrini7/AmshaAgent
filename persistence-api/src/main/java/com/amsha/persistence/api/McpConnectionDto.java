package com.amsha.persistence.api;

import java.beans.ConstructorProperties;
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
        String state,
        String approvalTaskId,
        long revision,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt) {

    public McpConnectionDto(
            String id,
            String userId,
            String name,
            String transport,
            String endpoint,
            String category,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                userId,
                name,
                transport,
                endpoint,
                category,
                enabled,
                enabled ? "DISCONNECTED" : "PAUSED",
                null,
                0,
                null,
                createdAt,
                updatedAt);
    }

    @ConstructorProperties({
        "id",
        "userId",
        "name",
        "transport",
        "endpoint",
        "category",
        "enabled",
        "state",
        "approvalTaskId",
        "revision",
        "lastErrorCode",
        "createdAt",
        "updatedAt"
    })
    public McpConnectionDto {
        requireText(id, "id");
        requireText(userId, "userId");
        requireText(name, "name");
        requireText(transport, "transport");
        requireText(endpoint, "endpoint");
        requireText(category, "category");
        requireText(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
