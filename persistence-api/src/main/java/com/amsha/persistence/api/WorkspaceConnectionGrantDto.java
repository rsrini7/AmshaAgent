package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Authorization record allowing a workspace to use a {@code USER}-scoped connection without
 * copying its secret. Members may use the connection; owner/admin roles control rotation/deletion.
 */
public record WorkspaceConnectionGrantDto(
        String workspaceId,
        String connectionId,
        String grantedByUserId,
        String grantScope,
        String state,
        Instant createdAt,
        Instant updatedAt) {

    public WorkspaceConnectionGrantDto {
        requireText(workspaceId, "workspaceId");
        requireText(connectionId, "connectionId");
        requireText(grantedByUserId, "grantedByUserId");
        requireText(state, "state");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
