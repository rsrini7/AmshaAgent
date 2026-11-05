package com.amsha.persistence.fs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptDto;

/**
 * Encapsulates atomic, compare-and-set checkpoint version checks, attempt validation, and reasoning
 * step appends for the filesystem store per Proposal §12.6.
 *
 * <p>Replay precedence: Idempotency is evaluated prior to CAS version checks. If all attempt IDs
 * are already linked to the current checkpoint and the stored step matches the incoming step across
 * all persisted fields, {@link CheckpointAppendResult#ALREADY_PRESENT} is returned immediately.
 */
class FsCheckpointAppendHandler {

    private final FsCheckpointHelper checkpointHelper;
    private final FsToolAttemptHelper toolAttemptHelper;

    FsCheckpointAppendHandler(FsCheckpointHelper checkpointHelper, FsToolAttemptHelper toolAttemptHelper) {
        this.checkpointHelper = checkpointHelper;
        this.toolAttemptHelper = toolAttemptHelper;
    }

    CheckpointAppendResult appendResultIfAbsent(
            String workspaceId,
            Long checkpointId,
            int expectedVersion,
            ReasoningStepDto step,
            List<String> attemptIds) {
        if (!areInputsValid(workspaceId, checkpointId, step, attemptIds)) {
            return CheckpointAppendResult.CONFLICT;
        }
        return checkpointHelper.withCheckpointLock(() -> toolAttemptHelper.withWorkspaceLock(
                workspaceId, () -> appendLocked(workspaceId, checkpointId, expectedVersion, step, attemptIds)));
    }

    private CheckpointAppendResult appendLocked(
            String workspaceId,
            Long checkpointId,
            int expectedVersion,
            ReasoningStepDto step,
            List<String> attemptIds) {
        Optional<CheckpointDto> checkpointOptional = checkpointHelper.findById(checkpointId);
        if (checkpointOptional.isEmpty()) return CheckpointAppendResult.CONFLICT;
        CheckpointDto checkpoint = checkpointOptional.get();
        FsCheckpointEnvelope envelope = checkpointHelper.loadOrCreateEnvelope(checkpoint);
        checkpointHelper.ensureAttemptLinksIndexed(envelope);

        for (String attemptId : attemptIds) {
            Optional<FsToolAttemptEnvelope> attemptEnvelope =
                    toolAttemptHelper.loadAttemptEnvelope(workspaceId, attemptId);
            if (attemptEnvelope.isEmpty()) return CheckpointAppendResult.CONFLICT;
            ToolAttemptDto attempt = attemptEnvelope.get().attempt();
            if (attempt == null
                    || !Objects.equals(attempt.sessionId(), checkpoint.sessionId())
                    || !Objects.equals(attempt.workspaceId(), workspaceId)) {
                return CheckpointAppendResult.CONFLICT;
            }
        }

        List<FsAttemptLink> existingLinks = new ArrayList<>();
        for (String attemptId : attemptIds) {
            List<FsAttemptLink> links = checkpointHelper.findAttemptLinks(workspaceId, attemptId);
            if (links.size() > 1) return CheckpointAppendResult.CONFLICT;
            existingLinks.addAll(links);
        }
        if (!existingLinks.isEmpty()) {
            if (existingLinks.size() != attemptIds.size()) return CheckpointAppendResult.CONFLICT;
            long linkedStepId = existingLinks.getFirst().reasoningStepId();
            for (FsAttemptLink link : existingLinks) {
                if (!Objects.equals(link.checkpointId(), checkpointId)
                        || link.reasoningStepId() != linkedStepId
                        || !Objects.equals(link.sessionId(), checkpoint.sessionId())
                        || !Objects.equals(link.workspaceId(), workspaceId)) {
                    return CheckpointAppendResult.CONFLICT;
                }
            }
            int stepIndex = (int) linkedStepId - 1;
            if (stepIndex < 0 || stepIndex >= envelope.steps().size()) return CheckpointAppendResult.CONFLICT;
            return Objects.equals(envelope.steps().get(stepIndex), step)
                    ? CheckpointAppendResult.ALREADY_PRESENT
                    : CheckpointAppendResult.CONFLICT;
        }

        CheckpointDto currentCheckpoint = envelope.checkpoint();
        int currentVersion = currentCheckpoint.version() != null ? currentCheckpoint.version() : 0;
        if (currentVersion != expectedVersion) return CheckpointAppendResult.STALE;
        CheckpointDto updatedCheckpoint = CheckpointDtoHelper.withVersion(currentCheckpoint, currentVersion + 1)
                .ensureTimestamps(currentCheckpoint.createdAt(), Instant.now());
        List<ReasoningStepDto> updatedSteps = new ArrayList<>(envelope.steps());
        updatedSteps.add(step);
        long newStepId = updatedSteps.size();
        List<FsAttemptLink> updatedLinks = new ArrayList<>(envelope.attemptLinks());
        List<FsAttemptLink> addedLinks = new ArrayList<>();
        for (String attemptId : attemptIds) {
            FsAttemptLink link =
                    new FsAttemptLink(checkpointId, attemptId, checkpoint.sessionId(), workspaceId, newStepId);
            updatedLinks.add(link);
            addedLinks.add(link);
        }

        FsCheckpointEnvelope updatedEnvelope = new FsCheckpointEnvelope(
                FsCheckpointEnvelope.CURRENT_VERSION, updatedCheckpoint, updatedSteps, updatedLinks);
        checkpointHelper.saveEnvelope(updatedEnvelope);
        for (FsAttemptLink link : addedLinks) {
            checkpointHelper.saveAttemptLink(link);
        }
        checkpointHelper.saveCheckpointFile(updatedCheckpoint);
        checkpointHelper.persistStep(checkpointId, (int) newStepId, step);
        return CheckpointAppendResult.APPENDED;
    }

    private static boolean areInputsValid(
            String workspaceId, Long checkpointId, ReasoningStepDto step, List<String> attemptIds) {
        if (workspaceId == null
                || workspaceId.isBlank()
                || checkpointId == null
                || step == null
                || attemptIds == null
                || attemptIds.isEmpty()) {
            return false;
        }
        for (String id : attemptIds) {
            if (id == null || id.isBlank()) {
                return false;
            }
        }
        return Set.copyOf(attemptIds).size() == attemptIds.size();
    }

    private static boolean matchesStep(ReasoningStepDto stored, ReasoningStepDto incoming) {
        return Objects.equals(stored, incoming);
    }
}
