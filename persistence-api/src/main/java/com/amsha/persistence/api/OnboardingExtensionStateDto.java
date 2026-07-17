package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Durable outcome of an optional onboarding extension step. Stores only extension ID/version, scope,
 * outcome, and timestamps; commit intent/receipt status belongs to the draft operation journal.
 */
public record OnboardingExtensionStateDto(
        String draftId,
        String extensionId,
        String extensionVersion,
        String scope,
        String outcome,
        Instant createdAt,
        Instant updatedAt) {

    public static final String OUTCOME_CONFIGURED = "CONFIGURED";
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    public OnboardingExtensionStateDto {
        if (draftId == null || draftId.isBlank()) throw new IllegalArgumentException("draftId is required");
        if (extensionId == null || extensionId.isBlank()) throw new IllegalArgumentException("extensionId is required");
        if (outcome == null || outcome.isBlank()) throw new IllegalArgumentException("outcome is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
