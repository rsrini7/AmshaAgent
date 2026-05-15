package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import io.quarkus.arc.Unremovable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * SQLite implementation of CheckpointStorePort.
 * Acts as a delegation facade for specialized persistence services and repositories.
 */
@ApplicationScoped
@Unremovable
public class SqliteCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(SqliteCheckpointStoreAdapter.class);

    private final SqliteCheckpointStoreAdapter self;
    private final int staleClaimMinutes;
    private final SqliteRetryTemplate retryTemplate;
    private final CheckpointBatchOperations batchOps;
    private final CheckpointCleanupService cleanupService;
    private final CheckpointStatusService statusService;
    private final CheckpointRepository checkpointRepo;
    private final ReasoningStepRepository stepRepo;
    private final CheckpointUpsertService upsertService;

    @Inject
    public SqliteCheckpointStoreAdapter(
            SqliteCheckpointStoreAdapter self,
            @ConfigProperty(name = "arcclaw.checkpoint.stale-claim-minutes", defaultValue = "2") int staleClaimMinutes,
            SqliteRetryTemplate retryTemplate,
            CheckpointBatchOperations batchOps,
            CheckpointCleanupService cleanupService,
            CheckpointStatusService statusService,
            CheckpointRepository checkpointRepo,
            ReasoningStepRepository stepRepo,
            CheckpointUpsertService upsertService) {
        this.self = self;
        this.staleClaimMinutes = staleClaimMinutes;
        this.retryTemplate = retryTemplate;
        this.batchOps = batchOps;
        this.cleanupService = cleanupService;
        this.statusService = statusService;
        this.checkpointRepo = checkpointRepo;
        this.stepRepo = stepRepo;
        this.upsertService = upsertService;
    }

    protected SqliteCheckpointStoreAdapter() {
        this(null, 2, null, null, null, null, null, null, null);
    }

    @Override
    public CheckpointDto save(CheckpointDto checkpointDto) {
        log.infof("[CheckpointAdapter] Saving checkpoint session=%s role=%s",
                checkpointDto.sessionId(), checkpointDto.agentRole());
        return withRetry(() -> self.doSaveEntity(checkpointDto), "save-entity");
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public CheckpointDto doSaveEntity(CheckpointDto checkpointDto) {
        if (checkpointDto.id() == null) {
            var existing = checkpointRepo.findAnyBySessionAndRole(
                    checkpointDto.sessionId(), checkpointDto.agentRole());
            if (!existing.isEmpty()) {
                AgentCheckpointEntity managed = existing.get(0);
                upsertService.updateEntityFromDto(managed, checkpointDto);
                return CheckpointMapper.toDto(checkpointRepo.merge(managed));
            }
            AgentCheckpointEntity entity = newEntityFromDto(checkpointDto);
            return CheckpointMapper.toDto(checkpointRepo.persist(entity));
        }
        return checkpointRepo.findById(checkpointDto.id())
            .map(managed -> {
                upsertService.updateEntityFromDto(managed, checkpointDto);
                return CheckpointMapper.toDto(checkpointRepo.merge(managed));
            })
            .orElseGet(() -> {
                AgentCheckpointEntity entity = newEntityFromDto(checkpointDto);
                return CheckpointMapper.toDto(checkpointRepo.persist(entity));
            });
    }

    private static AgentCheckpointEntity newEntityFromDto(CheckpointDto dto) {
        AgentCheckpointEntity entity = new AgentCheckpointEntity();
        entity.setSessionId(dto.sessionId());
        entity.setAgentRole(dto.agentRole());
        entity.setStatus(dto.status() != null ? CheckpointStatus.valueOf(dto.status()) : null);
        entity.setPendingToolName(dto.pendingToolName());
        entity.setPendingToolParametersJson(dto.pendingToolParametersJson());
        entity.setBatchToolDecisions(dto.batchToolDecisions());
        entity.setPreExecutedResults(dto.preExecutedResults());
        entity.setAutonomyLevel(dto.autonomyLevel() != null ? AutonomyLevel.valueOf(dto.autonomyLevel()) : null);
        entity.setOriginalGoal(dto.originalGoal());
        entity.setContextDataJson(dto.contextDataJson());
        entity.setIterationsUsed(dto.iterationsUsed());
        entity.setMaxIterations(dto.maxIterations());
        entity.setRetryAfterMs(dto.retryAfterMs());
        entity.setChannelType(dto.channelType());
        entity.setChannelPlatformId(dto.channelPlatformId());
        entity.setSystemNote(dto.systemNote());
        entity.setStateJson(dto.stateJson());
        entity.setFailReason(dto.failReason());
        entity.setFailedAt(dto.failedAt());
        entity.setCompletedAt(dto.completedAt());
        entity.setErrorCode(dto.errorCode());
        entity.setTraceId(dto.traceId());
        entity.setRootCauseClass(dto.rootCauseClass());
        entity.setUpdatedAt(dto.updatedAt());
        return entity;
    }

    @Override
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, 
        String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) {
        log.infof("[CheckpointAdapter] Upserting checkpoint with steps for session=%s", sessionId);
        return withRetry(() -> self.doUpsertWithSteps(sessionId, agentRole, stateJson, autonomy, iterationsUsed, originalGoal, contextDataJson), "upsert-steps");
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public CheckpointUpsertResult doUpsertWithSteps(String sessionId, String agentRole, 
            String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) {
        return upsertService.doUpsertWithSteps(sessionId, agentRole, stateJson, autonomy, iterationsUsed, originalGoal, contextDataJson);
    }

    @Override
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, 
        String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        log.infof("[CheckpointAdapter] Upserting and finalizing checkpoint for session=%s (status=%s)", sessionId, finalStatus);
        return withRetry(() -> self.doUpsertAndFinalize(sessionId, agentRole, stateJson, finalStatus, steps, originalGoal, contextDataJson), "upsert-and-finalize");
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public CheckpointUpsertResult doUpsertAndFinalize(String sessionId, String agentRole, 
        String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        return upsertService.doUpsertAndFinalize(sessionId, agentRole, stateJson, finalStatus, steps, originalGoal, contextDataJson);
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        log.infof("[CheckpointAdapter] Saving checkpoint with %d steps for session=%s",
                steps.size(), checkpointDto.sessionId());
        return withRetry(() -> self.doSaveCheckpointWithSteps(checkpointDto, steps), "save-checkpoint-steps");
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public CheckpointDto doSaveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        CheckpointDto saved = doSaveEntity(checkpointDto);
        stepRepo.persistSteps(saved.id(), steps);
        return saved;
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return withRetry(() -> checkpointRepo.findById(id).map(CheckpointMapper::toDto), "find-by-id");
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return withRetry(() -> checkpointRepo.findBySessionAndRole(sessionId, agentRole)
            .stream().findFirst().map(CheckpointMapper::toDto), "find-by-role");
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return withRetry(() -> checkpointRepo.findActiveBySession(sessionId)
            .stream().map(CheckpointMapper::toDto).toList(), "find-active");
    }

    @Override
    public void transitionStatus(Long id, String newStatus) {
        withRetry(() -> { statusService.doTransitionStatus(id, newStatus); return null; }, "transition-status");
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        return withRetry(() -> statusService.doAtomicTransition(id, expectedStatus, newStatus), "atomic-transition");
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Attempting to claim resume for session=%s role=%s", sessionId, agentRole);
        return withRetry(() -> self.doClaimForResume(sessionId, agentRole), "claim-for-resume");
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public boolean doClaimForResume(String sessionId, String agentRole) {
        return statusService.doClaimForResume(sessionId, agentRole, staleClaimMinutes);
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Releasing resume claim for session=%s role=%s", sessionId, agentRole);
        withRetry(() -> { self.doReleaseResumeClaim(sessionId, agentRole); return null; }, "release-resume-claim");
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void doReleaseResumeClaim(String sessionId, String agentRole) {
        statusService.doReleaseResumeClaim(sessionId, agentRole);
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return withRetry(() -> checkpointRepo.findLatestAwaiting(sessionId)
            .stream().findFirst().map(CheckpointMapper::toDto), "find-latest-awaiting");
    }

    @Override
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        log.infof("[CheckpointAdapter] Saving %d steps for checkpoint=%d", steps.size(), checkpointId);
        withRetry(() -> { stepRepo.persistSteps(checkpointId, steps); return null; }, "save-steps");
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return withRetry(() -> stepRepo.loadSteps(checkpointId), "load-steps");
    }

    @Override
    public void deleteSteps(Long checkpointId) {
        log.infof("[CheckpointAdapter] Deleting steps for checkpoint=%d", checkpointId);
        withRetry(() -> { stepRepo.deleteSteps(checkpointId); return null; }, "delete-steps");
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return withRetry(() -> checkpointRepo.findByStatus(status)
            .stream().map(CheckpointMapper::toDto).toList(), "find-by-status");
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        return cleanupService.isCheckpointExpired(sessionId, timeoutHours);
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return withRetry(() -> cleanupService.isSessionActive(sessionId), "is-active");
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return cleanupService.findInProgressZombies(cutoff);
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return cleanupService.findStaleApprovals(cutoff);
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        log.warnf("[CheckpointAdapter] Marking ALL active checkpoints as FAILED for session=%s", sessionId);
        withRetry(() -> { cleanupService.doMarkCheckpointsFailed(sessionId); return null; }, "mark-failed-all");
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        log.infof("[CheckpointAdapter] Deleting checkpoint for session=%s role=%s", sessionId, role);
        withRetry(() -> { cleanupService.doDeleteCheckpoint(sessionId, role); return null; }, "delete-checkpoint");
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        log.infof("[CheckpointAdapter] Purging old chat history before %s", cutoff);
        withRetry(() -> { cleanupService.doPurgeOldChatHistory(cutoff); return null; }, "purge-history");
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    public void markFailed(Long id, String reason, String code, String trace, String root) {
        withRetry(() -> { statusService.doMarkFailed(id, reason, code, trace, root); return null; }, "mark-failed");
    }

    @Override
    public void markCompleted(Long id) {
        withRetry(() -> { statusService.doMarkCompleted(id); return null; }, "mark-completed");
    }

    @Override
    public void markOrphaned(Long id, String reason) {
        withRetry(() -> { statusService.doMarkOrphaned(id, reason); return null; }, "mark-orphaned");
    }

    @Override
    public void archive(Long id) {
        withRetry(() -> { statusService.doArchive(id); return null; }, "archive");
    }

    @Override
    public boolean claimForRetry(Long id) {
        return withRetry(() -> statusService.doClaimForRetry(id), "claim-for-retry");
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant cutoff, String reason) {
        log.infof("[CheckpointAdapter] Recovering zombie checkpoints before %s", cutoff);
        return withRetry(() -> cleanupService.doRecoverInProgressZombies(cutoff, reason), "recover-zombies");
    }

    @Override
    public boolean atomicUpdateBatchToolDecision(String sid, String role, String tool, String decision) {
        return withRetry(() -> batchOps.doAtomicUpdateBatchToolDecision(sid, role, tool, decision), "atomic-update-batch-decision");
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return withRetry(() -> checkpointRepo.findLatestAwaitingBySegment(segment)
            .stream().findFirst().map(CheckpointMapper::toDto), "find-latest-segment");
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return withRetry(() -> checkpointRepo.findLatestBySession(sessionId)
            .stream().findFirst().map(CheckpointMapper::toDto), "find-latest");
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return withRetry(() -> checkpointRepo.findLatestNonArchived(sessionId)
            .stream().findFirst().map(CheckpointMapper::toDto), "find-latest-non-archived");
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return withRetry(() -> checkpointRepo.findLatestBySessionAndStatus(sessionId, status)
            .stream().findFirst().map(CheckpointMapper::toDto), "find-latest-status");
    }

    @Override
    public boolean hasPendingApprovalForChat(String chatId) {
        return withRetry(() -> checkpointRepo.hasPendingApprovalForChat(chatId), "has-pending-approval-for-chat");
    }

    private <T> T withRetry(Callable<T> task, String operation) {
        try {
            return retryTemplate.execute(operation, 5, 50, task);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException("Adapter failed: " + operation, e);
        }
    }
}

