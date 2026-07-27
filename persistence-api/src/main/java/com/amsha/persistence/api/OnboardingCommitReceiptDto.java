package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Durable record of an extension commit receipt within an onboarding saga. Survives process
 * restarts so that crash recovery can determine which extensions need compensation.
 *
 * <p>States: PREPARED (side effects not yet executed), COMMITTED (side effects executed, receipt
 * captured), COMPENSATED (reverse side effects executed), COMPENSATION_FAILED (compensation
 * attempted but failed — requires manual remediation).</p>
 */
public record OnboardingCommitReceiptDto(
        String id,
        String draftId,
        String extensionId,
        String operationId,
        String receipt,
        String state,
        Instant createdAt,
        Instant updatedAt) {

    public OnboardingCommitReceiptDto {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (draftId == null || draftId.isBlank()) throw new IllegalArgumentException("draftId is required");
        if (extensionId == null || extensionId.isBlank()) throw new IllegalArgumentException("extensionId is required");
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId is required");
        if (state == null || state.isBlank()) throw new IllegalArgumentException("state is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
