package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.ReasoningStepEntity;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.TemporalUtils;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * SQLite adapter implementation of {@link CheckpointStorePort}.
 * 
 * Encapsulates all SQLite-specific concerns including:
 * - REQUIRES_NEW transaction boundaries for write operations
 * - Retry logic wrapping via {@link SqliteRetryTemplate}
 * - em.clear() patterns for fresh DB reads after BUSY retries
 * - Instant formatting for SQLite TEXT timestamp comparisons
 * 
 * Architecture note (§5.14.1): Retry-on-BUSY logic is intentionally encapsulated
 * inside this adapter. It does NOT leak via a PersistenceRetryPort interface.
 * Postgres adapters will handle retry differently or not at all.
 */
@ApplicationScoped
public class SqliteCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(SqliteCheckpointStoreAdapter.class);

    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;
    @Inject SqliteRetryTemplate retryTemplate;
    @Inject SqliteCheckpointStoreAdapter self; // CDI proxy for @Transactional(REQUIRES_NEW)

    @Override
    public CheckpointDto save(CheckpointDto checkpointDto) {
        return withRetry(() -> self.doSaveEntity(checkpointDto), "save-entity");
    }

    @Override
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, 
        String stateJson, String autonomy, int iterationsUsed) {
        return withRetry(() -> self.doUpsertWithSteps(sessionId, agentRole, stateJson, autonomy, iterationsUsed), "upsert-with-steps");
    }

    /**
     * Atomically finds or creates a checkpoint and updates its metadata.
     * Restores TOCTOU safety (§FIX 1).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointUpsertResult doUpsertWithSteps(String sessionId, String agentRole, 
        String stateJson, String autonomy, int iterationsUsed) {
        em.clear();
        List<AgentCheckpoint> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.agentRole = :role AND c.status <> :archived " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList();
        
        AgentCheckpoint entity;
        boolean created = false;
        if (results.isEmpty()) {
            entity = new AgentCheckpoint();
            entity.setSessionId(sessionId);
            entity.setAgentRole(agentRole);
            created = true;
        } else {
            entity = results.get(0);
        }
        
        entity.setStatus(CheckpointStatus.IN_PROGRESS);
        entity.setStateJson(stateJson);
        if (autonomy != null) {
            entity.setAutonomyLevel(AutonomyLevel.valueOf(autonomy));
        }
        entity.setIterationsUsed(iterationsUsed);
        
        if (created) {
            em.persist(entity);
        } else {
            entity = em.merge(entity);
        }
        
        return new CheckpointUpsertResult(CheckpointMapper.toDto(entity), created);
    }

    /**
     * Saves or updates a checkpoint in a fresh, isolated transaction.
     * Handles both inserts and updates; clears L1 cache before querying existing.
     * 
     * Caller must NOT be inside a REQUIRED transaction.
     * 
     * NOTE: When called from doSaveCheckpointWithSteps(), the @Transactional annotation
     * on this method is bypassed (direct call, not CDI proxy). This is intentional.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointDto doSaveEntity(CheckpointDto checkpointDto) {
        em.clear();
        
        if (checkpointDto.id() == null) {
            AgentCheckpoint entity = CheckpointMapper.toEntity(checkpointDto);
            em.persist(entity);
            return CheckpointMapper.toDto(entity);
        } else {
            AgentCheckpoint managed = em.find(AgentCheckpoint.class, checkpointDto.id());
            if (managed == null) {
                AgentCheckpoint newEntity = CheckpointMapper.toEntity(checkpointDto);
                em.persist(newEntity);
                return CheckpointMapper.toDto(newEntity);
            }
            updateEntityFromDto(managed, checkpointDto);
            return CheckpointMapper.toDto(managed);
        }
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        return withRetry(() -> self.doSaveCheckpointWithSteps(checkpointDto, steps), "save-checkpoint-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointDto doSaveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        // INTENTIONAL: doSaveEntity and doSaveSteps are called directly (not via self) so
        // their @Transactional(REQUIRES_NEW) is NOT intercepted. They participate in THIS
        // method's REQUIRES_NEW transaction, ensuring checkpoint + steps commit atomically.
        // Do NOT change to self.doSaveEntity() — that would start a nested REQUIRES_NEW,
        // split the commit, and break atomicity.
        CheckpointDto saved = doSaveEntity(checkpointDto);
        doSaveSteps(saved.id(), steps);
        return saved;
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return withRetry(() -> self.doFindById(id), "find-by-id");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindById(Long id) {
        em.clear();
        AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, id);
        return Optional.ofNullable(checkpoint).map(CheckpointMapper::toDto);
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return withRetry(() -> self.doFindBySessionAndRole(sessionId, agentRole), "find-by-role");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindBySessionAndRole(String sessionId, String agentRole) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.agentRole = :role " +
                "  AND c.status <> :archived " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return withRetry(() -> self.doFindActiveBySession(sessionId), "find-active");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindActiveBySession(String sessionId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.status NOT IN (:terminalStatuses) " +
                "ORDER BY c.id ASC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .toList();
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return withRetry(() -> self.doFindLatestAwaitingApproval(sessionId), "find-latest-awaiting");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestAwaitingApproval(String sessionId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.status = :status " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalWithPrefix(String sessionPrefix) {
        return withRetry(() -> self.doFindLatestAwaitingApprovalWithPrefix(sessionPrefix), "find-awaiting-prefix");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestAwaitingApprovalWithPrefix(String sessionPrefix) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId LIKE :prefix " +
                "  AND c.status = :status " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("prefix", sessionPrefix + "%")
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public void transitionStatus(Long id, String newStatus) {
        withRetry(() -> {
            self.doTransitionStatus(id, newStatus);
            return null;
        }, "transition-status");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doTransitionStatus(Long id, String newStatus) {
        em.clear();
        AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, id);
        if (checkpoint != null) {
            CheckpointStatus status = CheckpointStatus.valueOf(newStatus);
            checkpoint.setStatus(status);
            Instant now = Instant.now();
            switch (status) {
                case COMPLETED:
                    checkpoint.setCompletedAt(now);
                    checkpoint.setFailReason(null);
                    break;
                case FAILED:
                    checkpoint.setFailedAt(now);
                    break;
                default:
                    // No-op for other statuses
                    break;
            }
            log.debugf("Transitioned checkpoint %d to status %s", id, newStatus);
        }
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return withRetry(() -> self.doFindLatestBySession(sessionId), "find-latest");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestBySession(String sessionId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return withRetry(() -> self.doFindLatestNonArchivedBySession(sessionId), "find-latest-non-archived");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestNonArchivedBySession(String sessionId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.status <> :archived " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return withRetry(() -> self.doFindLatestBySessionAndStatus(sessionId, status), "find-latest-status");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestBySessionAndStatus(String sessionId, String status) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.status = :status " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .setMaxResults(1)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        withRetry(() -> {
            self.doSaveSteps(checkpointId, steps);
            return null;
        }, "save-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doSaveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        // NOTE: When called from doSaveCheckpointWithSteps(), the @Transactional annotation
        // on this method is bypassed (direct call, not CDI proxy). This is intentional.
        em.clear();
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();

        for (int i = 0; i < steps.size(); i++) {
            ReasoningStepDto dto = steps.get(i);
            ReasoningStepEntity entity = new ReasoningStepEntity();
            entity.setCheckpointId(checkpointId);
            entity.setSequenceNum(i);
            entity.setThoughtJson(dto.thoughtJson());
            entity.setAction(dto.action());
            entity.setActionInputJson(dto.actionInputJson());
            entity.setObservation(dto.observation());
            entity.setInputTokens(dto.inputTokens());
            entity.setOutputTokens(dto.outputTokens());
            entity.setSuccess(dto.success());
            entity.setErrorCode(dto.errorCode());
            entity.setTraceId(dto.traceId());
            entity.setRootCauseClass(dto.rootCauseClass());
            em.persist(entity);
        }
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return withRetry(() -> self.doLoadSteps(checkpointId), "load-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<ReasoningStepDto> doLoadSteps(Long checkpointId) {
        em.clear();
        return em.createQuery(
                "SELECT r FROM ReasoningStepEntity r " +
                "WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC",
                ReasoningStepEntity.class)
                .setParameter("id", checkpointId)
                .getResultList()
                .stream()
                .map(e -> new ReasoningStepDto(
                        e.getThoughtJson(), e.getAction(), e.getActionInputJson(),
                        e.getObservation(), e.getInputTokens(), e.getOutputTokens(),
                        e.getSuccess(), e.getErrorCode(), e.getTraceId(), e.getRootCauseClass()))
                .toList();
    }

    @Override
    public void deleteSteps(Long checkpointId) {
        withRetry(() -> {
            self.doDeleteSteps(checkpointId);
            return null;
        }, "delete-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doDeleteSteps(Long checkpointId) {
        em.clear();
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return withRetry(() -> self.doFindStaleAwaitingApproval(cutoff), "find-stale-approvals");
    }


    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return withRetry(() -> self.doFindByStatus(status), "find-by-status");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindByStatus(String status) {
        em.clear();
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status", AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .getResultList()
                .stream().map(CheckpointMapper::toDto).toList();
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        return withRetry(() -> self.doIsCheckpointExpired(sessionId, timeoutHours), "is-checkpoint-expired");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doIsCheckpointExpired(String sessionId, long timeoutHours) {
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
        em.clear();
        List<AgentCheckpoint> rows = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.status = :status " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
            .setParameter("sid", sessionId)
            .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
            .setMaxResults(1)
            .getResultList();
            
        if (rows.isEmpty()) return true;   // no AWAITING_APPROVAL → treat as expired
        AgentCheckpoint cp = rows.get(0);
        if (cp.getUpdatedAt() == null) return true;
        return cp.getUpdatedAt().isBefore(cutoff);
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return withRetry(() -> self.doFindInProgressZombies(cutoff), "find-in-progress-zombies");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindInProgressZombies(Instant cutoff) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", cutoff)
                .getResultList()
                .stream().map(CheckpointMapper::toDto).toList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindStaleAwaitingApproval(Instant cutoff) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList()
                .stream().map(CheckpointMapper::toDto).toList();
    }


    @Override
    public boolean isSessionActive(String sessionId) {
        return withRetry(() -> self.doIsSessionActive(sessionId), "is-session-active");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doIsSessionActive(String sessionId) {
        em.clear();
        Long count = em.createQuery("SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status NOT IN :terminalStatuses", Long.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .getSingleResult();
        return count > 0;
    }

    /**
     * Checks if there are any pending tool approvals for a session.
     * Used to determine if a session has awaiting-approval checkpoints.
     */
    public boolean hasPendingApproval(String sessionId) {
        return withRetry(() -> self.doHasPendingApproval(sessionId), "has-pending-approval");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doHasPendingApproval(String sessionId) {
        em.clear();
        return !em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.status = :status",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    /**
     * Deletes a checkpoint with a matching (sessionId, agentRole) pair.
     * Typically used for cleanup during session termination.
     */
    public void deleteCheckpoint(String sessionId, String role) {
        withRetry(() -> {
            self.doDeleteCheckpoint(sessionId, role);
            return null;
        }, "delete-checkpoint");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doDeleteCheckpoint(String sessionId, String role) {
        em.clear();
        em.createQuery(
            "DELETE FROM AgentCheckpoint c " +
            "WHERE c.sessionId = :sid AND c.agentRole = :role")
        .setParameter("sid", sessionId)
        .setParameter("role", role)
        .executeUpdate();
        log.debugf("Deleted checkpoint for session=%s, role=%s", sessionId, role);
    }

    /**
     * Marks all non-terminal checkpoints for a session as FAILED.
     * Used during session cleanup (markCheckpointsFailed).
     */
    public void markCheckpointsFailed(String sessionId) {
        withRetry(() -> {
            self.doMarkCheckpointsFailed(sessionId);
            return null;
        }, "mark-checkpoints-failed");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCheckpointsFailed(String sessionId) {
        em.clear();
        List<AgentCheckpoint> checkpoints = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.status NOT IN (:terminalStatuses)",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .getResultList();

        Instant now = Instant.now();
        for (var checkpoint : checkpoints) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setFailedAt(now);
            checkpoint.setFailReason("Session terminated by WorkspaceManager");
        }
        if (!checkpoints.isEmpty()) {
            log.infof("Marked %d checkpoint(s) FAILED for session=%s",
                      checkpoints.size(), sessionId);
        }
    }

    // ── FIX 8B: purgeOldChatHistory — moved from SessionReaper ──────────

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        withRetry(() -> {
            self.doPurgeOldChatHistory(cutoff);
            return null;
        }, "purge-old-chat-history");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doPurgeOldChatHistory(Instant cutoff) {
        em.clear();
        int deleted = em.createQuery(
            "DELETE FROM ChatMemoryEntity m WHERE m.memoryId NOT IN (" +
            "  SELECT c.sessionId FROM AgentCheckpoint c " +
            "  WHERE c.updatedAt > :cutoff " +
            "     OR c.status NOT IN (:terminalStatuses)" +
            ")")
            // Hibernate 6 bulk JPQL bypasses autoApply converters for parameters.
            // Manually format to String using TemporalUtils for SQLite TEXT comparison.
            .setParameter("cutoff", TemporalUtils.formatIsoLex(cutoff))
            .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
            .executeUpdate();

        if (deleted > 0) {
            log.infof("Purge complete: removed %d stale chat memory row(s).", deleted);
        }
    }

    // ── FIX 9: Atomic status transition methods ─────────────────────────

    @Override
    public void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        withRetry(() -> {
            self.doMarkFailed(checkpointId, failReason, errorCode, traceId, rootCauseClass);
            return null;
        }, "mark-failed");
    }

    @Override
    public List<CheckpointDto> recoverZombiesAndMarkFailed(Instant inProgressCutoff, Instant approvalCutoff, String failReason) {
        return withRetry(() -> self.doRecoverZombiesAndMarkFailed(inProgressCutoff, approvalCutoff, failReason), "recover-zombies");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doRecoverZombiesAndMarkFailed(Instant inProgressCutoff, Instant approvalCutoff, String failReason) {
        em.clear();
        
        // 1. In-progress zombies
        List<AgentCheckpoint> inProgress = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", inProgressCutoff)
                .getResultList();
        
        // 2. Awaiting-approval zombies
        List<AgentCheckpoint> awaiting = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", approvalCutoff)
                .getResultList();

        Instant now = Instant.now();
        List<CheckpointDto> results = new ArrayList<>();
        
        for (AgentCheckpoint cp : inProgress) {
            results.add(CheckpointMapper.toDto(cp));
            cp.setStatus(CheckpointStatus.FAILED);
            cp.setFailedAt(now);
            cp.setFailReason(failReason);
        }
        for (AgentCheckpoint cp : awaiting) {
            results.add(CheckpointMapper.toDto(cp));
            cp.setStatus(CheckpointStatus.ORPHANED);
            cp.setFailedAt(now);
            cp.setFailReason(failReason);
        }

        return results;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        em.clear();
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, checkpointId);
        if (cp == null) return;
        cp.setStatus(CheckpointStatus.FAILED);
        cp.setFailedAt(Instant.now());
        cp.setFailReason(failReason);
        cp.setErrorCode(errorCode);
        cp.setTraceId(traceId);
        cp.setRootCauseClass(rootCauseClass);
    }

    @Override
    public void markCompleted(Long checkpointId) {
        withRetry(() -> {
            self.doMarkCompleted(checkpointId);
            return null;
        }, "mark-completed");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCompleted(Long checkpointId) {
        em.clear();
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, checkpointId);
        if (cp == null) return;
        cp.setStatus(CheckpointStatus.COMPLETED);
        cp.setCompletedAt(Instant.now());
        cp.setFailReason(null);
    }

    @Override
    public void markOrphaned(Long checkpointId, String reason) {
        withRetry(() -> {
            self.doMarkOrphaned(checkpointId, reason);
            return null;
        }, "mark-orphaned");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkOrphaned(Long checkpointId, String reason) {
        em.clear();
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, checkpointId);
        if (cp == null) return;
        cp.setStatus(CheckpointStatus.ORPHANED);
        cp.setFailedAt(Instant.now());
        cp.setFailReason(reason);
    }

    // ── FIX 10: Atomic batch tool decision update ───────────────────────

    @Override
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole,
                                                  String toolName, String decision) {
        return withRetry(() -> self.doAtomicUpdateBatchToolDecision(sessionId, agentRole, toolName, decision),
                "atomic-update-batch-decision");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doAtomicUpdateBatchToolDecision(String sessionId, String agentRole,
                                                    String toolName, String decision) {
        em.clear();
        List<AgentCheckpoint> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.agentRole = :role " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList();
        if (results.isEmpty()) return false;

        AgentCheckpoint cp = results.get(0);
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL || cp.getBatchToolDecisions() == null) {
            return false;
        }

        try {
            Map<String, ToolDecision> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            if (!map.containsKey(toolName)) return false;
            map.put(toolName, ToolDecision.valueOf(decision));
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(map));
            return true;
        } catch (Exception e) {
            log.errorf("Failed to update batch tool decision for session=%s tool=%s: %s",
                       sessionId, toolName, e.getMessage());
            return false;
        }
    }

    /**
     * Retry wrapper that delegates to retryTemplate with SQLite BUSY handling.
     * 
     * Encapsulates the SqliteRetryTemplate inside the adapter; callers see
     * only the CheckpointStorePort interface, not retry semantics.
     * 
     * Architecture rule (§5.14.1): Retry-on-BUSY is a SQLite-specific concern
     * that MUST NOT leak to core domain code via a PersistenceRetryPort interface.
     */
    private <T> T withRetry(Callable<T> task, String operation) {
        try {
            return retryTemplate.execute(operation, 5, 50, task);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re)
                ? re
                : new RuntimeException("Adapter operation failed after retries: " + operation, e);
        }
    }

    // ── Conversion helpers ───────────────

    private void updateEntityFromDto(AgentCheckpoint entity, CheckpointDto dto) {
        entity.setSessionId(dto.sessionId());
        entity.setAgentRole(dto.agentRole());
        
        CheckpointStatus oldStatus = entity.getStatus();
        if (dto.status() != null) {
            entity.setStatus(CheckpointStatus.valueOf(dto.status()));
        }
        if (dto.autonomyLevel() != null) {
            entity.setAutonomyLevel(AutonomyLevel.valueOf(dto.autonomyLevel()));
        }
        entity.setStateJson(dto.stateJson());
        entity.setChannelPlatformId(dto.channelPlatformId());
        entity.setChannelType(dto.channelType());
        entity.setOriginalGoal(dto.originalGoal());
        entity.setFailedAt(dto.failedAt());
        entity.setCompletedAt(dto.completedAt());
        entity.setFailReason(dto.failReason());
        entity.setPendingToolName(dto.pendingToolName());
        
        // A16 — Stale Join Guard: merge batch decisions to prevent overwriting active decisions
        if (CheckpointStatus.AWAITING_APPROVAL.toString().equals(dto.status()) 
                && oldStatus == CheckpointStatus.AWAITING_APPROVAL) {
            mergeBatchDecisions(entity, dto);
        } else {
            entity.setBatchToolDecisions(dto.batchToolDecisions());
        }

        entity.setPendingToolParametersJson(dto.pendingToolParametersJson());
        entity.setPreExecutedResults(dto.preExecutedResults());
        entity.setMaxIterations(dto.maxIterations());
        entity.setIterationsUsed(dto.iterationsUsed());
        entity.setRetryAfterMs(dto.retryAfterMs());
        entity.setSystemNote(dto.systemNote());
        entity.setErrorCode(dto.errorCode());
        entity.setTraceId(dto.traceId());
        entity.setRootCauseClass(dto.rootCauseClass());
        entity.setUpdatedAt(dto.updatedAt());
    }

    private void mergeBatchDecisions(AgentCheckpoint managed, CheckpointDto incoming) {
        if (incoming.batchToolDecisions() == null) return;
        if (managed.getBatchToolDecisions() == null) {
            managed.setBatchToolDecisions(incoming.batchToolDecisions());
            return;
        }
        try {
            Map<String, ToolDecision> managedMap = objectMapper.readValue(
                managed.getBatchToolDecisions(),
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
                managed.setBatchToolDecisions(objectMapper.writeValueAsString(incomingMap));
            } else {
                managed.setBatchToolDecisions(incoming.batchToolDecisions());
            }

        } catch (Exception e) {
            log.warnf("Failed to merge batch decisions for session %s: %s", 
                    managed.getSessionId(), e.getMessage());
            managed.setBatchToolDecisions(incoming.batchToolDecisions());
        }
    }
}
