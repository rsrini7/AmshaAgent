package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for resumable onboarding draft metadata and cleanup ownership. Three backends (SQLite,
 * PostgreSQL, filesystem) implement one externally atomic contract; the filesystem implementation
 * provides atomic visibility through the {@code FilesystemFileStore} temp + atomic-move swap.
 */
public interface OnboardingDraftStorePort {

    Optional<OnboardingDraftDto> findById(String draftId);

    /**
     * Finds the most recent non-terminal draft (DRAFT, WAITING_APPROVAL, RESUMABLE, COMMITTING) for
     * the owner, used to offer Resume / Discard on login.
     */
    Optional<OnboardingDraftDto> findActiveByOwner(String ownerId, Instant now);

    List<OnboardingDraftDto> listByOwner(String ownerId);

    /**
     * Creates a new draft with revision 1. Fails if a draft with the same id already exists.
     */
    boolean save(OnboardingDraftDto draft);

    /**
     * Updates a draft only when the persisted revision matches {@code expectedRevision}; bumps the
     * revision on success. Returns true when applied.
     */
    boolean update(OnboardingDraftDto draft, int expectedRevision);

    int delete(String draftId);

    /**
     * Finds all drafts that have expired (expiresAt before the given instant). Used by the
     * scheduled cleanup job to discard stale drafts and their staged vault secrets.
     */
    List<OnboardingDraftDto> findExpired(Instant now);
}
