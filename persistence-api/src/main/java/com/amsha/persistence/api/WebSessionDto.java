package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing a browser web session.
 */
public record WebSessionDto(
        String sessionId,
        String userId,
        String tokenDigest,
        String csrfDigest,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant rotatedAt,
        Instant revokedAt,
        String safeClientMetadata,
        int revision,
        Instant createdAt,
        Instant updatedAt) {

    public WebSessionDto {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (tokenDigest == null || tokenDigest.isBlank()) throw new IllegalArgumentException("tokenDigest is required");
        if (csrfDigest == null || csrfDigest.isBlank()) throw new IllegalArgumentException("csrfDigest is required");
        if (idleExpiresAt == null) throw new IllegalArgumentException("idleExpiresAt is required");
        if (absoluteExpiresAt == null) throw new IllegalArgumentException("absoluteExpiresAt is required");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
