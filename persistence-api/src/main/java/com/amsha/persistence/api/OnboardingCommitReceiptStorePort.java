package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for onboarding saga commit receipts. Survives process restarts so that crash
 * recovery can determine which extensions have been committed and need compensation.
 *
 * <p>Implementations must be transactional and idempotent on receipt upsert (same operationId
 * overwrites the prior state).</p>
 */
public interface OnboardingCommitReceiptStorePort {

    void upsert(OnboardingCommitReceiptDto receipt);

    Optional<OnboardingCommitReceiptDto> findByOperationId(String operationId);

    List<OnboardingCommitReceiptDto> listByDraftId(String draftId);

    int deleteByDraftId(String draftId);
}
