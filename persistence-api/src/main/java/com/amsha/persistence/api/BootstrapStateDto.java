package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing the deployment bootstrap state machine.
 */
public record BootstrapStateDto(
        String id,
        String status,
        String ownerId,
        String credentialDigest,
        Instant issuedAt,
        Instant claimedAt,
        int revision,
        Instant updatedAt) {

    public BootstrapStateDto {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
