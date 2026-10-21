package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftStorePort;

/** Migrates legacy plaintext credentials when persisted onboarding drafts are read. */
@ApplicationScoped
public class OnboardingDraftSecretMigrator {

    @Inject
    OnboardingDraftStorePort draftStore;

    @Inject
    OnboardingSelectionScrubber scrubber;

    public Optional<OnboardingDraftDto> findById(String draftId) {
        return draftStore.findById(draftId).map(this::migratePersistedSecrets);
    }

    public Optional<OnboardingDraftDto> findActiveByOwner(String ownerId) {
        return draftStore.findActiveByOwner(ownerId, Instant.now()).map(this::migratePersistedSecrets);
    }

    private OnboardingDraftDto migratePersistedSecrets(OnboardingDraftDto draft) {
        List<String> existingSelections = draft.selectedNonSecretIds();
        if (existingSelections == null || existingSelections.isEmpty()) {
            return draft;
        }

        List<String> scrubbedSelections = scrubber.processAndScrubSecrets(draft.ownerId(), existingSelections);
        if (existingSelections.equals(scrubbedSelections)) {
            return draft;
        }

        OnboardingDraftDto migratedDraft = new OnboardingDraftDto(
                draft.draftId(),
                draft.ownerId(),
                draft.purpose(),
                draft.planFingerprint(),
                draft.currentStepId(),
                draft.completedStepIds(),
                scrubbedSelections,
                draft.safeFieldErrors(),
                draft.pendingApprovalIds(),
                draft.state(),
                draft.revision(),
                draft.expiresAt(),
                draft.createdAt(),
                Instant.now());
        draftStore.update(migratedDraft, draft.revision());
        return migratedDraft;
    }
}
