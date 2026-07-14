package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing an external identity (e.g. OIDC or GitHub OAuth).
 */
public record ExternalIdentityDto(
        String providerId,
        String issuer,
        String subject,
        String userId,
        String safeClaims,
        Instant createdAt,
        Instant updatedAt) {

    public ExternalIdentityDto {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId is required");
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject is required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
