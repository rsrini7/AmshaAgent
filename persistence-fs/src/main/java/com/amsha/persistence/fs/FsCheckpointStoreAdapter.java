package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.ReasoningStepDto;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
@SuppressWarnings("PMD.ExcessiveClassLength") // Data access adapter — many store methods can't be split
public class FsCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Set<String> ACTIVE_TREE_STATUSES = Set.of(
            CheckpointStatus.IN_PROGRESS.name(),
            CheckpointStatus.RESUMING.name(),
            CheckpointStatus.AWAITING_APPROVAL.name(),
            CheckpointStatus.AWAITING_RETRY.name());

    @Inject
    FilesystemFileStore store;

    @Inject
    FsCheckpointHelper helper;

    @Inject
    FsToolAttemptHelper toolAttemptHelper;

    private FsCheckpointAppendHandler appendHandler;

    public FsCheckpointStoreAdapter() {}

    public FsCheckpointStoreAdapter(
            FilesystemFileStore store, FsCheckpointHelper helper, FsToolAttemptHelper toolAttemptHelper) {
        this.store = store;
        this.helper = helper;
        this.toolAttemptHelper = toolAttemptHelper;
        this.appendHandler = new FsCheckpointAppendHandler(helper, toolAttemptHelper);
    }

    @Override
    public CheckpointDto save(CheckpointDto dto) {
        return helper.upsertCheckpoint(dto, null, false, true);
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
        return helper.upsertCheckpoint(checkpoint, steps, true, true);
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return helper.findById(id);
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return helper.findBySessionAndRole(sessionId, agentRole);
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return helper.loadSession(sessionId).stream()
                .filter(c -> c.status() == null
                        || (!CheckpointStatus.COMPLETED.name().equals(c.status())
                                && !CheckpointStatus.FAILED.name().equals(c.status())
                                && !CheckpointStatus.DENIED.name().equals(c.status())
                                && !CheckpointStatus.ARCHIVED.name().equals(c.status())))
                .toList();
    }

    @Override
    public List<CheckpointDto> findActiveBySessionTree(String rootSessionId) {
        if (rootSessionId == null) return List.of();
        String delegatePrefix = rootSessionId + ":delegate:";
        return helper.loadAll().stream()
                .filter(c -> c.sessionId() != null
                        && (c.sessionId().equals(rootSessionId) || c.sessionId().startsWith(delegatePrefix)))
                .filter(c -> c.status() != null && ACTIVE_TREE_STATUSES.contains(c.status()))
                .toList();
    }

    @Override
    public List<CheckpointDto> findByStatusAndSessionIds(
            String status, List<String> sessionIds, int limit, int offset) {
        return helper.loadAll().stream()
                .filter(c -> status.equals(c.status()) && sessionIds.contains(c.sessionId()))
                .sorted(Comparator.comparing(CheckpointDto::id).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countByStatusAndSessionIds(String status, List<String> sessionIds) {
        return helper.loadAll().stream()
                .filter(c -> status.equals(c.status()) && sessionIds.contains(c.sessionId()))
                .count();
    }

    @Override
    public void transitionStatus(Long id, String newStatus) {
        findById(id)
                .ifPresent(checkpoint ->
                        helper.upsertCheckpoint(withNewStatus(checkpoint, newStatus), null, false, false));
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        return helper.withCheckpointLock(() -> {
            Optional<CheckpointDto> existing = findById(id);
            if (existing.isEmpty() || !expectedStatus.equals(existing.get().status())) return false;
            helper.upsertCheckpoint(withNewStatus(existing.get(), newStatus), null, false, false);
            return true;
        });
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        return helper.withCheckpointLock(() -> {
            Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
            if (existing.isEmpty()) return false;
            CheckpointDto checkpoint = existing.get();
            if (!CheckpointStatus.IN_PROGRESS.name().equals(checkpoint.status())
                    && !CheckpointStatus.AWAITING_APPROVAL.name().equals(checkpoint.status())) {
                return false;
            }
            helper.upsertCheckpoint(withNewStatus(checkpoint, CheckpointStatus.RESUMING.name()), null, false, false);
            return true;
        });
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        helper.withCheckpointLock(() -> {
            findBySessionAndRole(sessionId, agentRole).ifPresent(checkpoint -> {
                if (CheckpointStatus.RESUMING.name().equals(checkpoint.status())) {
                    helper.upsertCheckpoint(
                            withNewStatus(checkpoint, CheckpointStatus.IN_PROGRESS.name()), null, false, false);
                }
            });
            return null;
        });
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return helper.loadSession(sessionId).stream()
                .filter(c -> CheckpointStatus.AWAITING_APPROVAL.name().equals(c.status()))
                .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return helper.loadAll().stream()
                .filter(c -> CheckpointStatus.AWAITING_APPROVAL.name().equals(c.status()))
                .filter(c -> c.updatedAt() != null && c.updatedAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return helper.loadAll().stream()
                .filter(c -> CheckpointStatus.AWAITING_APPROVAL.name().equals(c.status()))
                .filter(c -> c.sessionId() != null && c.sessionId().contains(segment))
                .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return helper.loadSession(sessionId).stream()
                .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return helper.loadSession(sessionId).stream()
                .filter(c -> !CheckpointStatus.ARCHIVED.name().equals(c.status()))
                .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return helper.loadSession(sessionId).stream()
                .filter(c -> status.equals(c.status()))
                .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        helper.withCheckpointLock(() -> {
            CheckpointDto checkpoint =
                    findById(checkpointId).orElseThrow(() -> new IllegalArgumentException("Checkpoint does not exist"));
            FsCheckpointEnvelope current = helper.loadOrCreateEnvelope(checkpoint);
            List<ReasoningStepDto> replacement = steps == null ? List.of() : List.copyOf(steps);
            helper.saveEnvelope(new FsCheckpointEnvelope(
                    FsCheckpointEnvelope.CURRENT_VERSION, current.checkpoint(), replacement, List.of()));
            current.attemptLinks().forEach(link -> helper.deleteAttemptLink(link.workspaceId(), link.attemptId()));
            helper.persistSteps(checkpointId, replacement);
            return null;
        });
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return helper.loadSteps(checkpointId);
    }

    @Override
    public CheckpointAppendResult appendResultIfAbsent(
            String workspaceId,
            Long checkpointId,
            int expectedVersion,
            ReasoningStepDto step,
            List<String> attemptIds) {
        if (appendHandler == null) {
            appendHandler = new FsCheckpointAppendHandler(
                    helper, toolAttemptHelper != null ? toolAttemptHelper : new FsToolAttemptHelper(store));
        }
        return appendHandler.appendResultIfAbsent(workspaceId, checkpointId, expectedVersion, step, attemptIds);
    }

    @Override
    public void deleteSteps(Long checkpointId) {
        helper.deleteSteps(checkpointId);
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return helper.loadAll().stream().filter(c -> status.equals(c.status())).toList();
    }

    @Override
    public List<CheckpointDto> findAllActive() {
        return helper.loadAll().stream()
                .filter(c -> !CheckpointStatus.terminalStatuses().contains(CheckpointStatus.valueOf(c.status())))
                .toList();
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
        return helper.loadSession(sessionId).stream()
                .filter(c -> CheckpointStatus.AWAITING_APPROVAL.name().equals(c.status()))
                .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(cp -> cp.updatedAt() != null && cp.updatedAt().isBefore(cutoff))
                .orElse(false);
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return helper.loadSession(sessionId).stream()
                .anyMatch(c -> c.status() != null
                        && !CheckpointStatus.COMPLETED.name().equals(c.status())
                        && !CheckpointStatus.FAILED.name().equals(c.status())
                        && !CheckpointStatus.DENIED.name().equals(c.status())
                        && !CheckpointStatus.ARCHIVED.name().equals(c.status()));
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    public boolean hasPendingApprovalForChat(String chatId) {
        return helper.loadAll().stream()
                .anyMatch(c -> CheckpointStatus.AWAITING_APPROVAL.name().equals(c.status())
                        && chatId.equals(c.channelPlatformId())
                        && !c.sessionId().startsWith("cron-"));
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return helper.loadAll().stream()
                .filter(c -> CheckpointStatus.IN_PROGRESS.name().equals(c.status()))
                .filter(c -> c.updatedAt() != null && c.updatedAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        for (CheckpointDto cp : helper.loadSession(sessionId)) {
            if (cp.status() != null
                    && !CheckpointStatus.COMPLETED.name().equals(cp.status())
                    && !CheckpointStatus.FAILED.name().equals(cp.status())) {
                save(CheckpointDtoHelper.withFailure(
                        cp,
                        CheckpointStatus.FAILED.name(),
                        Instant.now(),
                        "Session terminated",
                        "SESSION_TERMINATED",
                        null,
                        null));
            }
        }
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        helper.deleteCheckpoint(sessionId, role);
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        helper.purgeOldChatHistory(cutoff);
    }

    @Override
    public void markFailed(Long id, String failReason, String errorCode, String traceId, String rootCauseClass) {
        findById(id)
                .ifPresent(cp -> save(CheckpointDtoHelper.withFailure(
                        cp,
                        CheckpointStatus.FAILED.name(),
                        Instant.now(),
                        failReason,
                        errorCode,
                        traceId,
                        rootCauseClass)));
    }

    @Override
    public void markCompleted(Long id) {
        findById(id)
                .ifPresent(cp ->
                        save(CheckpointDtoHelper.withCompletion(cp, CheckpointStatus.COMPLETED.name(), Instant.now())));
    }

    @Override
    public void markOrphaned(Long id, String reason) {
        findById(id).ifPresent(cp -> save(CheckpointDtoHelper.withOrphanReason(cp, reason)));
    }

    @Override
    public void archive(Long id) {
        transitionStatus(id, CheckpointStatus.ARCHIVED.name());
    }

    @Override
    public boolean claimForRetry(Long id) {
        Optional<CheckpointDto> existing = findById(id);
        if (existing.isEmpty()) return false;
        CheckpointDto cp = existing.get();
        if (!CheckpointStatus.FAILED.name().equals(cp.status())
                && !CheckpointStatus.ORPHANED.name().equals(cp.status())) return false;
        save(CheckpointDtoHelper.withRetry(cp, CheckpointStatus.IN_PROGRESS.name()));
        return true;
    }

    @Override
    public CheckpointUpsertResult upsertWithSteps(
            String sessionId,
            String agentRole,
            String stateJson,
            String autonomy,
            int iterationsUsed,
            String originalGoal,
            String contextDataJson) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        boolean created = existing.isEmpty();
        CheckpointDto toSave = helper.buildUpsertCheckpoint(
                existing.orElse(null),
                sessionId,
                agentRole,
                autonomy,
                stateJson,
                iterationsUsed,
                originalGoal,
                contextDataJson);
        return new CheckpointUpsertResult(save(toSave), created);
    }

    @Override
    public CheckpointUpsertResult upsertAndFinalize(
            String sessionId,
            String agentRole,
            String stateJson,
            String finalStatus,
            List<ReasoningStepDto> steps,
            String originalGoal,
            String contextDataJson) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        boolean created = existing.isEmpty();
        CheckpointDto toSave = helper.buildFinalizedCheckpoint(
                existing.orElse(null), sessionId, agentRole, stateJson, finalStatus, originalGoal, contextDataJson);
        CheckpointDto saved = saveCheckpointWithSteps(toSave, steps);
        return new CheckpointUpsertResult(saved, created);
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        for (CheckpointDto cp : zombies) {
            markFailed(cp.id(), failReason, "ZOMBIE", null, null);
        }
        return helper.loadAll().stream()
                .filter(c -> CheckpointStatus.FAILED.name().equals(c.status()) && failReason.equals(c.failReason()))
                .toList();
    }

    @Override
    public synchronized boolean atomicUpdateBatchToolDecision(
            String sessionId, String agentRole, String toolName, String decision) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        if (existing.isEmpty()) return false;
        CheckpointDto cp = existing.get();
        Optional<String> updated =
                FsCheckpointHelper.updateJsonMapIfPending(cp.batchToolDecisions(), toolName, decision);
        if (updated.isEmpty() || !CheckpointStatus.AWAITING_APPROVAL.name().equals(cp.status())) return false;
        String updatedDecisions = updated.get();
        save(CheckpointDtoHelper.withBatchDecisions(cp, updatedDecisions));
        return true;
    }

    @Override
    public void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId) {
        findById(checkpointId)
                .ifPresent(cp -> save(CheckpointDtoHelper.withChannelInfo(cp, channelType, channelPlatformId)));
    }

    private static CheckpointDto withNewStatus(CheckpointDto cp, String newStatus) {
        return CheckpointDtoHelper.withStatus(cp, newStatus);
    }
}
