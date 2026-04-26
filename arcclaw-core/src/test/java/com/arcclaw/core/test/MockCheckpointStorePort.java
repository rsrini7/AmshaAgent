package com.arcclaw.core.test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockCheckpointStorePort implements CheckpointStorePort {

    private final Map<Long, CheckpointDto> store = new ConcurrentHashMap<>();
    private final Map<Long, List<ReasoningStepDto>> stepsStore = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public CheckpointDto save(CheckpointDto checkpoint) {
        CheckpointDto toSave = checkpoint;
        if (checkpoint.id() == null) {
            toSave = createWithId(checkpoint, idSeq.getAndIncrement());
        }
        store.put(toSave.id(), toSave);
        return toSave;
    }

    private CheckpointDto createWithId(CheckpointDto d, Long id) {
        return new CheckpointDto(id, d.sessionId(), d.agentRole(), d.status(), d.autonomyLevel(), d.stateJson(),
                d.channelPlatformId(), d.channelType(), d.originalGoal(), d.failedAt(), d.completedAt(),
                d.failReason(), d.pendingToolName(), d.batchToolDecisions(), d.pendingToolParametersJson(),
                d.preExecutedResults(), d.maxIterations(), d.iterationsUsed(), d.retryAfterMs(), d.systemNote(),
                d.errorCode(), d.traceId(), d.rootCauseClass(), d.createdAt() == null ? Instant.now() : d.createdAt(),
                Instant.now());
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
        CheckpointDto saved = save(checkpoint);
        saveSteps(saved.id(), steps);
        return saved;
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return store.values().stream()
                .filter(c -> c.sessionId().equals(sessionId) && c.agentRole().equals(agentRole))
                .findFirst();
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return store.values().stream()
                .filter(c -> c.sessionId().equals(sessionId) && !"FAILED".equals(c.status()) && !"COMPLETED".equals(c.status()))
                .collect(Collectors.toList());
    }

    @Override
    public void transitionStatus(Long id, String newStatus) {
        CheckpointDto existing = store.get(id);
        if (existing != null) {
            CheckpointDto updated = new CheckpointDto(existing.id(), existing.sessionId(), existing.agentRole(),
                    newStatus, existing.autonomyLevel(), existing.stateJson(), existing.channelPlatformId(),
                    existing.channelType(), existing.originalGoal(), existing.failedAt(), existing.completedAt(),
                    existing.failReason(), existing.pendingToolName(), existing.batchToolDecisions(),
                    existing.pendingToolParametersJson(), existing.preExecutedResults(), existing.maxIterations(),
                    existing.iterationsUsed(), existing.retryAfterMs(), existing.systemNote(), existing.errorCode(),
                    existing.traceId(), existing.rootCauseClass(), existing.createdAt(), Instant.now());
            store.put(id, updated);
        }
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return store.values().stream()
                .filter(c -> "AWAITING_APPROVAL".equals(c.status()) && c.updatedAt().isBefore(cutoff))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return store.values().stream()
                .filter(c -> c.sessionId().contains(segment) && "AWAITING_APPROVAL".equals(c.status()))
                .max((c1, c2) -> c1.id().compareTo(c2.id()));
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return store.values().stream()
                .filter(c -> c.sessionId().equals(sessionId))
                .max((c1, c2) -> c1.id().compareTo(c2.id()));
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return findLatestBySession(sessionId);
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return store.values().stream()
                .filter(c -> c.sessionId().equals(sessionId) && status.equals(c.status()))
                .max((c1, c2) -> c1.id().compareTo(c2.id()));
    }

    @Override
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        stepsStore.put(checkpointId, new ArrayList<>(steps));
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return stepsStore.getOrDefault(checkpointId, Collections.emptyList());
    }

    @Override
    public void deleteSteps(Long checkpointId) {
        stepsStore.remove(checkpointId);
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return store.values().stream()
                .filter(c -> status.equals(c.status()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        return false;
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return true;
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return findLatestBySessionAndStatus(sessionId, "AWAITING_APPROVAL");
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return store.values().stream()
                .filter(c -> "IN_PROGRESS".equals(c.status()) && c.updatedAt().isBefore(cutoff))
                .collect(Collectors.toList());
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        findActiveBySession(sessionId).forEach(c -> transitionStatus(c.id(), "FAILED"));
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        findBySessionAndRole(sessionId, role).ifPresent(c -> store.remove(c.id()));
    }

    @Override
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, String stateJson, String autonomy, int iterationsUsed, String originalGoal) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        if (existing.isPresent()) {
            CheckpointDto e = existing.get();
            CheckpointDto updated = new CheckpointDto(e.id(), e.sessionId(), e.agentRole(), e.status(), autonomy, stateJson,
                    e.channelPlatformId(), e.channelType(), e.originalGoal() != null ? e.originalGoal() : originalGoal, e.failedAt(), e.completedAt(),
                    e.failReason(), e.pendingToolName(), e.batchToolDecisions(), e.pendingToolParametersJson(),
                    e.preExecutedResults(), e.maxIterations(), iterationsUsed, e.retryAfterMs(), e.systemNote(),
                    e.errorCode(), e.traceId(), e.rootCauseClass(), e.createdAt(), Instant.now());
            store.put(e.id(), updated);
            return new CheckpointUpsertResult(updated, false);
        } else {
            CheckpointDto created = save(new CheckpointDto(null, sessionId, agentRole, "IN_PROGRESS", autonomy, stateJson,
                    null, null, originalGoal, null, null, null, null, null, null, null, null, iterationsUsed, null, null, null, null, null, Instant.now(), Instant.now()));
            return new CheckpointUpsertResult(created, true);
        }
    }

    @Override
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal) {
        CheckpointUpsertResult result = upsertWithSteps(sessionId, agentRole, stateJson, "SUPERVISED", 0, originalGoal);
        
        // Finalize state and timestamps
        CheckpointDto existing = result.checkpoint();
        Instant completedAt = "COMPLETED".equals(finalStatus) ? Instant.now() : existing.completedAt();
        Instant failedAt = "FAILED".equals(finalStatus) ? Instant.now() : existing.failedAt();
        
        CheckpointDto updated = new CheckpointDto(existing.id(), existing.sessionId(), existing.agentRole(),
                finalStatus, existing.autonomyLevel(), existing.stateJson(), existing.channelPlatformId(),
                existing.channelType(), existing.originalGoal() != null ? existing.originalGoal() : originalGoal, failedAt, completedAt,
                existing.failReason(), existing.pendingToolName(), existing.batchToolDecisions(),
                existing.pendingToolParametersJson(), existing.preExecutedResults(), existing.maxIterations(),
                existing.iterationsUsed(), existing.retryAfterMs(), existing.systemNote(), existing.errorCode(),
                existing.traceId(), existing.rootCauseClass(), existing.createdAt(), Instant.now());
        
        store.put(updated.id(), updated);
        saveSteps(updated.id(), steps);
        
        return new CheckpointUpsertResult(updated, result.created());
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        zombies.forEach(c -> markFailed(c.id(), failReason, "ZOMBIE", null, null));
        return zombies;
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {}

    @Override
    public void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        CheckpointDto existing = store.get(checkpointId);
        if (existing != null) {
            CheckpointDto updated = new CheckpointDto(existing.id(), existing.sessionId(), existing.agentRole(),
                    "FAILED", existing.autonomyLevel(), existing.stateJson(), existing.channelPlatformId(),
                    existing.channelType(), existing.originalGoal(), Instant.now(), existing.completedAt(),
                    failReason, existing.pendingToolName(), existing.batchToolDecisions(),
                    existing.pendingToolParametersJson(), existing.preExecutedResults(), existing.maxIterations(),
                    existing.iterationsUsed(), existing.retryAfterMs(), existing.systemNote(), errorCode,
                    traceId, rootCauseClass, existing.createdAt(), Instant.now());
            store.put(checkpointId, updated);
        }
    }

    @Override
    public void markCompleted(Long checkpointId) {
        transitionStatus(checkpointId, "COMPLETED");
    }

    @Override
    public void markOrphaned(Long checkpointId, String reason) {
        transitionStatus(checkpointId, "ARCHIVED");
    }

    @Override
    public void archive(Long checkpointId) {
        transitionStatus(checkpointId, "ARCHIVED");
    }

    @Override
    public boolean claimForRetry(Long id) {
        CheckpointDto existing = store.get(id);
        if (existing != null && "AWAITING_RETRY".equals(existing.status())) {
            // Updated to clear retryAfterMs to match real implementation
            CheckpointDto updated = new CheckpointDto(existing.id(), existing.sessionId(), existing.agentRole(),
                    "IN_PROGRESS", existing.autonomyLevel(), existing.stateJson(), existing.channelPlatformId(),
                    existing.channelType(), existing.originalGoal(), existing.failedAt(), existing.completedAt(),
                    existing.failReason(), existing.pendingToolName(), existing.batchToolDecisions(),
                    existing.pendingToolParametersJson(), existing.preExecutedResults(), existing.maxIterations(),
                    existing.iterationsUsed(), null, // clear retryAfterMs
                    existing.systemNote(), existing.errorCode(),
                    existing.traceId(), existing.rootCauseClass(), existing.createdAt(), Instant.now());
            store.put(id, updated);
            return true;
        }
        return false;
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        CheckpointDto existing = store.get(id);
        if (existing != null && existing.status().equals(expectedStatus)) {
            transitionStatus(id, newStatus);
            return true;
        }
        return false;
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        Optional<CheckpointDto> latest = findLatestAwaitingApproval(sessionId);
        if (latest.isPresent() && latest.get().agentRole().equals(agentRole)) {
            transitionStatus(latest.get().id(), "RESUMING");
            return true;
        }
        return false;
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        store.values().stream()
                .filter(c -> c.sessionId().equals(sessionId) && c.agentRole().equals(agentRole) && "RESUMING".equals(c.status()))
                .max((c1, c2) -> c1.id().compareTo(c2.id()))
                .ifPresent(c -> transitionStatus(c.id(), "AWAITING_APPROVAL"));
    }

    @Override
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        return true;
    }
}
