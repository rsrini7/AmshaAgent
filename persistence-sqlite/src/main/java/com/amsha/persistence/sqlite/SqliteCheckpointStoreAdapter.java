package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.common.util.TemporalUtils;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * SQLite implementation of {@link CheckpointStorePort} backed by JDBI.
 * Replaces the JPA adapter with direct JDBC via JDBI DAO.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
@SuppressWarnings("PMD.ExcessiveClassLength") // Data access adapter — many store methods can't be split
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
            @ConfigProperty(name = "amsha.checkpoint.stale-claim-minutes", defaultValue = "2") int staleClaimMinutes) {
        this.jdbi = jdbi;
        this.retryWrapper = retryWrapper;
        this.objectMapper = objectMapper;
        this.staleClaimMinutes = staleClaimMinutes;
    }

    protected SqliteCheckpointStoreAdapter() {
        this(null, null, null, 2);
    }

    @Override
    public CheckpointDto save(CheckpointDto checkpointDto) {
        log.infof(
                "[CheckpointAdapter] Saving checkpoint session=%s role=%s",
                checkpointDto.sessionId(), checkpointDto.agentRole());
        return retryWrapper.withRetry(
                "save-entity",
                5,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    // Load existing to handle AWAITING_APPROVAL batch decision merge
                    Optional<CheckpointDto> existing = checkpointDto.id() != null
                            ? dao.findById(checkpointDto.id())
                            : dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole());
                    CheckpointDto toSave;
                    if (existing.isPresent()
                            && CheckpointStatus.AWAITING_APPROVAL.name().equals(checkpointDto.status())
                            && CheckpointStatus.AWAITING_APPROVAL
                                    .name()
                                    .equals(existing.get().status())) {
                        toSave =
                                SqliteCheckpointHelper.mergeBatchDecisions(existing.get(), checkpointDto, objectMapper);
                    } else {
                        toSave = checkpointDto;
                    }
                    toSave = toSave.ensureTimestamps(
                            existing.map(CheckpointDto::createdAt).orElseGet(Instant::now), Instant.now());
                    dao.upsertCheckpoint(toSave);
                    return dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole())
                            .orElseThrow(() -> new RuntimeException("Checkpoint not found after upsert"));
                }));
    }

    /**
     * Upserts checkpoint state only — does NOT persist reasoning steps despite
     * the legacy method name. Steps are persisted by {@link #upsertAndFinalize}
     * or {@link #saveCheckpointWithSteps}.
     */
    @Override
    public CheckpointUpsertResult upsertWithSteps(
            String sessionId,
            String agentRole,
            String stateJson,
            String autonomy,
            int iterationsUsed,
            String originalGoal,
            String contextDataJson) {
        log.infof("[CheckpointAdapter] Upserting checkpoint with steps for session=%s", sessionId);
        return retryWrapper.withRetry(
                "upsert-steps",
                5,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    Optional<CheckpointDto> existing = dao.findAnyBySessionAndRole(sessionId, agentRole);
                    boolean created = existing.isEmpty();
                    String status = existing.map(CheckpointDto::status).orElse(CheckpointStatus.IN_PROGRESS.name());
                    // Preserve AWAITING_APPROVAL / RESUMING if already set
                    if (!CheckpointStatus.AWAITING_APPROVAL.name().equals(status)
                            && !CheckpointStatus.RESUMING.name().equals(status)) {
                        status = CheckpointStatus.IN_PROGRESS.name();
                    }
                    CheckpointDto dto = CheckpointDtoHelper.create(
                            sessionId,
                            agentRole,
                            status,
                            autonomy,
                            stateJson,
                            originalGoal,
                            iterationsUsed,
                            contextDataJson);
                    dao.upsertCheckpoint(dto);
                    CheckpointDto saved =
                            dao.findAnyBySessionAndRole(sessionId, agentRole).orElseThrow();
                    return new CheckpointUpsertResult(saved, created);
                }));
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
        log.infof(
                "[CheckpointAdapter] Upserting and finalizing checkpoint for session=%s (status=%s)",
                sessionId, finalStatus);
        return retryWrapper.withRetry(
                "upsert-and-finalize",
                5,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    CheckpointDto existing =
                            dao.findAnyBySessionAndRole(sessionId, agentRole).orElse(null);
                    String autonomy = existing != null ? existing.autonomyLevel() : null;
                    int iters = existing != null ? existing.iterationsUsed() : 0;
                    CheckpointDto dto = CheckpointDtoHelper.create(
                            sessionId,
                            agentRole,
                            finalStatus,
                            autonomy,
                            stateJson,
                            originalGoal,
                            iters,
                            contextDataJson);
                    dao.upsertCheckpoint(dto);
                    CheckpointDto saved =
                            dao.findAnyBySessionAndRole(sessionId, agentRole).orElseThrow();
                    dao.deleteSteps(saved.id());
                    if (steps != null && !steps.isEmpty()) {
                        SqliteCheckpointHelper.insertSteps(handle, saved.id(), steps);
                    }
                    return new CheckpointUpsertResult(saved, true);
                }));
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        log.infof(
                "[CheckpointAdapter] Saving checkpoint with %d steps for session=%s",
                steps.size(), checkpointDto.sessionId());
        return retryWrapper.withRetry(
                "save-checkpoint-steps",
                5,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    Optional<CheckpointDto> existing = checkpointDto.id() != null
                            ? dao.findById(checkpointDto.id())
                            : dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole());
                    CheckpointDto toSave = checkpointDto.ensureTimestamps(
                            existing.map(CheckpointDto::createdAt).orElseGet(Instant::now), Instant.now());
                    dao.upsertCheckpoint(toSave);
                    CheckpointDto saved = dao.findAnyBySessionAndRole(
                                    checkpointDto.sessionId(), checkpointDto.agentRole())
                            .orElseThrow();
                    if (steps != null && !steps.isEmpty()) {
                        dao.deleteSteps(saved.id());
                        SqliteCheckpointHelper.insertSteps(handle, saved.id(), steps);
                    }
                    return saved;
                }));
    }

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
    public List<CheckpointDto> findActiveBySessionTree(String rootSessionId) {
        if (rootSessionId == null) return List.of();
        String prefixPattern = rootSessionId + ":delegate:%";
        return jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.findActiveBySessionTree(rootSessionId, prefixPattern));
    }

    @Override
    public List<CheckpointDto> findByStatusAndSessionIds(
            String status, List<String> sessionIds, int limit, int offset) {
        return jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.findByStatusAndSessionIds(status, sessionIds, limit, offset));
    }

    @Override
    public long countByStatusAndSessionIds(String status, List<String> sessionIds) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.countByStatusAndSessionIds(status, sessionIds));
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

    @Override
    public void transitionStatus(Long id, String newStatus) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.transitionStatus(id, newStatus, now));
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        return jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.atomicTransition(id, newStatus, now, expectedStatus));
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Attempting to claim resume for session=%s role=%s", sessionId, agentRole);
        String now = TemporalUtils.formatIsoLex(Instant.now());
        String cutoff = TemporalUtils.formatIsoLex(Instant.now().minus(staleClaimMinutes, ChronoUnit.MINUTES));
        return jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.claimForResume(sessionId, agentRole, now, cutoff));
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
        retryWrapper.withRetryVoid(
                "save-steps",
                3,
                50,
                () -> jdbi.useTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    dao.deleteSteps(checkpointId);
                    if (steps != null && !steps.isEmpty()) {
                        SqliteCheckpointHelper.insertSteps(handle, checkpointId, steps);
                    }
                }));
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
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        log.infof("[BatchOps] Updating tool decision for session=%s tool=%s -> %s", sessionId, toolName, decision);
        return retryWrapper.withRetry(
                "atomic-update-batch",
                3,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    // Load current batch_tool_decisions and status
                    Optional<CheckpointDto> cp = dao.findAnyBySessionAndRole(sessionId, agentRole);
                    if (cp.isEmpty()) {
                        log.warnf("[BatchOps] No checkpoint found for session=%s", sessionId);
                        return false;
                    }
                    CheckpointDto dto = cp.get();
                    if (!CheckpointStatus.AWAITING_APPROVAL.name().equals(dto.status())) {
                        log.warnf(
                                "[BatchOps] Checkpoint status is '%s' instead of AWAITING_APPROVAL for session=%s",
                                dto.status(), sessionId);
                        return false;
                    }
                    String rawJson = dto.batchToolDecisions();
                    if (rawJson == null || rawJson.isBlank()) {
                        log.warnf("[BatchOps] batchToolDecisions is null for session=%s", sessionId);
                        return false;
                    }
                    try {
                        Map<String, ToolDecision> map =
                                objectMapper.readValue(rawJson, new TypeReference<Map<String, ToolDecision>>() {});
                        if (!map.containsKey(toolName)) {
                            log.warnf(
                                    "[BatchOps] Tool '%s' not in batch for session %s. Available keys: %s",
                                    toolName, sessionId, map.keySet());
                            return false;
                        }
                        if (map.get(toolName) != ToolDecision.PENDING) {
                            log.infof("[BatchOps] Tool '%s' is already decided for session=%s", toolName, sessionId);
                            return false;
                        }
                        map.put(toolName, ToolDecision.valueOf(decision));
                        String updatedJson = objectMapper.writeValueAsString(map);
                        String now = TemporalUtils.formatIsoLex(Instant.now());
                        return dao.atomicUpdateBatchDecision(sessionId, agentRole, updatedJson, rawJson, now);
                    } catch (Exception e) {
                        log.errorf(
                                "[BatchOps] Failed to update batch tool decision for session=%s tool=%s: %s",
                                sessionId, toolName, LogUtil.rootMessage(e));
                        return false;
                    }
                }));
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
        return jdbi.withExtension(
                CheckpointJdbiDao.class,
                dao -> !dao.findExpiredCheckpoints(sessionId, cutoff).isEmpty());
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return jdbi.withExtension(
                CheckpointJdbiDao.class,
                dao -> !dao.findActiveBySessionAny(sessionId).isEmpty());
    }

    @Override
    public void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(
                CheckpointJdbiDao.class,
                dao -> dao.stampChannelInfo(checkpointId, channelType, channelPlatformId, now));
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant cutoff, String reason) {
        log.infof("[CheckpointAdapter] Recovering zombie checkpoints before %s", cutoff);
        String cutoffStr = TemporalUtils.formatIsoLex(cutoff);
        return retryWrapper.withRetry(
                "recover-zombies",
                3,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(CheckpointJdbiDao.class);
                    List<CheckpointDto> zombies = dao.findInProgressZombies(cutoffStr);
                    if (!zombies.isEmpty()) {
                        String now = TemporalUtils.formatIsoLex(Instant.now());
                        dao.recoverInProgressZombies(cutoffStr, reason, now);
                    }
                    return zombies;
                }));
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(
                CheckpointJdbiDao.class, dao -> dao.markCheckpointsFailed(sessionId, "Session cancelled", now));
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
}
