package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of CheckpointStorePort backed by JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
public class PostgresCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(PostgresCheckpointStoreAdapter.class);

    private final Jdbi jdbi;
    private final ObjectMapper objectMapper;
    private final int staleClaimMinutes;

    @Inject
    public PostgresCheckpointStoreAdapter(
            Jdbi jdbi,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.checkpoint.stale-claim-minutes", defaultValue = "2") int staleClaimMinutes) {
        this.jdbi = jdbi;
        this.objectMapper = objectMapper;
        this.staleClaimMinutes = staleClaimMinutes;
    }

    protected PostgresCheckpointStoreAdapter() {
        this(null, null, 2);
    }

    @Override
    public CheckpointDto save(CheckpointDto checkpointDto) {
        log.infof(
                "[CheckpointAdapter] Saving checkpoint session=%s role=%s",
                checkpointDto.sessionId(), checkpointDto.agentRole());
        return jdbi.inTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            Optional<CheckpointDto> existing = checkpointDto.id() != null
                    ? dao.findById(checkpointDto.id())
                    : dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole());
            CheckpointDto toSave;
            if (existing.isPresent()
                    && CheckpointStatus.AWAITING_APPROVAL.name().equals(checkpointDto.status())
                    && CheckpointStatus.AWAITING_APPROVAL
                            .name()
                            .equals(existing.get().status())) {
                toSave = PostgresCheckpointHelper.mergeBatchDecisions(existing.get(), checkpointDto, objectMapper);
            } else {
                toSave = checkpointDto;
            }
            toSave = toSave.ensureTimestamps(
                    existing.map(CheckpointDto::createdAt).orElseGet(Instant::now), Instant.now());
            dao.upsertCheckpoint(toSave);
            return dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole())
                    .orElseThrow(() -> new RuntimeException("Checkpoint not found after upsert"));
        });
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
        log.infof("[CheckpointAdapter] Upserting checkpoint state only for session=%s", sessionId);
        return jdbi.inTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            Optional<CheckpointDto> existing = dao.findAnyBySessionAndRole(sessionId, agentRole);
            boolean created = existing.isEmpty();
            Instant now = Instant.now();
            String status = existing.map(CheckpointDto::status).orElse(CheckpointStatus.IN_PROGRESS.name());
            if (!CheckpointStatus.AWAITING_APPROVAL.name().equals(status)
                    && !CheckpointStatus.RESUMING.name().equals(status)) {
                status = CheckpointStatus.IN_PROGRESS.name();
            }
            CheckpointDto dto = new CheckpointDto(
                    null,
                    sessionId,
                    agentRole,
                    status,
                    autonomy,
                    stateJson,
                    null,
                    null,
                    originalGoal,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    iterationsUsed,
                    null,
                    null,
                    contextDataJson,
                    null,
                    null,
                    null,
                    null,
                    0,
                    now,
                    now);
            dao.upsertCheckpoint(dto);
            CheckpointDto saved =
                    dao.findAnyBySessionAndRole(sessionId, agentRole).orElseThrow();
            return new CheckpointUpsertResult(saved, created);
        });
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
        return jdbi.inTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            Instant now = Instant.now();
            Optional<CheckpointDto> existing = dao.findAnyBySessionAndRole(sessionId, agentRole);
            String autonomy = existing.map(CheckpointDto::autonomyLevel).orElse(null);
            int iterationsUsed = existing.map(CheckpointDto::iterationsUsed).orElse(0);
            CheckpointDto dto = new CheckpointDto(
                    null,
                    sessionId,
                    agentRole,
                    finalStatus,
                    autonomy,
                    stateJson,
                    null,
                    null,
                    originalGoal,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    iterationsUsed,
                    null,
                    null,
                    contextDataJson,
                    null,
                    null,
                    null,
                    null,
                    0,
                    now,
                    now);
            dao.upsertCheckpoint(dto);
            CheckpointDto saved =
                    dao.findAnyBySessionAndRole(sessionId, agentRole).orElseThrow();
            dao.deleteSteps(saved.id());
            if (steps != null && !steps.isEmpty()) {
                PostgresCheckpointHelper.insertSteps(handle, saved.id(), steps);
            }
            return new CheckpointUpsertResult(saved, true);
        });
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        log.infof(
                "[CheckpointAdapter] Saving checkpoint with %d steps for session=%s",
                steps.size(), checkpointDto.sessionId());
        return jdbi.inTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            Optional<CheckpointDto> existing = checkpointDto.id() != null
                    ? dao.findById(checkpointDto.id())
                    : dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole());
            CheckpointDto toSave = checkpointDto.ensureTimestamps(
                    existing.map(CheckpointDto::createdAt).orElseGet(Instant::now), Instant.now());
            dao.upsertCheckpoint(toSave);
            CheckpointDto saved = dao.findAnyBySessionAndRole(checkpointDto.sessionId(), checkpointDto.agentRole())
                    .orElseThrow();
            if (steps != null && !steps.isEmpty()) {
                dao.deleteSteps(saved.id());
                PostgresCheckpointHelper.insertSteps(handle, saved.id(), steps);
            }
            return saved;
        });
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
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.transitionStatus(id, newStatus, Instant.now()));
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        return jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.atomicTransition(id, newStatus, Instant.now(), expectedStatus));
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Attempting to claim resume for session=%s role=%s", sessionId, agentRole);
        Instant now = Instant.now();
        Instant cutoff = now.minus(staleClaimMinutes, ChronoUnit.MINUTES);
        return jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.claimForResume(sessionId, agentRole, now, cutoff));
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        log.infof("[CheckpointAdapter] Releasing resume claim for session=%s role=%s", sessionId, agentRole);
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.releaseResumeClaim(sessionId, agentRole, Instant.now()));
    }

    @Override
    public void markFailed(Long id, String reason, String code, String trace, String root) {
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.markFailed(id, reason, code, trace, root, Instant.now()));
    }

    @Override
    public void markCompleted(Long id) {
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.markCompleted(id, Instant.now()));
    }

    @Override
    public void markOrphaned(Long id, String reason) {
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.markOrphaned(id, reason, Instant.now()));
    }

    @Override
    public void archive(Long id) {
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.archive(id, Instant.now()));
    }

    @Override
    public boolean claimForRetry(Long id) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.claimForRetry(id, Instant.now()));
    }

    @Override
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        log.infof("[CheckpointAdapter] Saving %d steps for checkpoint=%d", steps.size(), checkpointId);
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            dao.deleteSteps(checkpointId);
            if (steps != null && !steps.isEmpty()) {
                PostgresCheckpointHelper.insertSteps(handle, checkpointId, steps);
            }
        });
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

    @Override
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        log.infof("[BatchOps] Updating tool decision for session=%s tool=%s -> %s", sessionId, toolName, decision);
        return jdbi.inTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
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
                    log.warnf("[BatchOps] Tool '%s' not in batch for session %s.", toolName, sessionId);
                    return false;
                }
                if (map.get(toolName) != ToolDecision.PENDING) {
                    log.infof("[BatchOps] Tool '%s' is already decided for session=%s", toolName, sessionId);
                    return false;
                }
                map.put(toolName, ToolDecision.valueOf(decision));
                String updatedJson = objectMapper.writeValueAsString(map);
                return dao.atomicUpdateBatchDecision(sessionId, agentRole, updatedJson, rawJson, Instant.now());
            } catch (Exception e) {
                log.errorf("[BatchOps] Failed to update batch tool decision: %s", LogUtil.rootMessage(e));
                return false;
            }
        });
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findInProgressZombies(cutoff));
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return jdbi.withExtension(CheckpointJdbiDao.class, dao -> dao.findStaleApprovals(cutoff));
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
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
        jdbi.useExtension(
                CheckpointJdbiDao.class,
                dao -> dao.stampChannelInfo(checkpointId, channelType, channelPlatformId, Instant.now()));
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant cutoff, String reason) {
        log.infof("[CheckpointAdapter] Recovering zombie checkpoints before %s", cutoff);
        return jdbi.inTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            List<CheckpointDto> zombies = dao.findInProgressZombies(cutoff);
            if (!zombies.isEmpty()) {
                dao.recoverInProgressZombies(cutoff, reason, Instant.now());
            }
            return zombies;
        });
    }

    @Override
    public void markCheckpointsFailed(String sessionId) {
        jdbi.useExtension(
                CheckpointJdbiDao.class,
                dao -> dao.markCheckpointsFailed(sessionId, "Session cancelled", Instant.now()));
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        log.infof("[CheckpointAdapter] Deleting checkpoint for session=%s role=%s", sessionId, role);
        jdbi.useExtension(CheckpointJdbiDao.class, dao -> dao.deleteCheckpoint(sessionId, role));
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        log.infof("[CheckpointAdapter] Purging old chat history before %s", cutoff);
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CheckpointJdbiDao.class);
            dao.deleteReasoningStepsForPurge(cutoff);
            dao.deleteCheckpointsForPurge(cutoff);
        });
    }
}
