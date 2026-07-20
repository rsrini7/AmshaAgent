package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsOnboardingDraftStoreAdapter implements OnboardingDraftStorePort {

    private static final String DOMAIN = "onboarding";
    private static final String DRAFTS_DIR = "drafts";

    @Inject
    FilesystemFileStore store;

    private Path draftPath(String draftId) {
        return store.resolve(List.of(DOMAIN, DRAFTS_DIR, draftId + ".json"));
    }

    @Override
    public synchronized Optional<OnboardingDraftDto> findById(String draftId) {
        return store.readOptional(List.of(DOMAIN, DRAFTS_DIR, draftId + ".json"), OnboardingDraftDto.class);
    }

    @Override
    public synchronized Optional<OnboardingDraftDto> findActiveByOwner(String ownerId, Instant now) {
        return listByOwner(ownerId).stream()
                .filter(d -> d.state() == OnboardingDraftState.DRAFT
                        || d.state() == OnboardingDraftState.WAITING_APPROVAL
                        || d.state() == OnboardingDraftState.RESUMABLE
                        || d.state() == OnboardingDraftState.COMMITTING)
                .filter(d -> d.expiresAt() == null || d.expiresAt().isAfter(now))
                .max(Comparator.comparing(OnboardingDraftDto::updatedAt));
    }

    @Override
    public synchronized List<OnboardingDraftDto> listByOwner(String ownerId) {
        List<OnboardingDraftDto> result = new ArrayList<>();
        File dir = store.resolve(List.of(DOMAIN, DRAFTS_DIR)).toFile();
        if (dir.isDirectory()) {
            for (File f : dir.listFiles((d, n) -> n.endsWith(".json"))) {
                store.readOptional(List.of(DOMAIN, DRAFTS_DIR, f.getName()), OnboardingDraftDto.class)
                        .ifPresent(d -> {
                            if (d.ownerId().equals(ownerId)) result.add(d);
                        });
            }
        }
        result.sort(Comparator.comparing(OnboardingDraftDto::updatedAt).reversed());
        return result;
    }

    @Override
    public synchronized boolean save(OnboardingDraftDto draft) {
        if (store.readOptional(List.of(DOMAIN, DRAFTS_DIR, draft.draftId() + ".json"), OnboardingDraftDto.class)
                .isPresent()) {
            return false;
        }
        store.write(List.of(DOMAIN, DRAFTS_DIR, draft.draftId() + ".json"), draft);
        return true;
    }

    @Override
    public synchronized boolean update(OnboardingDraftDto draft, int expectedRevision) {
        Optional<OnboardingDraftDto> existing = findById(draft.draftId());
        if (existing.isEmpty() || existing.get().revision() != expectedRevision) {
            return false;
        }
        // Mirror the SQL backends' `revision = revision + 1` so persisted drafts stay consistent
        // with the optimistic-lock contract documented on OnboardingDraftStorePort.update().
        OnboardingDraftDto bumped = new OnboardingDraftDto(
                draft.draftId(),
                draft.ownerId(),
                draft.purpose(),
                draft.planFingerprint(),
                draft.currentStepId(),
                draft.completedStepIds(),
                draft.selectedNonSecretIds(),
                draft.safeFieldErrors(),
                draft.pendingApprovalIds(),
                draft.state(),
                expectedRevision + 1,
                draft.expiresAt(),
                draft.createdAt(),
                draft.updatedAt());
        store.write(List.of(DOMAIN, DRAFTS_DIR, draft.draftId() + ".json"), bumped);
        return true;
    }

    @Override
    public synchronized int delete(String draftId) {
        Path p = draftPath(draftId);
        try {
            if (Files.deleteIfExists(p)) {
                return 1;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete draft " + draftId, e);
        }
        return 0;
    }
}
