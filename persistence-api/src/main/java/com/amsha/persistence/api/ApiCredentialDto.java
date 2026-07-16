package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing an API credential mapped to a stable user.
 */
public record ApiCredentialDto(
        String credentialId,
        String userId,
        String keyDigest,
        String roleSet,
        int revision,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt) {

    public ApiCredentialDto {
        if (credentialId == null || credentialId.isBlank())
            throw new IllegalArgumentException("credentialId is required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (keyDigest == null || keyDigest.isBlank()) throw new IllegalArgumentException("keyDigest is required");
        if (roleSet == null || roleSet.isBlank()) throw new IllegalArgumentException("roleSet is required");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
