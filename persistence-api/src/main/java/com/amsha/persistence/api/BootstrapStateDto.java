package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Data transfer object representing the deployment bootstrap state machine.
 *
 * <p>State transitions follow {@code UNCLAIMED -> CLAIMING -> CREDENTIAL_ISSUED -> CLAIMED}.
 * The {@code CLAIMING} reservation reserves the owner/workspace IDs before any identity or
 * credential rows are created, so a losing concurrent claimant leaves no orphan rows.
 */
public record BootstrapStateDto(
        String id,
        String status,
        String ownerId,
        String workspaceId,
        String credentialDigest,
        Instant issuedAt,
        Instant claimedAt,
        int revision,
        Instant updatedAt) {

    public static final String STATUS_UNCLAIMED = "UNCLAIMED";
    public static final String STATUS_CLAIMING = "CLAIMING";
    public static final String STATUS_CREDENTIAL_ISSUED = "CREDENTIAL_ISSUED";
    public static final String STATUS_CLAIMED = "CLAIMED";

    public BootstrapStateDto {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    public boolean isUnclaimed() {
        return STATUS_UNCLAIMED.equals(status);
    }

    public boolean isClaiming() {
        return STATUS_CLAIMING.equals(status);
    }

    public boolean isCredentialIssued() {
        return STATUS_CREDENTIAL_ISSUED.equals(status);
    }

    public boolean isClaimed() {
        return STATUS_CLAIMED.equals(status);
    }
}
