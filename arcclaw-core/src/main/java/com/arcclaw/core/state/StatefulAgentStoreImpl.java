package com.arcclaw.core.state;

import com.arcclaw.core.agent.ReasoningStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    private static final java.util.Random JITTER = new java.util.Random();

    @Override
    public AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps) {
        return withRetry(() -> {
            return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
                String json = serializeSteps(steps);
                Optional<AgentCheckpoint> existing = findBySessionAndRole(sessionId, agentRole);

                AgentCheckpoint checkpoint;
                if (existing.isPresent()) {
                    checkpoint = existing.get();
                    checkpoint.setStateJson(json);
                    checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
                    checkpoint = em.merge(checkpoint);
                    log.debugf("Updated checkpoint for session=%s, role=%s", sessionId, agentRole);
                } else {
                    checkpoint = new AgentCheckpoint();
                    checkpoint.setSessionId(sessionId);
                    checkpoint.setAgentRole(agentRole);
                    checkpoint.setStateJson(json);
                    checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
                    em.persist(checkpoint);
                    
                    String userId = (String) MDC.get("userId");
                    auditService.record(sessionId, userId, agentRole, SecurityEventType.CHECKPOINT_CREATED, null);
                    
                    log.infof("Created checkpoint for session=%s, role=%s", sessionId, agentRole);
                }
                return checkpoint;
            });
        }, "save-upsert");
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        return withRetry(() -> {
            return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
                if (checkpoint.getId() == null) {
                    em.persist(checkpoint);
                    return checkpoint;
                } else {
                    return em.merge(checkpoint);
                }
            });
        }, "save-entity");
    }

    @Override
    @Transactional
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return findBySessionAndRole(sessionId, agentRole);
    }

    @Override
    public List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint) {
        if (checkpoint.getStateJson() == null || checkpoint.getStateJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(checkpoint.getStateJson(), STEPS_TYPE);
        } catch (JsonProcessingException e) {
            log.errorf(e, "Failed to deserialize stateJson for checkpoint id=%d", checkpoint.getId());
            return List.of();
        }
    }

    @Override
    public void markFailed(Long checkpointId, String reason) {
        withRetry(() -> {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
                if (checkpoint != null) {
                    doMarkFailed(checkpoint, reason);
                }
            });
            return null;
        }, "markFailed-id");
    }

    @Override
    public void markFailed(AgentCheckpoint checkpoint, String reason) {
        withRetry(() -> {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                doMarkFailed(checkpoint, reason);
            });
            return null;
        }, "markFailed-entity");
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
        em.merge(checkpoint);
        
        String userId = (String) MDC.get("userId");
        auditService.record(checkpoint.getSessionId(), userId, checkpoint.getAgentRole(), SecurityEventType.CHECKPOINT_FAILED, "reason=" + reason);
        
        log.warnf("Checkpoint session=%s role=%s marked FAILED: %s", checkpoint.getSessionId(), checkpoint.getAgentRole(), reason);
    }

    @Override
    public void markCompleted(Long checkpointId) {
        withRetry(() -> {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
                if (checkpoint != null) {
                    checkpoint.setStatus(CheckpointStatus.COMPLETED);
                    em.merge(checkpoint);
                    
                    String userId = (String) MDC.get("userId");
                    auditService.record(checkpoint.getSessionId(), userId, checkpoint.getAgentRole(), SecurityEventType.CHECKPOINT_COMPLETED, null);
                    
                    log.infof("Checkpoint id=%d marked COMPLETED", checkpointId);
                }
            });
            return null;
        }, "markCompleted");
    }

    private <T> T withRetry(java.util.concurrent.Callable<T> task, String operation) {
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
                            + JITTER.nextInt(50);
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
            if (canonicalName.equals("org.hibernate.exception.LockAcquisitionException") ||
                canonicalName.equals("jakarta.persistence.OptimisticLockException") ||
                canonicalName.contains("SQLiteException")) {
                return true;
            }

            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("busy") || 
                    lower.contains("database is locked") || 
                    lower.contains("sqlite_busy") ||
                    lower.contains("snapshot")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<AgentCheckpoint> findBySessionAndRole(String sessionId, String agentRole) {
        var results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.agentRole = :role " +
                "ORDER BY c.updatedAt DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private String serializeSteps(List<ReasoningStep> steps) {
        try {
            return (steps == null || steps.isEmpty()) ? "[]" : objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reasoning steps", e);
        }
    }
}
