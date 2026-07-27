package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;

/**
 * Durable, resumable onboarding draft owned by a stable principal. Holds only non-secret state:
 * purpose, plan fingerprint, current position, selected non-secret IDs, baseline revisions, validation
 * state, pending approval IDs, safe field errors, expiry, and lifecycle state. It never stores a raw
 * input map, secret, OAuth token, provider response, endpoint header, or vault value.
 */
public record OnboardingDraftDto(
        String draftId,
        String ownerId,
        OnboardingDraftPurpose purpose,
        String planFingerprint,
        String currentStepId,
        List<String> completedStepIds,
        List<String> selectedNonSecretIds,
        String safeFieldErrors,
        String pendingApprovalIds,
        OnboardingDraftState state,
        int revision,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public OnboardingDraftDto {
        if (draftId == null || draftId.isBlank()) throw new IllegalArgumentException("draftId is required");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        if (purpose == null) throw new IllegalArgumentException("purpose is required");
        if (state == null) throw new IllegalArgumentException("state is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
