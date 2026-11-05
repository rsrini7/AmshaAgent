package com.amsha.persistence.fs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Rebuilds attempt-link sidecars from authoritative checkpoint envelopes. */
final class FsCheckpointLinkIndex {

    private final FsCheckpointHelper checkpointHelper;
    private final FilesystemFileStore store;

    FsCheckpointLinkIndex(FsCheckpointHelper checkpointHelper, FilesystemFileStore store) {
        this.checkpointHelper = checkpointHelper;
        this.store = store;
    }

    List<FsCheckpointEnvelope> loadAll() {
        return store.list(List.of(FsCheckpointHelper.DOMAIN, FsCheckpointHelper.ENVELOPES), ".json").stream()
                .map(this::loadEnvelopeByFileName)
                .toList();
    }

    List<FsAttemptLink> find(String workspaceId, String attemptId) {
        if (workspaceId == null || workspaceId.isBlank() || attemptId == null || attemptId.isBlank()) {
            return List.of();
        }
        return checkpointHelper.withCheckpointLock(() -> findLocked(workspaceId, attemptId));
    }

    private List<FsAttemptLink> findLocked(String workspaceId, String attemptId) {
        List<FsAttemptLink> committed = new ArrayList<>();
        for (FsCheckpointEnvelope envelope : loadAll()) {
            for (FsAttemptLink link : envelope.attemptLinks()) {
                if (workspaceId.equals(link.workspaceId()) && attemptId.equals(link.attemptId())) {
                    committed.add(link);
                    ensureIndexed(link);
                }
            }
        }
        Optional<FsAttemptLink> sidecar = readSidecar(workspaceId, attemptId);
        if (sidecar.isPresent() && committed.stream().noneMatch(link -> isSameLink(link, sidecar.get()))) {
            checkpointHelper.deleteAttemptLink(workspaceId, attemptId);
        }
        return List.copyOf(committed);
    }

    private FsCheckpointEnvelope loadEnvelopeByFileName(String fileName) {
        String id = fileName.substring(0, fileName.length() - ".json".length());
        try {
            Long checkpointId = Long.valueOf(id);
            return checkpointHelper
                    .loadEnvelope(checkpointId)
                    .orElseThrow(() -> new IllegalStateException("Checkpoint envelope disappeared while loading"));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid checkpoint envelope file name", e);
        }
    }

    private void ensureIndexed(FsAttemptLink link) {
        Optional<FsAttemptLink> existing = readSidecar(link.workspaceId(), link.attemptId());
        if (existing.isEmpty() || !isSameLink(existing.get(), link)) {
            checkpointHelper.saveAttemptLink(link);
        }
    }

    private Optional<FsAttemptLink> readSidecar(String workspaceId, String attemptId) {
        String encodedWorkspaceId = FsToolAttemptHelper.encodeWorkspaceId(workspaceId);
        return store.readOptional(
                List.of(
                        FsCheckpointHelper.DOMAIN,
                        FsCheckpointHelper.ATTEMPT_LINKS,
                        encodedWorkspaceId,
                        attemptId + ".json"),
                FsAttemptLink.class);
    }

    private static boolean isSameLink(FsAttemptLink left, FsAttemptLink right) {
        return Objects.equals(left.attemptId(), right.attemptId())
                && Objects.equals(left.checkpointId(), right.checkpointId())
                && left.reasoningStepId() == right.reasoningStepId()
                && Objects.equals(left.workspaceId(), right.workspaceId())
                && Objects.equals(left.sessionId(), right.sessionId());
    }
}
