package com.amsha.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao.AttemptLinkRow;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao.AttemptSessionRow;

import org.jdbi.v3.core.Jdbi;

/**
 * Encapsulates compare-and-set checkpoint version checks, attempt validation, and reasoning step
 * appends for PostgreSQL per Proposal §12.6.
 *
 * <p>Replay precedence: Idempotency is evaluated prior to CAS version checks. If all attempt IDs
 * are already linked to the current checkpoint and the stored step matches the incoming step across
 * all persisted fields, {@link CheckpointAppendResult#ALREADY_PRESENT} is returned immediately.
 * Attempt row locks (SELECT ... FOR UPDATE) serialize concurrent append attempts across transactions.
 */
class PostgresCheckpointAppendHandler {

    CheckpointAppendResult appendResultIfAbsent(
            Jdbi jdbi,
            String workspaceId,
            Long checkpointId,
            int expectedVersion,
            ReasoningStepDto step,
            List<String> attemptIds) {
        if (!areInputsValid(workspaceId, checkpointId, step, attemptIds)) {
            return CheckpointAppendResult.CONFLICT;
        }

        return jdbi.inTransaction(handle -> {
            CheckpointJdbiDao dao = handle.attach(CheckpointJdbiDao.class);
            Optional<CheckpointDto> cpOpt = dao.findById(checkpointId);
            if (cpOpt.isEmpty()) {
                return CheckpointAppendResult.CONFLICT;
            }
            CheckpointDto checkpoint = cpOpt.get();

            List<AttemptSessionRow> attempts = dao.findAttemptSessions(workspaceId, attemptIds);
            String targetWs = resolveWorkspace(attempts, checkpoint, workspaceId, attemptIds.size());
            if (targetWs == null) {
                return CheckpointAppendResult.CONFLICT;
            }

            CheckpointAppendResult linkCheck =
                    checkExistingLinks(dao, checkpointId, checkpoint.sessionId(), targetWs, step, attemptIds);
            if (linkCheck != null) {
                return linkCheck;
            }

            int currentVersion = checkpoint.version() != null ? checkpoint.version() : 0;
            if (currentVersion != expectedVersion) {
                return CheckpointAppendResult.STALE;
            }

            Instant now = Instant.now();
            int updated = dao.casVersion(checkpointId, expectedVersion, now);
            if (updated == 0) {
                return CheckpointAppendResult.STALE;
            }

            int seq = dao.nextSequenceNum(checkpointId);
            long stepId = dao.insertStepReturningId(checkpointId, seq, step);
            for (String attemptId : attemptIds) {
                dao.insertAttemptLink(checkpointId, attemptId, checkpoint.sessionId(), targetWs, stepId);
            }
            return CheckpointAppendResult.APPENDED;
        });
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

    private static String resolveWorkspace(
            List<AttemptSessionRow> attempts, CheckpointDto checkpoint, String workspaceId, int expectedCount) {
        if (attempts.size() != expectedCount) {
            return null;
        }
        String session = checkpoint.sessionId();
        for (AttemptSessionRow att : attempts) {
            if (!Objects.equals(att.sessionId(), session) || !Objects.equals(att.workspaceId(), workspaceId)) {
                return null;
            }
        }
        return workspaceId;
    }

    private static CheckpointAppendResult checkExistingLinks(
            CheckpointJdbiDao dao,
            Long checkpointId,
            String sessionId,
            String targetWs,
            ReasoningStepDto incomingStep,
            List<String> attemptIds) {
        List<AttemptLinkRow> links = dao.findLinksByAttemptIds(targetWs, attemptIds);
        if (links.isEmpty()) {
            return null;
        }
        if (links.size() != attemptIds.size()) {
            return CheckpointAppendResult.CONFLICT;
        }
        Long linkedStepId = links.getFirst().reasoningStepId();
        for (AttemptLinkRow link : links) {
            if (!Objects.equals(link.checkpointId(), checkpointId)
                    || !Objects.equals(link.reasoningStepId(), linkedStepId)
                    || !Objects.equals(link.sessionId(), sessionId)
                    || !Objects.equals(link.workspaceId(), targetWs)) {
                return CheckpointAppendResult.CONFLICT;
            }
        }
        Optional<ReasoningStepDto> storedStepOpt = dao.findStepById(checkpointId, linkedStepId);
        if (storedStepOpt.isEmpty()) {
            return CheckpointAppendResult.CONFLICT;
        }
        return matchesStep(storedStepOpt.get(), incomingStep)
                ? CheckpointAppendResult.ALREADY_PRESENT
                : CheckpointAppendResult.CONFLICT;
    }

    private static boolean matchesStep(ReasoningStepDto stored, ReasoningStepDto incoming) {
        return Objects.equals(stored, incoming);
    }
}
