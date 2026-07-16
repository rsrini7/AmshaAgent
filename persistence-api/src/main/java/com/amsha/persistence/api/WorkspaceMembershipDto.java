package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing workspace membership.
 */
public record WorkspaceMembershipDto(
        String workspaceId,
        String userId,
        String role,
        String status,
        int revision,
        Instant createdAt,
        Instant updatedAt) {

    public WorkspaceMembershipDto {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId is required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
