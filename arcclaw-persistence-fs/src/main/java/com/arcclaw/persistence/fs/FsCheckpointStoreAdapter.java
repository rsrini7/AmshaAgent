package com.arcclaw.persistence.fs;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class FsCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(FsCheckpointStoreAdapter.class);

    @Inject
    FilesystemFileStore store;

    @Inject
    FsCheckpointHelper helper;

    @Override
    public CheckpointDto save(CheckpointDto dto) {
        String session = dto.sessionId();
        String role = dto.agentRole();
        String fileName = FsCheckpointHelper.sanitizeRole(role) + ".json";

        CheckpointDto toSave;
        if (dto.id() == null) {
            toSave = new CheckpointDto(
                helper.nextId(), session, role, dto.status(), dto.autonomyLevel(),
                dto.stateJson(), dto.channelPlatformId(), dto.channelType(),
                dto.originalGoal(), dto.failedAt(), dto.completedAt(), dto.failReason(),
                dto.pendingToolName(), dto.batchToolDecisions(), dto.pendingToolParametersJson(),
                dto.preExecutedResults(), dto.maxIterations(), dto.iterationsUsed(),
                dto.retryAfterMs(), dto.systemNote(), dto.contextDataJson(),
                dto.errorCode(), dto.traceId(), dto.rootCauseClass(),
                dto.createdAt() != null ? dto.createdAt() : Instant.now(), Instant.now()
            );
        } else {
            toSave = new CheckpointDto(
                dto.id(), session, role, dto.status(), dto.autonomyLevel(),
                dto.stateJson(), dto.channelPlatformId(), dto.channelType(),
                dto.originalGoal(), dto.failedAt(), dto.completedAt(), dto.failReason(),
                dto.pendingToolName(), dto.batchToolDecisions(), dto.pendingToolParametersJson(),
                dto.preExecutedResults(), dto.maxIterations(), dto.iterationsUsed(),
                dto.retryAfterMs(), dto.systemNote(), dto.contextDataJson(),
                dto.errorCode(), dto.traceId(), dto.rootCauseClass(),
                dto.createdAt(), Instant.now()
            );
        }

        store.write(List.of(FsCheckpointHelper.DOMAIN, session, fileName), toSave);
        return toSave;
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
        CheckpointDto saved = save(checkpoint);
        helper.persistSteps(saved.id(), steps);
        return saved;
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return helper.loadAll().stream()
            .filter(c -> id.equals(c.id()))
            .findFirst();
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return helper.findBySessionAndRole(sessionId, agentRole);
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return helper.loadSession(sessionId).stream()
            .filter(c -> c.status() == null || (!"COMPLETED".equals(c.status())
                && !"FAILED".equals(c.status()) && !"ARCHIVED".equals(c.status())))
            .toList();
    }

    @Override
    public void transitionStatus(Long id, String newStatus) {
        findById(id).ifPresent(cp -> save(withNewStatus(cp, newStatus)));
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        Optional<CheckpointDto> existing = findById(id);
        if (existing.isEmpty() || !expectedStatus.equals(existing.get().status())) return false;
        transitionStatus(id, newStatus);
        return true;
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        if (existing.isEmpty()) return false;
        CheckpointDto cp = existing.get();
        if (!"IN_PROGRESS".equals(cp.status()) && !"AWAITING_APPROVAL".equals(cp.status())) return false;
        save(withNewStatus(cp, "RESUMING"));
        return true;
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        findBySessionAndRole(sessionId, agentRole).ifPresent(cp -> {
            if ("RESUMING".equals(cp.status())) save(withNewStatus(cp, "IN_PROGRESS"));
        });
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return helper.loadSession(sessionId).stream()
            .filter(c -> "AWAITING_APPROVAL".equals(c.status()))
            .max(Comparator.comparing(CheckpointDto::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return helper.loadAll().stream()
            .filter(c -> "AWAITING_APPROVAL".equals(c.status()))
            .filter(c -> c.updatedAt() != null && c.updatedAt().isBefore(cutoff))
            .toList();
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return helper.loadAll().stream()
            .filter(c -> "AWAITING_APPROVAL".equals(c.status()))
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
            .filter(c -> !"ARCHIVED".equals(c.status()))
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
        helper.persistSteps(checkpointId, steps);
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        String cpIdStr = checkpointId.toString();
        return store.list(List.of(FsCheckpointHelper.DOMAIN, FsCheckpointHelper.STEPS, cpIdStr), ".json").stream()
            .sorted(Comparator.naturalOrder())
            .map(f -> store.read(List.of(FsCheckpointHelper.DOMAIN, FsCheckpointHelper.STEPS, cpIdStr, f), ReasoningStepDto.class))
            .toList();
    }

    @Override
    public void deleteSteps(Long checkpointId) {
        store.deleteDirectory(List.of(FsCheckpointHelper.DOMAIN, FsCheckpointHelper.STEPS, checkpointId.toString()));
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return helper.loadAll().stream()
            .filter(c -> status.equals(c.status()))
            .toList();
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        return findLatestBySession(sessionId)
            .map(cp -> cp.updatedAt() != null && cp.updatedAt().plusSeconds(timeoutHours * 3600).isBefore(Instant.now()))
            .orElse(false);
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return helper.loadSession(sessionId).stream()
            .anyMatch(c -> c.status() != null && !"COMPLETED".equals(c.status())
                && !"FAILED".equals(c.status()) && !"ARCHIVED".equals(c.status()));
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    public boolean hasPendingApprovalForChat(String chatId) {
        return helper.loadAll().stream()
            .anyMatch(c -> "AWAITING_APPROVAL".equals(c.status()) && chatId.equals(c.channelPlatformId()));
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return helper.loadAll().stream()
            .filter(c -> "IN_PROGRESS".equals(c.status()) || "AWAITING_APPROVAL".equals(c.status()))
            .filter(c -> c.updatedAt() != null && c.updatedAt().isBefore(cutoff))
            .toList();
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        for (CheckpointDto cp : helper.loadSession(sessionId)) {
            if (cp.status() != null && !"COMPLETED".equals(cp.status()) && !"FAILED".equals(cp.status())) {
                save(new CheckpointDto(
                    cp.id(), cp.sessionId(), cp.agentRole(), "FAILED", cp.autonomyLevel(),
                    cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
                    cp.originalGoal(), Instant.now(), cp.completedAt(), "Session terminated",
                    cp.pendingToolName(), cp.batchToolDecisions(), cp.pendingToolParametersJson(),
                    cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
                    cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
                    "SESSION_TERMINATED", null, null, cp.createdAt(), Instant.now()
                ));
            }
        }
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        store.delete(List.of(FsCheckpointHelper.DOMAIN, sessionId, FsCheckpointHelper.sanitizeRole(role) + ".json"));
        helper.deleteStepsForSession(sessionId, role);
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        for (CheckpointDto cp : helper.loadAll()) {
            if (cp.updatedAt() != null && cp.updatedAt().isBefore(cutoff)) {
                store.delete(List.of(FsCheckpointHelper.DOMAIN, cp.sessionId(),
                    FsCheckpointHelper.sanitizeRole(cp.agentRole()) + ".json"));
                store.deleteDirectory(List.of(FsCheckpointHelper.DOMAIN, FsCheckpointHelper.STEPS, cp.id().toString()));
            }
        }
    }

    @Override
    public void markFailed(Long id, String failReason, String errorCode, String traceId, String rootCauseClass) {
        findById(id).ifPresent(cp -> save(new CheckpointDto(
            cp.id(), cp.sessionId(), cp.agentRole(), "FAILED", cp.autonomyLevel(),
            cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
            cp.originalGoal(), Instant.now(), cp.completedAt(), failReason,
            cp.pendingToolName(), cp.batchToolDecisions(), cp.pendingToolParametersJson(),
            cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
            cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
            errorCode, traceId, rootCauseClass, cp.createdAt(), Instant.now()
        )));
    }

    @Override
    public void markCompleted(Long id) {
        findById(id).ifPresent(cp -> save(new CheckpointDto(
            cp.id(), cp.sessionId(), cp.agentRole(), "COMPLETED", cp.autonomyLevel(),
            cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
            cp.originalGoal(), cp.failedAt(), Instant.now(), cp.failReason(),
            cp.pendingToolName(), cp.batchToolDecisions(), cp.pendingToolParametersJson(),
            cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
            cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
            cp.errorCode(), cp.traceId(), cp.rootCauseClass(), cp.createdAt(), Instant.now()
        )));
    }

    @Override
    public void markOrphaned(Long id, String reason) {
        findById(id).ifPresent(cp -> save(new CheckpointDto(
            cp.id(), cp.sessionId(), cp.agentRole(), "ORPHANED", cp.autonomyLevel(),
            cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
            cp.originalGoal(), cp.failedAt(), cp.completedAt(), reason,
            cp.pendingToolName(), cp.batchToolDecisions(), cp.pendingToolParametersJson(),
            cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
            cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
            cp.errorCode(), cp.traceId(), cp.rootCauseClass(), cp.createdAt(), Instant.now()
        )));
    }

    @Override
    public void archive(Long id) {
        transitionStatus(id, "ARCHIVED");
    }

    @Override
    public boolean claimForRetry(Long id) {
        Optional<CheckpointDto> existing = findById(id);
        if (existing.isEmpty()) return false;
        CheckpointDto cp = existing.get();
        if (!"FAILED".equals(cp.status()) && !"ORPHANED".equals(cp.status())) return false;
        save(new CheckpointDto(
            cp.id(), cp.sessionId(), cp.agentRole(), "IN_PROGRESS", cp.autonomyLevel(),
            cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
            cp.originalGoal(), null, null, null,
            cp.pendingToolName(), cp.batchToolDecisions(), cp.pendingToolParametersJson(),
            cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
            cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
            null, null, null, cp.createdAt(), Instant.now()
        ));
        return true;
    }

    @Override
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole,
        String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        boolean created = existing.isEmpty();
        CheckpointDto cp = existing.orElse(null);

        String status = (cp != null && ("AWAITING_APPROVAL".equals(cp.status()) || "RESUMING".equals(cp.status())))
            ? cp.status() : "IN_PROGRESS";
        String goal = (originalGoal != null && !originalGoal.isBlank()) ? originalGoal
            : (cp != null ? cp.originalGoal() : null);

        CheckpointDto saved = save(new CheckpointDto(
            cp != null ? cp.id() : null,
            sessionId, agentRole, status, autonomy, stateJson,
            cp != null ? cp.channelPlatformId() : null, cp != null ? cp.channelType() : null,
            goal, null, null, null, null, null, null, null,
            null, iterationsUsed, null, null, contextDataJson, null, null, null,
            cp != null ? cp.createdAt() : Instant.now(), Instant.now()
        ));
        return new CheckpointUpsertResult(saved, created);
    }

    @Override
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole,
        String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        boolean created = existing.isEmpty();
        CheckpointDto cp = existing.orElse(null);

        Instant now = Instant.now();
        Instant completedAt = "COMPLETED".equals(finalStatus) ? now : null;
        Instant failedAt = "FAILED".equals(finalStatus) ? now : null;
        String goal = (originalGoal != null && !originalGoal.isBlank()) ? originalGoal
            : (cp != null ? cp.originalGoal() : null);

        CheckpointDto saved = save(new CheckpointDto(
            cp != null ? cp.id() : null, sessionId, agentRole, finalStatus,
            cp != null ? cp.autonomyLevel() : null, stateJson,
            cp != null ? cp.channelPlatformId() : null, cp != null ? cp.channelType() : null,
            goal, failedAt, completedAt, null, null, null, null, null,
            cp != null ? cp.maxIterations() : null, cp != null ? cp.iterationsUsed() : 0,
            null, null, contextDataJson, null, null, null,
            cp != null ? cp.createdAt() : now, now
        ));
        helper.persistSteps(saved.id(), steps);
        return new CheckpointUpsertResult(saved, created);
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        for (CheckpointDto cp : zombies) {
            markFailed(cp.id(), failReason, "ZOMBIE", null, null);
        }
        return helper.loadAll().stream()
            .filter(c -> "FAILED".equals(c.status()) && failReason.equals(c.failReason()))
            .toList();
    }

    @Override
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        Optional<CheckpointDto> existing = findBySessionAndRole(sessionId, agentRole);
        if (existing.isEmpty()) return false;
        CheckpointDto cp = existing.get();
        String updatedDecisions = FsCheckpointHelper.updateJsonMap(
            cp.batchToolDecisions() != null ? cp.batchToolDecisions() : "{}", toolName, decision);
        save(new CheckpointDto(
            cp.id(), cp.sessionId(), cp.agentRole(), cp.status(), cp.autonomyLevel(),
            cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
            cp.originalGoal(), cp.failedAt(), cp.completedAt(), cp.failReason(),
            cp.pendingToolName(), updatedDecisions, cp.pendingToolParametersJson(),
            cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
            cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
            cp.errorCode(), cp.traceId(), cp.rootCauseClass(), cp.createdAt(), Instant.now()
        ));
        return true;
    }

    private static CheckpointDto withNewStatus(CheckpointDto cp, String newStatus) {
        return new CheckpointDto(
            cp.id(), cp.sessionId(), cp.agentRole(), newStatus, cp.autonomyLevel(),
            cp.stateJson(), cp.channelPlatformId(), cp.channelType(),
            cp.originalGoal(), cp.failedAt(), cp.completedAt(), cp.failReason(),
            cp.pendingToolName(), cp.batchToolDecisions(), cp.pendingToolParametersJson(),
            cp.preExecutedResults(), cp.maxIterations(), cp.iterationsUsed(),
            cp.retryAfterMs(), cp.systemNote(), cp.contextDataJson(),
            cp.errorCode(), cp.traceId(), cp.rootCauseClass(), cp.createdAt(), Instant.now()
        );
    }
}
