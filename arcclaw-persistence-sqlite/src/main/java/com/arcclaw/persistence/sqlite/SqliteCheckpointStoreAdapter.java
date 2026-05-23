package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.TemporalUtils;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * SQLite implementation of {@link CheckpointStorePort} backed by JDBI.
 * Replaces the JPA/Hibernate adapter with direct JDBC via JDBI DAO.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(SqliteCheckpointStoreAdapter.class);

    private final Jdbi jdbi;
    private final JdbiRetryWrapper retryWrapper;
    private final ObjectMapper objectMapper;
    private final int staleClaimMinutes;

    @Inject
    public SqliteCheckpointStoreAdapter(
            Jdbi jdbi,
            JdbiRetryWrapper retryWrapper,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "arcclaw.checkpoint.stale-claim-minutes", defaultValue = "2") int staleClaimMinutes) {
        this.jdbi = jdbi;
        this.retryWrapper = retryWrapper;
        this.objectMapper = objectMapper;
        this.staleClaimMinutes = staleClaimMinutes;
    }

    protected SqliteCheckpointStoreAdapter() {
        this(null, null, null, 2);
    }

    // ── Core Save ──────────────────────────────────────────────────────────

    @Override
    public CheckpointDto save(CheckpointDto checkpointDto) {
        log.infof("[CheckpointAdapter] Saving checkpoint session=%s role=%s",
                checkpointDto.sessionId(), checkpointDto.agentRole());
        return retryWrapper.withRetry("save-entity", 5, 50, () ->
            jdbi.inTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                // Load existing to handle AWAITING_APPROVAL batch decision merge
                Optional<CheckpointDto> existing = checkpointDto.id() != null
                    ? dao.findById(checkpointDto.id())
                    : dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole());
                if (existing.isPresent() && "AWAITING_APPROVAL".equals(checkpointDto.status())
                        && "AWAITING_APPROVAL".equals(existing.get().status())) {
                    CheckpointDto merged = mergeBatchDecisions(existing.get(), checkpointDto);
                    dao.upsertCheckpoint(merged);
                } else {
                    dao.upsertCheckpoint(checkpointDto);
                }
                return dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole())
                    .orElseThrow(() -> new RuntimeException("Checkpoint not found after upsert"));
            })
        );
    }

    // ── Upsert with Steps ──────────────────────────────────────────────────

    @Override
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole,
            String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) {
        log.infof("[CheckpointAdapter] Upserting checkpoint with steps for session=%s", sessionId);
        return retryWrapper.withRetry("upsert-steps", 5, 50, () ->
            jdbi.inTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                Optional<CheckpointDto> existing = dao.findAnyBySessionAndRole(sessionId, agentRole);
                boolean created = existing.isEmpty();
                Instant now = Instant.now();
                String status = existing.map(CheckpointDto::status).orElse("IN_PROGRESS");
                // Preserve AWAITING_APPROVAL / RESUMING if already set
                if (!"AWAITING_APPROVAL".equals(status) && !"RESUMING".equals(status)) {
                    status = "IN_PROGRESS";
                }
                CheckpointDto dto = new CheckpointDto(
                    null, sessionId, agentRole, status, autonomy, stateJson,
                    null, null, originalGoal, null, null, null, null, null, null, null,
                    null, iterationsUsed, null, null, contextDataJson, null, null, null,
                    null, 0, now, now
                );
                dao.upsertCheckpoint(dto);
                CheckpointDto saved = dao.findAnyBySessionAndRole(sessionId, agentRole).orElseThrow();
                return new CheckpointUpsertResult(saved, created);
            })
        );
    }

    @Override
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole,
            String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        log.infof("[CheckpointAdapter] Upserting and finalizing checkpoint for session=%s (status=%s)", sessionId, finalStatus);
        return retryWrapper.withRetry("upsert-and-finalize", 5, 50, () ->
            jdbi.inTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                Instant now = Instant.now();
                CheckpointDto dto = new CheckpointDto(
                    null, sessionId, agentRole, finalStatus, null, stateJson,
                    null, null, originalGoal, null, null, null, null, null, null, null,
                    null, 0, null, null, contextDataJson, null, null, null,
                    null, 0, now, now
                );
                dao.upsertCheckpoint(dto);
                CheckpointDto saved = dao.findAnyBySessionAndRole(sessionId, agentRole).orElseThrow();
                // Replace steps
                dao.deleteSteps(saved.id());
                if (steps != null && !steps.isEmpty()) {
                    insertSteps(handle, saved.id(), steps);
                }
                return new CheckpointUpsertResult(saved, true);
            })
        );
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        log.infof("[CheckpointAdapter] Saving checkpoint with %d steps for session=%s",
                steps.size(), checkpointDto.sessionId());
        return retryWrapper.withRetry("save-checkpoint-steps", 5, 50, () ->
            jdbi.inTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                dao.upsertCheckpoint(checkpointDto);
                CheckpointDto saved = dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole())
                    .orElseThrow();
                if (steps != null && !steps.isEmpty()) {
                    dao.deleteSteps(saved.id());
                    insertSteps(handle, saved.id(), steps);
                }
                return saved;
            })
        );
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findById(id));
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findBySessionAndRole(sessionId, agentRole));
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findActiveBySession(sessionId));
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findByStatus(status));
    }

    @Override
    public List<CheckpointDto> findAllActive() {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findAllActive());
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findLatestAwaiting(sessionId));
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findLatestAwaitingBySegment(segment));
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findLatestBySession(sessionId));
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findLatestNonArchived(sessionId));
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findLatestBySessionAndStatus(sessionId, status));
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    public boolean hasPendingApprovalForChat(String chatId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.hasPendingApprovalForChat(chatId));
    }

    // ── Status Transitions ─────────────────────────────────────────────────

    @Override
    public void transitionStatus(Long id, String newStatus) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.transitionStatus(id, newStatus, now));
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.atomicTransition(id, newStatus, now, expectedStatus));
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Attempting to claim resume for session=%s role=%s", sessionId, agentRole);
        String now = TemporalUtils.formatIsoLex(Instant.now());
        String cutoff = TemporalUtils.formatIsoLex(Instant.now().minus(staleClaimMinutes, ChronoUnit.MINUTES));
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.claimForResume(sessionId, agentRole, now, cutoff));
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Releasing resume claim for session=%s role=%s", sessionId, agentRole);
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.releaseResumeClaim(sessionId, agentRole, now));
    }

    @Override
    public void markFailed(Long id, String reason, String code, String trace, String root) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.markFailed(id, reason, code, trace, root, now));
    }

    @Override
    public void markCompleted(Long id) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.markCompleted(id, now));
    }

    @Override
    public void markOrphaned(Long id, String reason) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.markOrphaned(id, reason, now));
    }

    @Override
    public void archive(Long id) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.archive(id, now));
    }

    @Override
    public boolean claimForRetry(Long id) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.claimForRetry(id, now));
    }

    // ── Steps ──────────────────────────────────────────────────────────────

    @Override
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        log.infof("[CheckpointAdapter] Saving %d steps for checkpoint=%d", steps.size(), checkpointId);
        retryWrapper.withRetryVoid("save-steps", 3, 50, () ->
            jdbi.useTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                dao.deleteSteps(checkpointId);
                if (steps != null && !steps.isEmpty()) {
                    insertSteps(handle, checkpointId, steps);
                }
            })
        );
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.loadSteps(checkpointId));
    }

    @Override
    public void deleteSteps(Long checkpointId) {
        log.infof("[CheckpointAdapter] Deleting steps for checkpoint=%d", checkpointId);
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.deleteSteps(checkpointId));
    }

    // ── Batch Operations ───────────────────────────────────────────────────

    @Override
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole,
            String toolName, String decision) {
        log.infof("[BatchOps] Updating tool decision for session=%s tool=%s -> %s",
                sessionId, toolName, decision);
        return retryWrapper.withRetry("atomic-update-batch", 3, 50, () ->
            jdbi.inTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                // Load current batch_tool_decisions and status
                Optional<CheckpointDto> cp = dao.findAnyBySessionAndRole(sessionId, agentRole);
                if (cp.isEmpty()) {
                    log.warnf("[BatchOps] No checkpoint found for session=%s", sessionId);
                    return false;
                }
                CheckpointDto dto = cp.get();
                if (!"AWAITING_APPROVAL".equals(dto.status())) {
                    log.warnf("[BatchOps] Checkpoint status is '%s' instead of AWAITING_APPROVAL for session=%s",
                            dto.status(), sessionId);
                    return false;
                }
                String rawJson = dto.batchToolDecisions();
                if (rawJson == null) {
                    log.warnf("[BatchOps] batchToolDecisions is null for session=%s", sessionId);
                    return false;
                }
                try {
                    Map<String, ToolDecision> map = objectMapper.readValue(
                        rawJson, new TypeReference<Map<String, ToolDecision>>() {});
                    if (!map.containsKey(toolName)) {
                        log.warnf("[BatchOps] Tool '%s' not in batch for session %s. Available keys: %s",
                                toolName, sessionId, map.keySet());
                        return false;
                    }
                    map.put(toolName, ToolDecision.valueOf(decision));
                    String updatedJson = objectMapper.writeValueAsString(map);
                    String now = TemporalUtils.formatIsoLex(Instant.now());
                    return dao.atomicUpdateBatchDecision(sessionId, agentRole, updatedJson, now);
                } catch (Exception e) {
                    log.errorf("[BatchOps] Failed to update batch tool decision for session=%s tool=%s: %s",
                            sessionId, toolName, LogUtil.rootMessage(e));
                    return false;
                }
            })
        );
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        String cutoffStr = TemporalUtils.formatIsoLex(cutoff);
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findInProgressZombies(cutoffStr));
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        String cutoffStr = TemporalUtils.formatIsoLex(cutoff);
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findStaleApprovals(cutoffStr));
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        String cutoff = TemporalUtils.formatIsoLex(Instant.now().minus(timeoutHours, ChronoUnit.HOURS));
        return jdbi.withExtension(CheckpointJdbiDao.class, dao ->
            !dao.findExpiredCheckpoints(sessionId, cutoff).isEmpty());
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao ->
            !dao.findActiveBySessionAny(sessionId).isEmpty());
    }

    @Override
    public void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao ->
            dao.stampChannelInfo(checkpointId, channelType, channelPlatformId, now));
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant cutoff, String reason) {
        log.infof("[CheckpointAdapter] Recovering zombie checkpoints before %s", cutoff);
        String cutoffStr = TemporalUtils.formatIsoLex(cutoff);
        return retryWrapper.withRetry("recover-zombies", 3, 50, () ->
            jdbi.inTransaction(handle -> {
                var dao = handle.attach(CheckpointJdbiDao.class);
                List<CheckpointDto> zombies = dao.findInProgressZombies(cutoffStr);
                if (!zombies.isEmpty()) {
                    String now = TemporalUtils.formatIsoLex(Instant.now());
                    dao.recoverInProgressZombies(cutoffStr, reason, now);
                }
                return zombies;
            })
        );
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao ->
            dao.markCheckpointsFailed(sessionId, "Session cancelled", now));
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        log.infof("[CheckpointAdapter] Deleting checkpoint for session=%s role=%s", sessionId, role);
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.deleteCheckpoint(sessionId, role));
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        log.infof("[CheckpointAdapter] Purging old chat history before %s", cutoff);
        String cutoffStr = TemporalUtils.formatIsoLex(cutoff);
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            dao.deleteReasoningStepsForPurge(cutoffStr);
            dao.deleteCheckpointsForPurge(cutoffStr);
        });
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    private void insertSteps(Handle handle, Long checkpointId, List<ReasoningStepDto> steps) {
        var batch = handle.prepareBatch(
            "INSERT INTO reasoning_step (checkpoint_id, sequence_num, thoughtJson, action, actionInputJson, observation, input_tokens, output_tokens, success, error_code, trace_id, root_cause_class) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        for (int i = 0; i < steps.size(); i++) {
            ReasoningStepDto s = steps.get(i);
            batch.add(checkpointId, i,
                s.thoughtJson(), s.action(), s.actionInputJson(), s.observation(),
                s.inputTokens(), s.outputTokens(), s.success(),
                s.errorCode(), s.traceId(), s.rootCauseClass());
        }
        batch.execute();
    }

    /**
     * Merges batch decisions during AWAITING_APPROVAL to prevent overwriting
     * decisions made in a concurrent update turn (A16 stale join guard).
     */
    private CheckpointDto mergeBatchDecisions(CheckpointDto managed, CheckpointDto incoming) {
        if (incoming.batchToolDecisions() == null) return incoming;
        if (managed.batchToolDecisions() == null) return incoming;
        try {
            Map<String, ToolDecision> managedMap = objectMapper.readValue(
                managed.batchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            Map<String, ToolDecision> incomingMap = objectMapper.readValue(
                incoming.batchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            boolean changed = false;
            for (Map.Entry<String, ToolDecision> entry : managedMap.entrySet()) {
                String tool = entry.getKey();
                ToolDecision managedDecision = entry.getValue();
                ToolDecision incomingDecision = incomingMap.get(tool);
                if (ToolDecision.PENDING != managedDecision
                        && ToolDecision.PENDING == incomingDecision) {
                    incomingMap.put(tool, managedDecision);
                    changed = true;
                }
            }
            if (changed) {
                try {
                    String mergedJson = objectMapper.writeValueAsString(incomingMap);
                    return new CheckpointDto(
                        incoming.id(), incoming.sessionId(), incoming.agentRole(),
                        incoming.status(), incoming.autonomyLevel(), incoming.stateJson(),
                        incoming.channelPlatformId(), incoming.channelType(),
                        incoming.originalGoal(), incoming.failedAt(), incoming.completedAt(),
                        incoming.failReason(), incoming.pendingToolName(), mergedJson,
                        incoming.pendingToolParametersJson(), incoming.preExecutedResults(),
                        incoming.maxIterations(), incoming.iterationsUsed(),
                        incoming.retryAfterMs(), incoming.systemNote(),
                        incoming.contextDataJson(), incoming.errorCode(),
                        incoming.traceId(), incoming.rootCauseClass(),
                        incoming.parentCheckpointId(), incoming.depth(),
                        incoming.createdAt(), incoming.updatedAt());
                } catch (Exception e) {
                    log.warnf("Failed to serialize merged decisions: %s", LogUtil.rootMessage(e));
                }
            }
        } catch (Exception e) {
            log.warnf("Failed to merge batch decisions for session %s: %s",
                    managed.sessionId(), LogUtil.rootMessage(e));
        }
        return incoming;
    }
}
