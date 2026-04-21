package com.arcclaw.persistence.sqlite;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

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
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

/**
 * SQLite implementation of CheckpointStorePort.
 * §1.2: All JPQL moved from core to here.
 * §5.8: Implements single-writer safe transaction patterns using REQUIRES_NEW
 *       at the outermost write boundary via self-proxy interception.
 */
@ApplicationScoped
@Unremovable
public class SqliteCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(SqliteCheckpointStoreAdapter.class);

    @Inject
    EntityManager em;

    @Inject
    public SqliteCheckpointStoreAdapter self; // self-proxy for @Transactional interception

    @Inject
    SqliteRetryTemplate retryTemplate;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public CheckpointDto save(CheckpointDto checkpointDto) {
        return withRetry(() -> self.doSaveEntity(checkpointDto), "save-entity");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointDto doSaveEntity(CheckpointDto checkpointDto) {
        return internalSaveEntity(checkpointDto);
    }

    @Override
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, 
        String stateJson, String autonomy, int iterationsUsed) {
        return withRetry(() -> self.doUpsertWithSteps(sessionId, agentRole, stateJson, autonomy, iterationsUsed), "upsert-with-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointUpsertResult doUpsertWithSteps(String sessionId, String agentRole, 
        String stateJson, String autonomy, int iterationsUsed) {
        
        em.clear();
        List<AgentCheckpoint> existing = em.createQuery(
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
                .getResultList();

        AgentCheckpoint entity;
        boolean created = false;
        if (existing.isEmpty()) {
            entity = new AgentCheckpoint();
            entity.setSessionId(sessionId);
            entity.setAgentRole(agentRole);
            entity.setCreatedAt(Instant.now());
            created = true;
        } else {
            entity = existing.get(0);
        }

        entity.setStatus(CheckpointStatus.IN_PROGRESS);
        entity.setStateJson(stateJson);
        if (autonomy != null) {
            entity.setAutonomyLevel(AutonomyLevel.valueOf(autonomy));
        }
        entity.setIterationsUsed(iterationsUsed);
        entity.setUpdatedAt(Instant.now());

        if (created) {
            em.persist(entity);
        } else {
            entity = em.merge(entity);
        }
        
        em.flush();
        return new CheckpointUpsertResult(CheckpointMapper.toDto(entity), created);
    }

    @Override
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, 
        String stateJson, String finalStatus, List<ReasoningStepDto> steps) {
        return withRetry(() -> self.doUpsertAndFinalize(sessionId, agentRole, stateJson, finalStatus, steps), "upsert-and-finalize");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointUpsertResult doUpsertAndFinalize(String sessionId, String agentRole, 
        String stateJson, String finalStatus, List<ReasoningStepDto> steps) {
        
        em.clear();
        List<AgentCheckpoint> existing = em.createQuery(
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
                .getResultList();

        AgentCheckpoint entity;
        boolean created = false;
        CheckpointStatus status = CheckpointStatus.valueOf(finalStatus);

        if (existing.isEmpty()) {
            entity = new AgentCheckpoint();
            entity.setSessionId(sessionId);
            entity.setAgentRole(agentRole);
            entity.setCreatedAt(Instant.now());
            created = true;
        } else {
            entity = existing.get(0);
        }

        entity.setStatus(status);
        entity.setStateJson(stateJson);
        entity.setUpdatedAt(Instant.now());

        if (status == CheckpointStatus.COMPLETED) {
            entity.setCompletedAt(Instant.now());
        } else if (status == CheckpointStatus.FAILED) {
            entity.setFailedAt(Instant.now());
        }

        if (created) {
            em.persist(entity);
        } else {
            entity = em.merge(entity);
        }
        
        em.flush();
        CheckpointDto savedDto = CheckpointMapper.toDto(entity);
        
        // 🟢 ATOMICITY FIX: Internal private method avoids nested REQUIRES_NEW
        internalSaveSteps(savedDto.id(), steps);
        
        return new CheckpointUpsertResult(savedDto, created);
    }

    @Override
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        return withRetry(() -> self.doSaveCheckpointWithSteps(checkpointDto, steps), "save-checkpoint-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public CheckpointDto doSaveCheckpointWithSteps(CheckpointDto checkpointDto, List<ReasoningStepDto> steps) {
        CheckpointDto saved = internalSaveEntity(checkpointDto);
        internalSaveSteps(saved.id(), steps);
        return saved;
    }

    private CheckpointDto internalSaveEntity(CheckpointDto checkpointDto) {
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
                .filter(Objects::nonNull)
                .map(CheckpointMapper::toDto)
                .findFirst();
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return withRetry(() -> self.doFindActiveBySession(sessionId), "find-active");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindActiveBySession(String sessionId) {
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
    public void transitionStatus(Long id, String newStatus) {
        withRetry(() -> {
            self.doTransitionStatus(id, newStatus);
            return null;
        }, "transition-status");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doTransitionStatus(Long id, String newStatus) {
        em.clear();
        AgentCheckpoint entity = em.find(AgentCheckpoint.class, id);
        if (entity != null) {
            entity.setStatus(CheckpointStatus.valueOf(newStatus));
            entity.setUpdatedAt(Instant.now());
        }
    }

    @Override
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        return withRetry(() -> self.doAtomicTransition(id, expectedStatus, newStatus), "atomic-transition");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doAtomicTransition(Long id, String expectedStatus, String newStatus) {
        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = :new, updated_at = :now " +
                "WHERE checkpoint_id = :id AND status = :expected"
        ).setParameter("new", newStatus)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now())) // §ISO-LEX consistency
         .setParameter("id", id)
         .setParameter("expected", expectedStatus)
         .executeUpdate();
        return updated == 1;
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        return withRetry(() -> self.doClaimForResume(sessionId, agentRole), "claim-for-resume");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doClaimForResume(String sessionId, String agentRole) {
        // Atomic transition of the latest checkpoint to RESUMING
        // We need to find the latest ID first in a subquery or native SQL
        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'RESUMING', updated_at = :now " +
                "WHERE checkpoint_id = (SELECT checkpoint_id FROM agent_checkpoint " +
                "            WHERE session_id = :sid AND agent_role = :role " +
                "            ORDER BY checkpoint_id DESC LIMIT 1) " +
                "  AND status = 'AWAITING_APPROVAL'"
        ).setParameter("sid", sessionId)
         .setParameter("role", agentRole)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now())) // §ISO-LEX consistency
         .executeUpdate();
        return updated == 1;
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
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        withRetry(() -> {
            self.doSaveSteps(checkpointId, steps);
            return null;
        }, "save-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doSaveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        internalSaveSteps(checkpointId, steps);
    }

    private void internalSaveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
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
        em.flush();
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return withRetry(() -> self.doLoadSteps(checkpointId), "load-steps");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<ReasoningStepDto> doLoadSteps(Long checkpointId) {
        return em.createQuery(
                "SELECT r FROM ReasoningStepEntity r WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC",
                ReasoningStepEntity.class)
                .setParameter("id", checkpointId)
                .getResultList()
                .stream()
                .map(s -> new ReasoningStepDto(s.getThoughtJson(), s.getAction(), s.getActionInputJson(),
                        s.getObservation(), s.getInputTokens(), s.getOutputTokens(), s.getSuccess(), 
                        s.getErrorCode(), s.getTraceId(), s.getRootCauseClass()))
                .collect(Collectors.toList());
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
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return withRetry(() -> self.doFindByStatus(status), "find-by-status");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindByStatus(String status) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        return withRetry(() -> self.doIsCheckpointExpired(sessionId, timeoutHours), "is-expired");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doIsCheckpointExpired(String sessionId, long timeoutHours) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(timeoutHours));
        List<AgentCheckpoint> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList();
        return !results.isEmpty();
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return withRetry(() -> self.doIsSessionActive(sessionId), "is-active");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doIsSessionActive(String sessionId) {
        List<AgentCheckpoint> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status NOT IN (:terminalStatuses)",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .setMaxResults(1)
                .getResultList();
        return !results.isEmpty();
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return withRetry(() -> self.doFindInProgressZombies(cutoff), "find-zombies");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindInProgressZombies(Instant cutoff) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", cutoff)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return withRetry(() -> self.doFindStaleApprovals(cutoff), "find-stale-approvals");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doFindStaleApprovals(Instant cutoff) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalWithPrefix(String sessionPrefix) {
        return withRetry(() -> self.doFindLatestAwaitingApprovalWithPrefix(sessionPrefix), "find-latest-prefix");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestAwaitingApprovalWithPrefix(String sessionPrefix) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId LIKE :prefix AND c.status = :status ORDER BY c.id DESC",
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
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return withRetry(() -> self.doFindLatestBySession(sessionId), "find-latest");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<CheckpointDto> doFindLatestBySession(String sessionId) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid ORDER BY c.id DESC",
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
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status <> :archived ORDER BY c.id DESC",
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
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status = :status ORDER BY c.id DESC",
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
    public void markCheckpointsFailed(String sessionId) {
        withRetry(() -> {
            self.doMarkCheckpointsFailed(sessionId);
            return null;
        }, "mark-failed-all");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCheckpointsFailed(String sessionId) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed, c.failedAt = :now, c.updatedAt = :now " +
                       "WHERE c.sessionId = :sid AND c.status NOT IN (:terminalStatuses)")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("now", Instant.now())
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .executeUpdate();
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        withRetry(() -> {
            self.doDeleteCheckpoint(sessionId, role);
            return null;
        }, "delete-checkpoint");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doDeleteCheckpoint(String sessionId, String role) {
        List<AgentCheckpoint> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.agentRole = :role",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", role)
                .getResultList();
        results.forEach(em::remove);
    }

    @Override
    public void purgeOldChatHistory(Instant cutoff) {
        withRetry(() -> {
            self.doPurgeOldChatHistory(cutoff);
            return null;
        }, "purge-history");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doPurgeOldChatHistory(Instant cutoff) {
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId IN " +
                       "(SELECT c.id FROM AgentCheckpoint c WHERE c.updatedAt < :cutoff)")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        em.createQuery("DELETE FROM AgentCheckpoint c WHERE c.updatedAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    public void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        withRetry(() -> {
            self.doMarkFailed(checkpointId, failReason, errorCode, traceId, rootCauseClass);
            return null;
        }, "mark-failed");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        em.clear();
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, checkpointId);
        if (cp == null) return;
        cp.setStatus(CheckpointStatus.FAILED);
        cp.setFailReason(failReason);
        cp.setErrorCode(errorCode);
        cp.setTraceId(traceId);
        cp.setRootCauseClass(rootCauseClass);
        cp.setFailedAt(Instant.now());
        cp.setUpdatedAt(Instant.now());
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
        cp.setUpdatedAt(Instant.now());
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

    @Override
    public boolean claimForRetry(Long id) {
        return withRetry(() -> self.doClaimForRetry(id), "claim-for-retry");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doClaimForRetry(Long id) {
        em.clear();
        AgentCheckpoint entity = em.find(AgentCheckpoint.class, id);
        if (entity != null && CheckpointStatus.AWAITING_RETRY == entity.getStatus()) {
            entity.setStatus(CheckpointStatus.IN_PROGRESS);
            entity.setRetryAfterMs(null);
            entity.setUpdatedAt(Instant.now());
            em.flush();
            return true;
        }
        return false;
    }

    @Override
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        return withRetry(() -> self.doRecoverInProgressZombies(inProgressCutoff, failReason), "recover-zombies");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doRecoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        zombies.forEach(c -> self.doMarkFailed(c.id(), failReason, "ZOMBIE", null, null));
        return zombies;
    }

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
