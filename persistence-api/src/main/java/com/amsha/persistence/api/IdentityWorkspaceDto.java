package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing a workspace.
 */
public record IdentityWorkspaceDto(
        String workspaceId, String displayName, String createdBy, Instant createdAt, Instant updatedAt) {

    public IdentityWorkspaceDto {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        if (createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("createdBy is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
