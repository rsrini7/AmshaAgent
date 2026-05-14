package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.postgres.entity.AgentCheckpointEntity;
import com.arcclaw.persistence.postgres.entity.ReasoningStepEntity;
import org.jboss.logging.Logger;

/**
 * PostgreSQL implementation of CheckpointStorePort.
 */
@ApplicationScoped
public class PostgresCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(PostgresCheckpointStoreAdapter.class);

    @Inject EntityManager em;
    @Inject CheckpointBatchOperations batchOps;
    @Inject ZombieRecoveryService zombieRecoveryService;

    @Override
    @Transactional
    public CheckpointDto save(CheckpointDto dto) {
        log.infof("[PostgresCheckpointAdapter] Saving checkpoint session=%s role=%s", dto.sessionId(), dto.agentRole());
        AgentCheckpointEntity entity = dto.id() != null ? em.find(AgentCheckpointEntity.class, dto.id()) : null;
        if (entity == null) {
            entity = new AgentCheckpointEntity();
        }
        CheckpointMapper.updateEntity(entity, dto);
        entity = em.merge(entity);
        return CheckpointMapper.toDto(entity);
    }

    @Override
    @Transactional
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
        log.infof("[PostgresCheckpointAdapter] Saving checkpoint with %d steps for session=%s", steps.size(), checkpoint.sessionId());
        CheckpointDto saved = save(checkpoint);
        saveSteps(saved.id(), steps);
        return saved;
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        log.debugf("[PostgresCheckpointAdapter] Finding checkpoint by id=%d", id);
        return Optional.ofNullable(em.find(AgentCheckpointEntity.class, id)).map(CheckpointMapper::toDto);
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.agentRole = :role ORDER BY c.id DESC", AgentCheckpointEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(CheckpointMapper::toDto);
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status NOT IN (:terminal)", AgentCheckpointEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("terminal", List.of(CheckpointStatus.COMPLETED, CheckpointStatus.FAILED, CheckpointStatus.DENIED))
                .getResultStream()
                .map(CheckpointMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void transitionStatus(Long id, String newStatus) {
        log.infof("[PostgresCheckpointAdapter] Transitioning checkpoint %d to %s", id, newStatus);
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :status WHERE c.id = :id")
                .setParameter("status", CheckpointStatus.valueOf(newStatus))
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
        log.debugf("[PostgresCheckpointAdapter] Atomic transition checkpoint %d from %s to %s", id, expectedStatus, newStatus);
        int updated = em.createQuery("UPDATE AgentCheckpoint c SET c.status = :newStatus WHERE c.id = :id AND c.status = :expected")
                .setParameter("newStatus", CheckpointStatus.valueOf(newStatus))
                .setParameter("expected", CheckpointStatus.valueOf(expectedStatus))
                .setParameter("id", id)
                .executeUpdate();
        return updated > 0;
    }

    @Override
    @Transactional
    public boolean claimForResume(String sessionId, String agentRole) {
        log.infof("[PostgresCheckpointAdapter] Attempting to claim resume for session=%s role=%s", sessionId, agentRole);
        int updated = em.createQuery("UPDATE AgentCheckpoint c SET c.status = :resuming WHERE c.sessionId = :sessionId AND c.agentRole = :role AND c.status = :approved")
                .setParameter("resuming", CheckpointStatus.RESUMING)
                .setParameter("approved", CheckpointStatus.APPROVED)
                .setParameter("sessionId", sessionId)
                .setParameter("role", agentRole)
                .executeUpdate();
        return updated > 0;
    }

    @Override
    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return zombieRecoveryService.findStaleApprovals(cutoff);
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId LIKE :segment AND c.status = :status ORDER BY c.id DESC", AgentCheckpointEntity.class)
                .setParameter("segment", "%" + segment + "%")
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(CheckpointMapper::toDto);
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId ORDER BY c.id DESC", AgentCheckpointEntity.class)
                .setParameter("sessionId", sessionId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(CheckpointMapper::toDto);
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status != :archived ORDER BY c.id DESC", AgentCheckpointEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(CheckpointMapper::toDto);
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status = :status ORDER BY c.id DESC", AgentCheckpointEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(CheckpointMapper::toDto);
    }

    @Override
    @Transactional
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        log.infof("[PostgresCheckpointAdapter] Saving %d steps for checkpoint=%d", steps.size(), checkpointId);
        deleteSteps(checkpointId);
        int seq = 0;
        for (ReasoningStepDto dto : steps) {
            ReasoningStepEntity entity = new ReasoningStepEntity();
            entity.setCheckpointId(checkpointId);
            entity.setSequenceNum(seq++);
            entity.setThoughtJson(dto.thoughtJson());
            entity.setAction(dto.action());
            entity.setActionInputJson(dto.actionInputJson());
            entity.setObservation(dto.observation());
            entity.setInputTokens(dto.inputTokens() != null ? dto.inputTokens() : 0);
            entity.setOutputTokens(dto.outputTokens() != null ? dto.outputTokens() : 0);
            entity.setSuccess(dto.success());
            entity.setErrorCode(dto.errorCode());
            entity.setTraceId(dto.traceId());
            entity.setRootCauseClass(dto.rootCauseClass());
            em.persist(entity);
        }
    }

    @Override
    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return em.createQuery("SELECT r FROM ReasoningStepEntity r WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC", ReasoningStepEntity.class)
                .setParameter("id", checkpointId)
                .getResultStream()
                .map(r -> new ReasoningStepDto(
                        r.getThoughtJson(), r.getAction(), r.getActionInputJson(),
                        r.getObservation(), r.getInputTokens(), r.getOutputTokens(),
                        r.getSuccess(), r.getErrorCode(), r.getTraceId(), r.getRootCauseClass()
                ))
                .toList();
    }

    @Override
    @Transactional
    public void deleteSteps(Long checkpointId) {
        log.infof("[PostgresCheckpointAdapter] Deleting steps for checkpoint=%d", checkpointId);
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status", AgentCheckpointEntity.class)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .getResultStream()
                .map(CheckpointMapper::toDto)
                .toList();
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        Long count = em.createQuery("SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.updatedAt < :cutoff AND c.status = :status", Long.class)
                .setParameter("sessionId", sessionId)
                .setParameter("cutoff", Instant.now().minusSeconds(timeoutHours * 3600))
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .getSingleResult();
        return count > 0;
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return zombieRecoveryService.isSessionActive(sessionId);
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return findLatestBySessionAndStatus(sessionId, CheckpointStatus.AWAITING_APPROVAL.name());
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return zombieRecoveryService.findInProgressZombies(cutoff);
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    @Transactional
    public void markCheckpointsFailed(String sessionId) {
        log.warnf("[PostgresCheckpointAdapter] Marking ALL active checkpoints as FAILED for session=%s", sessionId);
        zombieRecoveryService.markCheckpointsFailed(sessionId);
    }

    @Override
    @Transactional
    public void deleteCheckpoint(String sessionId, String role) {
        log.infof("[PostgresCheckpointAdapter] Deleting checkpoint for session=%s role=%s", sessionId, role);
        findBySessionAndRole(sessionId, role).ifPresent(dto -> {
            deleteSteps(dto.id());
            em.createQuery("DELETE FROM AgentCheckpoint c WHERE c.id = :id")
                    .setParameter("id", dto.id())
                    .executeUpdate();
        });
    }

    @Override
    @Transactional
    public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) {
        log.infof("[PostgresCheckpointAdapter] Upserting checkpoint for session=%s role=%s", sessionId, agentRole);
        AgentCheckpointEntity entity = em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.agentRole = :role", AgentCheckpointEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseGet(() -> {
                    AgentCheckpointEntity c = new AgentCheckpointEntity();
                    c.setSessionId(sessionId);
                    c.setAgentRole(agentRole);
                    return c;
                });

        entity.setStatus(CheckpointStatus.IN_PROGRESS);
        entity.setAutonomyLevel(autonomy != null ? AutonomyLevel.valueOf(autonomy) : AutonomyLevel.SUPERVISED);
        entity.setIterationsUsed(iterationsUsed);
        entity.setOriginalGoal(originalGoal);
        entity.setContextDataJson(contextDataJson);
        entity.setUpdatedAt(Instant.now());
        
        entity = em.merge(entity);
        return new CheckpointUpsertResult(CheckpointMapper.toDto(entity), true);
    }

    @Override
    @Transactional
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        log.infof("[PostgresCheckpointAdapter] Upserting and finalizing checkpoint for session=%s (status=%s)", sessionId, finalStatus);
        CheckpointUpsertResult res = upsertWithSteps(sessionId, agentRole, stateJson, "FULL", 0, originalGoal, contextDataJson);
        transitionStatus(res.checkpoint().id(), finalStatus);
        saveSteps(res.checkpoint().id(), steps);
        return res;
    }

    @Override
    @Transactional
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        log.infof("[PostgresCheckpointAdapter] Recovering zombie checkpoints before %s", inProgressCutoff);
        return zombieRecoveryService.recoverInProgressZombies(inProgressCutoff, failReason);
    }

    @Override
    @Transactional
    public void purgeOldChatHistory(Instant cutoff) {
        log.infof("[PostgresCheckpointAdapter] Purging old chat history before %s", cutoff);
        zombieRecoveryService.purgeOldChatHistory(cutoff);
    }

    @Override
    @Transactional
    public void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed, c.failReason = :reason, c.errorCode = :code, c.traceId = :trace, c.rootCauseClass = :root, c.failedAt = :now WHERE c.id = :id")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("reason", failReason)
                .setParameter("code", errorCode)
                .setParameter("trace", traceId)
                .setParameter("root", rootCauseClass)
                .setParameter("now", Instant.now())
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markCompleted(Long checkpointId) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :completed, c.completedAt = :now WHERE c.id = :id")
                .setParameter("completed", CheckpointStatus.COMPLETED)
                .setParameter("now", Instant.now())
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markOrphaned(Long checkpointId, String reason) {
        markFailed(checkpointId, "ORPHANED: " + reason, "ORPHANED", null, null);
    }

    @Override
    @Transactional
    public void archive(Long checkpointId) {
        transitionStatus(checkpointId, CheckpointStatus.ARCHIVED.name());
    }

    @Override
    @Transactional
    public boolean claimForRetry(Long id) {
        int updated = em.createQuery("UPDATE AgentCheckpoint c SET c.status = :resuming WHERE c.id = :id AND c.status = :awaiting")
                .setParameter("resuming", CheckpointStatus.RESUMING)
                .setParameter("awaiting", CheckpointStatus.AWAITING_RETRY)
                .setParameter("id", id)
                .executeUpdate();
        return updated > 0;
    }

    @Override
    @Transactional
    public void releaseResumeClaim(String sessionId, String agentRole) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :inProgress WHERE c.sessionId = :sessionId AND c.agentRole = :role AND c.status = :resuming")
                .setParameter("inProgress", CheckpointStatus.IN_PROGRESS)
                .setParameter("sessionId", sessionId)
                .setParameter("role", agentRole)
                .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        return batchOps.atomicUpdateToolDecision(sessionId, agentRole, toolName, decision);
    }

    @Override
    public boolean hasPendingApprovalForChat(String chatId) {
        return batchOps.hasPendingApprovalForChat(chatId);
    }

}


