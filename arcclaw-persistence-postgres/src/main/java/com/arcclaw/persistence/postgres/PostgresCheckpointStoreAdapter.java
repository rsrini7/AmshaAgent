package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.ReasoningStepEntity;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import org.jboss.logging.Logger;

/**
 * PostgreSQL implementation of CheckpointStorePort.
 */
@ApplicationScoped
public class PostgresCheckpointStoreAdapter implements CheckpointStorePort {

    private static final Logger log = Logger.getLogger(PostgresCheckpointStoreAdapter.class);

    @Inject EntityManager em;

    @Override
    @Transactional
    public CheckpointDto save(CheckpointDto dto) {
        AgentCheckpoint entity = dto.id() != null ? em.find(AgentCheckpoint.class, dto.id()) : null;
        if (entity == null) {
            entity = new AgentCheckpoint();
        }
        updateEntity(entity, dto);
        entity = em.merge(entity);
        return toDto(entity);
    }

    @Override
    @Transactional
    public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
        CheckpointDto saved = save(checkpoint);
        saveSteps(saved.id(), steps);
        return saved;
    }

    @Override
    public Optional<CheckpointDto> findById(Long id) {
        return Optional.ofNullable(em.find(AgentCheckpoint.class, id)).map(this::toDto);
    }

    @Override
    public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.agentRole = :role ORDER BY c.id DESC", AgentCheckpoint.class)
                .setParameter("sessionId", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(this::toDto);
    }

    @Override
    public List<CheckpointDto> findActiveBySession(String sessionId) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status NOT IN (:terminal)", AgentCheckpoint.class)
                .setParameter("sessionId", sessionId)
                .setParameter("terminal", List.of(CheckpointStatus.COMPLETED, CheckpointStatus.FAILED, CheckpointStatus.DENIED))
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void transitionStatus(Long id, String newStatus) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :status WHERE c.id = :id")
                .setParameter("status", CheckpointStatus.valueOf(newStatus))
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
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
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff", AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId LIKE :segment AND c.status = :status ORDER BY c.id DESC", AgentCheckpoint.class)
                .setParameter("segment", "%" + segment + "%")
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(this::toDto);
    }

    @Override
    public Optional<CheckpointDto> findLatestBySession(String sessionId) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId ORDER BY c.id DESC", AgentCheckpoint.class)
                .setParameter("sessionId", sessionId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(this::toDto);
    }

    @Override
    public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status != :archived ORDER BY c.id DESC", AgentCheckpoint.class)
                .setParameter("sessionId", sessionId)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(this::toDto);
    }

    @Override
    public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status = :status ORDER BY c.id DESC", AgentCheckpoint.class)
                .setParameter("sessionId", sessionId)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(this::toDto);
    }

    @Override
    @Transactional
    public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
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
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Override
    public List<CheckpointDto> findByStatus(String status) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status", AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .getResultStream()
                .map(this::toDto)
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
        Long count = em.createQuery("SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status NOT IN (:terminal)", Long.class)
                .setParameter("sessionId", sessionId)
                .setParameter("terminal", List.of(CheckpointStatus.COMPLETED, CheckpointStatus.FAILED, CheckpointStatus.DENIED))
                .getSingleResult();
        return count > 0;
    }

    @Override
    public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
        return findLatestBySessionAndStatus(sessionId, "AWAITING_APPROVAL");
    }

    @Override
    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff", AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", cutoff)
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return findLatestAwaitingApproval(sessionId).isPresent();
    }

    @Override
    @Transactional
    public void markCheckpointsFailed(String sessionId) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed WHERE c.sessionId = :sessionId AND c.status NOT IN (:terminal)")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("sessionId", sessionId)
                .setParameter("terminal", List.of(CheckpointStatus.COMPLETED, CheckpointStatus.FAILED, CheckpointStatus.DENIED, CheckpointStatus.ARCHIVED))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void deleteCheckpoint(String sessionId, String role) {
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
        AgentCheckpoint entity = em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.agentRole = :role", AgentCheckpoint.class)
                .setParameter("sessionId", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpoint();
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
        return new CheckpointUpsertResult(toDto(entity), true);
    }

    @Override
    @Transactional
    public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        CheckpointUpsertResult res = upsertWithSteps(sessionId, agentRole, stateJson, "FULL", 0, originalGoal, contextDataJson);
        transitionStatus(res.checkpoint().id(), finalStatus);
        saveSteps(res.checkpoint().id(), steps);
        return res;
    }

    @Override
    @Transactional
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        for (CheckpointDto z : zombies) {
            markFailed(z.id(), failReason, "ZOMBIE_RECOVERY", null, null);
        }
        return zombies;
    }

    @Override
    @Transactional
    public void purgeOldChatHistory(Instant cutoff) {
        // Chat memory is in PersistentChatMemoryStore
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
        transitionStatus(checkpointId, "ARCHIVED");
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
    @Transactional
    public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        // Complex JSON update, omitted for now
        return true;
    }

    private void updateEntity(AgentCheckpoint entity, CheckpointDto dto) {
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
        entity.setUpdatedAt(Instant.now());
    }

    private CheckpointDto toDto(AgentCheckpoint c) {
        return new CheckpointDto(
                c.getId(), c.getSessionId(), c.getAgentRole(),
                c.getStatus() != null ? c.getStatus().name() : null,
                c.getAutonomyLevel() != null ? c.getAutonomyLevel().name() : null,
                null, c.getChannelPlatformId(), c.getChannelType(),
                c.getOriginalGoal(), c.getFailedAt(), c.getCompletedAt(), c.getFailReason(),
                c.getPendingToolName(), c.getBatchToolDecisions(), c.getPendingToolParametersJson(),
                c.getPreExecutedResults(), c.getMaxIterations(), c.getIterationsUsed(),
                c.getRetryAfterMs(), c.getSystemNote(), c.getContextDataJson(),
                c.getErrorCode(), c.getTraceId(), c.getRootCauseClass(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
