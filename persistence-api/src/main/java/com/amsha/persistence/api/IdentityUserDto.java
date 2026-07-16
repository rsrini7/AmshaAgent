package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing a durable user identity.
 */
public record IdentityUserDto(String userId, String displayName, String status, Instant createdAt, Instant updatedAt) {

    public IdentityUserDto {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
