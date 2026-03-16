package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import org.hibernate.exception.LockAcquisitionException;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.persistence.SqliteErrorStrings;
import com.arcclaw.core.util.MdcKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

/**
 * JPA EntityManager-based implementation of {@link StatefulAgentStore}.
 * <p>
 * Uses plain {@link EntityManager} instead of Panache because the entity
 * lives in {@code arcclaw-core} — Panache bytecode enhancement only runs
 * in the Quarkus application module ({@code arcclaw-app}).
 * <p>
 * Uses Jackson {@link ObjectMapper} (auto-configured by Quarkus) to
 * serialize/deserialize {@code List<ReasoningStep>} to/from the
 * {@link AgentCheckpoint#stateJson} TEXT field.
 */
@ApplicationScoped
public class StatefulAgentStoreImpl implements StatefulAgentStore {

    private static final Logger log = Logger.getLogger(StatefulAgentStoreImpl.class);
    private static final TypeReference<List<ReasoningStep>> STEPS_TYPE = new TypeReference<>() {
    };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager em;

    @Inject
    AuditService auditService;

    private static final long BACKOFF_BASE_MS = 50;

    @Inject
    StatefulAgentStoreImpl self;

    @Override
    public AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps) {
        return withRetry(() -> self.doSaveSteps(sessionId, agentRole, steps), "save-upsert");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public AgentCheckpoint doSaveSteps(String sessionId, String agentRole, List<ReasoningStep> steps) {
        em.clear();
        String json = serializeSteps(steps);

        Optional<AgentCheckpoint> existing = findBySessionAndRole(sessionId, agentRole);

        AgentCheckpoint checkpoint;
        if (existing.isPresent()) {
            checkpoint = existing.get();
            checkpoint.setStateJson(json);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            log.debugf("Updated checkpoint for session=%s, role=%s", sessionId, agentRole);
        } else {
            checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId(sessionId);
            checkpoint.setAgentRole(agentRole);
            checkpoint.setStateJson(json);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            em.persist(checkpoint);
            
            String userId = (String) MDC.get(MdcKeys.USER_ID);
            auditService.record(sessionId, userId, agentRole, SecurityEventType.CHECKPOINT_CREATED, null);
            
            log.infof("Created checkpoint for session=%s, role=%s", sessionId, agentRole);
        }
        return checkpoint;
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        return withRetry(() -> self.doSaveEntity(checkpoint), "save-entity");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public AgentCheckpoint doSaveEntity(AgentCheckpoint checkpoint) {
        em.clear();
        if (checkpoint.getId() == null) {
            em.persist(checkpoint);
            return checkpoint;
        } else {
            AgentCheckpoint managed = em.find(AgentCheckpoint.class, checkpoint.getId());
            if (managed == null) {
                em.persist(checkpoint);
                return checkpoint;
            }
            managed.setSessionId(checkpoint.getSessionId());
            managed.setAgentRole(checkpoint.getAgentRole());
            managed.setStatus(checkpoint.getStatus());
            managed.setStateJson(checkpoint.getStateJson());
            managed.setFailedAt(checkpoint.getFailedAt());
            managed.setCompletedAt(checkpoint.getCompletedAt());
            managed.setFailReason(checkpoint.getFailReason());
            managed.setPendingToolName(checkpoint.getPendingToolName());
            managed.setPendingToolParametersJson(checkpoint.getPendingToolParametersJson());
            return managed;
        }
    }

    @Override
    @Transactional
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return findBySessionAndRole(sessionId, agentRole);
    }

    @Override
    @Deprecated
    public List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint) {
        if (checkpoint.getStateJson() == null || checkpoint.getStateJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(checkpoint.getStateJson(), STEPS_TYPE);
        } catch (JsonProcessingException e) {
            log.errorf("Failed to deserialize stateJson for checkpoint id=%d: %s", checkpoint.getId(), e.getMessage());
            log.debugf(e, "Full stacktrace for deserialization failure of checkpoint id=%d", checkpoint.getId());
            return List.of();
        }
    }

    @Override
    public void markFailed(Long checkpointId, String reason) {
        withRetry(() -> {
            self.doMarkFailedId(checkpointId, reason);
            return null;
        }, "markFailed-id");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkFailedId(Long checkpointId, String reason) {
        em.clear();
        AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
        if (checkpoint != null) {
            doMarkFailed(checkpoint, reason);
        }
    }

    @Override
    public void markFailed(AgentCheckpoint checkpoint, String reason) {
        withRetry(() -> {
            self.doMarkFailedEntity(checkpoint, reason);
            return null;
        }, "markFailed-entity");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkFailedEntity(AgentCheckpoint checkpoint, String reason) {
        em.clear();
        AgentCheckpoint managed = em.find(AgentCheckpoint.class, checkpoint.getId());
        if (managed != null) {
            doMarkFailed(managed, reason);
        }
    }

    @Override
    public Optional<AgentCheckpoint> findLatestAwaitingApprovalForCron(String cronId) {
        String sessionPrefix = "cron-" + cronId + "-";

        TypedQuery<AgentCheckpoint> q = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId LIKE :sessionPrefix " +
                "  AND c.status = :status " +
                "ORDER BY c.updatedAt DESC",
                AgentCheckpoint.class
        );
        q.setParameter("sessionPrefix", sessionPrefix + "%");
        q.setParameter("status", CheckpointStatus.AWAITING_APPROVAL);

        q.setMaxResults(1);
        return q.getResultList().stream().findFirst();
    }

    private void doMarkFailed(AgentCheckpoint checkpoint, String reason) {
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setFailedAt(Instant.now());
        checkpoint.setFailReason(reason);
        
        String userId = (String) MDC.get(MdcKeys.USER_ID);
        auditService.record(checkpoint.getSessionId(), userId, checkpoint.getAgentRole(), SecurityEventType.CHECKPOINT_FAILED, "reason=" + reason);
        
        log.warnf("Checkpoint session=%s role=%s marked FAILED: %s", checkpoint.getSessionId(), checkpoint.getAgentRole(), reason);
    }

    @Override
    public void markCompleted(Long checkpointId) {
        withRetry(() -> {
            self.doMarkCompletedId(checkpointId);
            return null;
        }, "markCompleted");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCompletedId(Long checkpointId) {
        em.clear();
        AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
        if (checkpoint != null) {
            checkpoint.setStatus(CheckpointStatus.COMPLETED);
            checkpoint.setCompletedAt(Instant.now());
            
            String userId = (String) MDC.get(MdcKeys.USER_ID);
            auditService.record(checkpoint.getSessionId(), userId, checkpoint.getAgentRole(), SecurityEventType.CHECKPOINT_COMPLETED, null);
            
            log.infof("Checkpoint id=%d marked COMPLETED", checkpointId);
        }
    }

    @Override
    public AgentCheckpoint saveAndFinalize(String sessionId, String agentRole, List<ReasoningStep> steps, CheckpointStatus finalStatus) {
        return withRetry(() -> self.doSaveAndFinalize(sessionId, agentRole, steps, finalStatus), "saveAndFinalize");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public AgentCheckpoint doSaveAndFinalize(String sessionId, String agentRole, List<ReasoningStep> steps, CheckpointStatus finalStatus) {
        em.clear();
        String json = serializeSteps(steps);
        Optional<AgentCheckpoint> existing = findBySessionAndRole(sessionId, agentRole);

        AgentCheckpoint checkpoint;
        if (existing.isPresent()) {
            checkpoint = existing.get();
            checkpoint.setStateJson(json);
            checkpoint.setStatus(finalStatus);
            if (finalStatus == CheckpointStatus.COMPLETED) {
                checkpoint.setCompletedAt(Instant.now());
            } else if (finalStatus == CheckpointStatus.FAILED) {
                checkpoint.setFailedAt(Instant.now());
            }
            log.debugf("Updating checkpoint for finalization: session=%s, role=%s", sessionId, agentRole);
        } else {
            checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId(sessionId);
            checkpoint.setAgentRole(agentRole);
            checkpoint.setStateJson(json);
            checkpoint.setStatus(finalStatus); // Fix: Set status before persist
            if (finalStatus == CheckpointStatus.COMPLETED) {
                checkpoint.setCompletedAt(Instant.now());
            } else if (finalStatus == CheckpointStatus.FAILED) {
                checkpoint.setFailedAt(Instant.now());
            }
            em.persist(checkpoint);
            
            String userId = (String) MDC.get(MdcKeys.USER_ID);
            auditService.record(sessionId, userId, agentRole, SecurityEventType.CHECKPOINT_CREATED, null);
            log.infof("Created new checkpoint for finalization: session=%s, role=%s", sessionId, agentRole);
        }

        final Long cpId = checkpoint.getId();
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", cpId)
                .executeUpdate();

        for (int i = 0; i < steps.size(); i++) {
            ReasoningStep step = steps.get(i);
            ReasoningStepEntity entity = new ReasoningStepEntity();
            entity.setCheckpointId(cpId);
            entity.setSequenceNum(i);
            entity.setThoughtJson(serialize(step.thought()));
            entity.setAction(step.action());
            entity.setActionInputJson(serialize(step.actionInput()));
            entity.setObservation(step.observation());
            entity.setInputTokens(step.inputTokens());
            entity.setOutputTokens(step.outputTokens());
            em.persist(entity);
        }

        String userId = (String) MDC.get(MdcKeys.USER_ID);
        SecurityEventType eventType = finalStatus == CheckpointStatus.COMPLETED 
                ? SecurityEventType.CHECKPOINT_COMPLETED 
                : SecurityEventType.CHECKPOINT_FAILED;
        auditService.record(sessionId, userId, agentRole, eventType, null);

        log.infof("Checkpoint session=%s role=%s finalized with status=%s", sessionId, agentRole, finalStatus);
        return checkpoint;
    }

    @Override
    @Transactional
    public List<ReasoningStep> loadSteps(Long checkpointId) {
        TypedQuery<ReasoningStepEntity> q = em.createQuery(
                "SELECT r FROM ReasoningStepEntity r WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC",
                ReasoningStepEntity.class
        );
        q.setParameter("id", checkpointId);
        List<ReasoningStepEntity> entities = q.getResultList();

        return entities.stream()
                .map(this::mapToReasoningStep)
                .toList();
    }

    private ReasoningStep mapToReasoningStep(ReasoningStepEntity entity) {
        return new ReasoningStep(
                deserialize(entity.getThoughtJson(), String.class),
                entity.getAction(),
                deserialize(entity.getActionInputJson(), new TypeReference<Map<String, Object>>() {}),
                entity.getObservation(),
                entity.getInputTokens(),
                entity.getOutputTokens()
        );
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.errorf(e, "Failed to deserialize reasoning step component: %s", json);
            return null;
        }
    }

    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.errorf(e, "Failed to deserialize reasoning step component: %s", json);
            return null;
        }
    }

    private String serialize(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reasoning step component", e);
        }
    }

    private <T> T withRetry(Callable<T> task, String operation) {
        int maxRetries = 5;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastEx = e;
                if (isSqliteBusy(e)) {
                    // Exponential backoff with jitter prevents thundering herd:
                    // base * 2^attempt + random(0..50ms)
                    long delay = (BACKOFF_BASE_MS * (1L << attempt))
                            + ThreadLocalRandom.current().nextInt(50);
                    log.warnf("SQLite BUSY on %s attempt %d/%d — retrying in %dms",
                            operation, attempt, maxRetries, delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                } else {
                    throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                }
            }
        }
        throw new RuntimeException("Action failed after " + maxRetries + " retries for " + operation, lastEx);
    }

    private boolean isSqliteBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String canonicalName = t.getClass().getName();
            if (canonicalName.equals(LockAcquisitionException.class.getName()) ||
                canonicalName.equals("jakarta.persistence.OptimisticLockException") ||
                canonicalName.contains(SqliteErrorStrings.SQLITE_EXCEPTION)) {
                return true;
            }

            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains(SqliteErrorStrings.BUSY) || 
                    lower.contains(SqliteErrorStrings.DATABASE_LOCKED) || 
                    lower.contains(SqliteErrorStrings.SQLITE_BUSY) ||
                    lower.contains(SqliteErrorStrings.SNAPSHOT)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<AgentCheckpoint> findBySessionAndRole(String sessionId, String agentRole) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.agentRole = :role",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private String serializeSteps(List<ReasoningStep> steps) {
        try {
            return (steps == null || steps.isEmpty()) ? "[]" : objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reasoning steps", e);
        }
    }
}
