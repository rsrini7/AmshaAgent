package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing a workspace invitation.
 */
public record IdentityInvitationDto(
        String invitationId,
        String workspaceId,
        String role,
        String tokenDigest,
        String optionalEmailConstraint,
        Instant expiresAt,
        String acceptedBy,
        Instant revokedAt,
        int revision,
        Instant createdAt,
        Instant updatedAt) {

    public IdentityInvitationDto {
        if (invitationId == null || invitationId.isBlank())
            throw new IllegalArgumentException("invitationId is required");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId is required");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        if (tokenDigest == null || tokenDigest.isBlank()) throw new IllegalArgumentException("tokenDigest is required");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
