package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Durable, redacted retry record for an MCP credential cleanup failure.
 *
 * <p>The associated connection row may already be deleted, so this record intentionally does not require a live
 * parent connection. It stores only owner scope, non-secret header metadata, and the opaque vault reference needed for
 * retry.</p>
 */
public record McpConnectionCleanupFailureDto(
        String id,
        String connectionId,
        String userId,
        String headerName,
        String vaultKeyRef,
        String reason,
        int attempts,
        Instant createdAt,
        Instant updatedAt) {

    public McpConnectionCleanupFailureDto {
        requireText(id, "id");
        requireText(connectionId, "connectionId");
        requireText(userId, "userId");
        requireText(headerName, "headerName");
        requireText(vaultKeyRef, "vaultKeyRef");
        requireText(reason, "reason");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
