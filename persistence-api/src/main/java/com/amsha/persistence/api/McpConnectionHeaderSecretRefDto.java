package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Non-secret reference to a vault-backed HTTP header value for an MCP connection.
 */
public record McpConnectionHeaderSecretRefDto(
        String id,
        String connectionId,
        String userId,
        String headerName,
        String vaultKeyRef,
        Instant createdAt,
        Instant updatedAt) {

    public McpConnectionHeaderSecretRefDto {
        requireText(id, "id");
        requireText(connectionId, "connectionId");
        requireText(userId, "userId");
        requireText(headerName, "headerName");
        requireText(vaultKeyRef, "vaultKeyRef");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
