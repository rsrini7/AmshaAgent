package com.arcclaw.core.state;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.persistence.SqliteRetryTemplate;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.util.MdcKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

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
    private static final TypeReference<List<ReasoningStep>> STEPS_TYPE = new TypeReference<>() {};

    /**
     * In-memory map of sessionIds to the start time of a resume operation.
     * Prevents double-resume from concurrent callbacks.
     * §7.29 — NOT persisted; cleared on session termination.
     * F7: Stores Instant to allow TTL-based cleanup of orphaned markers.
     */
    private final ConcurrentHashMap<String, Instant>
        resumingBatches = new ConcurrentHashMap<>();

    @Inject ObjectMapper objectMapper;
    @Inject EntityManager em;
    @Inject AuditService auditService;
    @Inject SqliteRetryTemplate retryTemplate;
    @Inject StatefulAgentStoreImpl self;

    @Override
    public AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps) {
        return withRetry(() -> self.doSaveSteps(sessionId, agentRole, steps), "save-upsert");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public AgentCheckpoint doSaveSteps(String sessionId, String agentRole,
                                       List<ReasoningStep> steps) {
        em.clear();
        String json = serializeSteps(steps);

        Optional<AgentCheckpoint> existing = findBySessionAndRole(sessionId, agentRole);

        AgentCheckpoint checkpoint;
        AutonomyLevel autonomy = WorkspaceContext.autonomy();

        if (existing.isPresent()) {
            checkpoint = existing.get();
            checkpoint.setStateJson(json);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            checkpoint.setAutonomyLevel(autonomy);
            checkpoint.setIterationsUsed(steps.size());
            log.debugf("Updated checkpoint for session=%s, role=%s, autonomy=%s, used=%d",
                       sessionId, agentRole, autonomy, steps.size());
        } else {
            checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId(sessionId);
            checkpoint.setAgentRole(agentRole);
            checkpoint.setStateJson(json);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            checkpoint.setAutonomyLevel(autonomy);
            checkpoint.setIterationsUsed(steps.size());
            em.persist(checkpoint);

            String userId = (String) MDC.get(MdcKeys.USER_ID);
            auditService.record(sessionId, userId, agentRole,
                SecurityEventType.CHECKPOINT_CREATED, "autonomy=" + autonomy);

            log.infof("Created checkpoint for session=%s, role=%s, autonomy=%s",
                      sessionId, agentRole, autonomy);
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
            managed.setAutonomyLevel(checkpoint.getAutonomyLevel());
            managed.setOriginalGoal(checkpoint.getOriginalGoal());

            // A16 — Stale Join Guard: merge batch decisions to prevent overwriting active decisions
            if (checkpoint.getStatus() == CheckpointStatus.AWAITING_APPROVAL 
                    && managed.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                mergeBatchDecisions(managed, checkpoint);
            } else {
                managed.setBatchToolDecisions(checkpoint.getBatchToolDecisions());
            }

            managed.setPreExecutedResults(checkpoint.getPreExecutedResults());
            managed.setIterationsUsed(checkpoint.getIterationsUsed());
            // Fix 2: updatedAt must propagate so findStaleAwaitingApproval cutoff works.
            managed.setUpdatedAt(checkpoint.getUpdatedAt());
            return managed;
        }
    }

    private void mergeBatchDecisions(AgentCheckpoint managed, AgentCheckpoint incoming) {
        if (incoming.getBatchToolDecisions() == null) return;
        if (managed.getBatchToolDecisions() == null) {
            managed.setBatchToolDecisions(incoming.getBatchToolDecisions());
            return;
        }
        try {
            Map<String, String> managedMap = objectMapper.readValue(
                managed.getBatchToolDecisions(),
                new TypeReference<Map<String, String>>() {});
            Map<String, String> incomingMap = objectMapper.readValue(
                incoming.getBatchToolDecisions(),
                new TypeReference<Map<String, String>>() {});

            boolean changed = false;
            for (Map.Entry<String, String> entry : managedMap.entrySet()) {
                String tool = entry.getKey();
                String managedDecision = entry.getValue();
                String incomingDecision = incomingMap.get(tool);

                // If managed has a real decision, and incoming is still pending, keep the decision
                if (!AgentSentinels.TOOL_DECISION_PENDING.equals(managedDecision)
                        && AgentSentinels.TOOL_DECISION_PENDING.equals(incomingDecision)) {
                    incomingMap.put(tool, managedDecision);
                    changed = true;
                }
            }

            if (changed) {
                managed.setBatchToolDecisions(objectMapper.writeValueAsString(incomingMap));
            } else {
                managed.setBatchToolDecisions(incoming.getBatchToolDecisions());
            }
        } catch (Exception e) {
            log.warnf("Failed to merge batch tool decisions for cp id=%d: %s",
                managed.getId(), e.getMessage());
            managed.setBatchToolDecisions(incoming.getBatchToolDecisions());
        }
    }

    @Override
    @Transactional
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        em.clear();
        return findBySessionAndRole(sessionId, agentRole);
    }

    // ── NEW: findLatestAwaitingApproval ───────────────────────────────────────

    /**
     * Returns the most-recent checkpoint for this session that is in
     * {@link CheckpointStatus#AWAITING_APPROVAL}, regardless of agentRole.
     * <p>
     * This replaces the AgentRoleEnum enum loop in {@code ApproveReflex} to fix
     * stale-row ambiguity: when multiple rows exist for the same
     * (sessionId, agentRole), the old loop called load() which could return the
     * oldest (COMPLETED) row, shadowing a newer AWAITING_APPROVAL checkpoint and
     * causing "/approve → No pending tool executions require approval" silently.
     */
    @Override
    @Transactional
    public Optional<AgentCheckpoint> findLatestAwaitingApproval(String sessionId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.status = :status " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
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
            log.errorf("Failed to deserialize stateJson for checkpoint id=%d: %s",
                       checkpoint.getId(), e.getMessage());
            log.debugf(e, "Full stacktrace for deserialization failure of checkpoint id=%d",
                       checkpoint.getId());
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
                "ORDER BY c.id DESC",
                AgentCheckpoint.class);
        q.setParameter("sessionPrefix", sessionPrefix + "%");
        q.setParameter("status", CheckpointStatus.AWAITING_APPROVAL);
        q.setMaxResults(1);
        return q.getResultList().stream().filter(Objects::nonNull).findFirst();
    }

    private void doMarkFailed(AgentCheckpoint checkpoint, String reason) {
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setFailedAt(Instant.now());
        checkpoint.setFailReason(reason);

        String userId = (String) MDC.get(MdcKeys.USER_ID);
        auditService.record(checkpoint.getSessionId(), userId, checkpoint.getAgentRole(),
            SecurityEventType.CHECKPOINT_FAILED, "reason=" + reason);

        log.warnf("Checkpoint session=%s role=%s marked FAILED: %s",
                  checkpoint.getSessionId(), checkpoint.getAgentRole(), reason);
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
            auditService.record(checkpoint.getSessionId(), userId, checkpoint.getAgentRole(),
                SecurityEventType.CHECKPOINT_COMPLETED, null);

            log.infof("Checkpoint id=%d marked COMPLETED", checkpointId);
        }
    }

    @Override
    public AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
            List<ReasoningStep> steps, CheckpointStatus finalStatus) {
        return withRetry(
            () -> self.doSaveAndFinalize(sessionId, agentRole, steps, finalStatus),
            "saveAndFinalize");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public AgentCheckpoint doSaveAndFinalize(String sessionId, String agentRole,
            List<ReasoningStep> steps, CheckpointStatus finalStatus) {
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
            log.debugf("Updating checkpoint for finalization: session=%s, role=%s",
                       sessionId, agentRole);
        } else {
            checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId(sessionId);
            checkpoint.setAgentRole(agentRole);
            checkpoint.setStateJson(json);
            checkpoint.setStatus(finalStatus);
            if (finalStatus == CheckpointStatus.COMPLETED) {
                checkpoint.setCompletedAt(Instant.now());
            } else if (finalStatus == CheckpointStatus.FAILED) {
                checkpoint.setFailedAt(Instant.now());
            }
            em.persist(checkpoint);

            String userId = (String) MDC.get(MdcKeys.USER_ID);
            auditService.record(sessionId, userId, agentRole,
                SecurityEventType.CHECKPOINT_CREATED, null);
            log.infof("Created new checkpoint for finalization: session=%s, role=%s",
                      sessionId, agentRole);
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

        log.infof("Checkpoint session=%s role=%s finalized with status=%s",
                  sessionId, agentRole, finalStatus);
        return checkpoint;
    }

    @Override
    @Transactional
    public List<ReasoningStep> loadSteps(Long checkpointId) {
        TypedQuery<ReasoningStepEntity> q = em.createQuery(
                "SELECT r FROM ReasoningStepEntity r " +
                "WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC",
                ReasoningStepEntity.class);
        q.setParameter("id", checkpointId);
        return q.getResultList().stream()
                .map(this::mapToReasoningStep)
                .toList();
    }

    private ReasoningStep mapToReasoningStep(ReasoningStepEntity entity) {
        return ReasoningStep.of(
                deserialize(entity.getThoughtJson(), String.class),
                entity.getAction(),
                deserialize(entity.getActionInputJson(),
                    new TypeReference<Map<String, Object>>() {}),
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
            log.errorf("Failed to deserialize reasoning step component (Exception: %s): %s",
                       e.getMessage(), json);
            log.debug("Full deserialization failure stack trace:", e);
            return null;
        }
    }

    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.errorf("Failed to deserialize reasoning step component (Exception: %s): %s",
                       e.getMessage(), json);
            log.debug("Full deserialization failure stack trace:", e);
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
        try {
            return retryTemplate.execute(operation, 5, 50, task);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re)
                ? re
                : new RuntimeException("Action failed after retries for " + operation, e);
        }
    }

    /**
     * Finds the most-recent non-ARCHIVED checkpoint for a (sessionId, agentRole) pair.
     * <p>
     * FIX: Added {@code ORDER BY c.id DESC} so the newest row is always returned.
     * Previously, LIMIT 1 with no ordering caused SQLite to return the oldest row
     * (lowest checkpoint_id = first inserted = often COMPLETED), which shadowed
     * any newer AWAITING_APPROVAL row and broke the "/approve" flow.
     * <p>
     * Also excludes {@link CheckpointStatus#ARCHIVED} rows so that fresh turns
     * archived by AgentLoop never interfere with the live checkpoint.
     */
    private Optional<AgentCheckpoint> findBySessionAndRole(String sessionId, String agentRole) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.agentRole = :role " +
                "  AND c.status <> :archived " +  // ← exclude archived rows
                "ORDER BY c.id DESC",             // ← newest first — was missing
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private String serializeSteps(List<ReasoningStep> steps) {
        try {
            return (steps == null || steps.isEmpty())
                ? "[]"
                : objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reasoning steps", e);
        }
    }

    @Override
    @Transactional
    public Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId) {
        TypedQuery<AutonomyLevel> q = em.createQuery(
                "SELECT c.autonomyLevel FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid ORDER BY c.id DESC",
                AutonomyLevel.class);
        q.setParameter("sid", sessionId);
        q.setMaxResults(1);
        return q.getResultList().stream().filter(Objects::nonNull).findFirst();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void deleteCheckpoint(String sessionId, String role) {
        em.createQuery(
            "DELETE FROM AgentCheckpoint c " +
            "WHERE c.sessionId = :sid AND c.agentRole = :role")
        .setParameter("sid", sessionId)
        .setParameter("role", role)
        .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean updateBatchToolDecision(String sessionId, String agentRole,
                                           String toolName, String decision) {
        return withRetry(
            () -> self.doUpdateBatchToolDecision(sessionId, agentRole, toolName, decision),
            "updateBatchToolDecision");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doUpdateBatchToolDecision(String sessionId, String agentRole,
                                             String toolName, String decision) {
        em.clear();
        Optional<AgentCheckpoint> cpOpt = load(sessionId, agentRole);
        if (cpOpt.isEmpty()) return false;

        AgentCheckpoint cp = cpOpt.get();
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL
                || !cp.hasBatchToolDecisions()) {
            return false;
        }

        try {
            Map<String, String> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<Map<String, String>>() {});
            if (!map.containsKey(toolName)) return false;
            map.put(toolName, decision);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(map));
            em.merge(cp);
            return true;
        } catch (Exception e) {
            log.errorf("Failed to update batch tool decision: %s",
               e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean isAllBatchToolsDecided(String sessionId, String agentRole) {
        return withRetry(() -> self.doIsAllBatchToolsDecided(sessionId, agentRole), "isAllBatchDecided");
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doIsAllBatchToolsDecided(String sessionId, String agentRole) {
        em.clear();
        Optional<AgentCheckpoint> cpOpt = load(sessionId, agentRole);
        if (cpOpt.isEmpty()) return false;

        AgentCheckpoint cp = cpOpt.get();
        if (!cp.hasBatchToolDecisions()) return false;

        try {
            Map<String, String> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<Map<String, String>>() {});
            return map.values().stream()
                .noneMatch(val -> AgentSentinels.TOOL_DECISION_PENDING.equals(val));
        } catch (Exception e) {
            log.errorf("Failed to check if batch decided: %s",
               e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    @Transactional
    public List<AgentCheckpoint> findStaleAwaitingApproval(Instant cutoff) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.status = :status " +
                "  AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .getResultList();
    }

    /**
     * Attempts to acquire the local JVM resume lock for a session.
     * <p>
     * <strong>D1.1/F7 Tradeoff Documented:</strong> This is a JVM-local
     * ConcurrentHashMap. It protects against concurrent duplicate resumes hitting
     * the SAME pod (e.g., impatient user clicking 'Approve' twice). It DOES NOT
     * protect against cross-pod races during rolling restarts or if a load balancer
     * sprays two approvals to different pods. Moving to a persistent lock (e.g. 
     * Redis or row-level SQLite lock) is required for full distributed exactly-once
     * resuming, if ever strictly required by an orchestrator setup.
     */
    @Override
    public boolean markBatchResuming(String sessionId) {
        // F7: Use Instant.now() instead of Boolean.TRUE for TTL tracking.
        return resumingBatches.putIfAbsent(sessionId, Instant.now()) == null;
    }

    @Override
    public boolean isBatchResuming(String sessionId) {
        Instant start = resumingBatches.get(sessionId);
        if (start == null) return false;

        // F7: TTL Guard. If the entry is older than 30 minutes, assume the 
        // thread died or finally block failed to run (e.g. OOM/SIGKILL previously).
        if (Duration.between(start, Instant.now()).toMinutes() > 30) {
            log.warnf("Found orphaned resume marker for session %s (started %s) — ignoring.", sessionId, start);
            resumingBatches.remove(sessionId);
            return false;
        }
        return true;
    }

    @Override
    public void clearBatchResuming(String sessionId) {
        resumingBatches.remove(sessionId);
    }

    /**
     * §7.35 — Returns true if the latest AWAITING_APPROVAL checkpoint for this
     * session has been waiting longer than {@code timeoutHours}, or if no such
     * checkpoint exists.
     */
    @Override
    @Transactional
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        Optional<AgentCheckpoint> cpOpt = findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            return true; // no checkpoint = nothing to approve = treat as expired
        }
        AgentCheckpoint cp = cpOpt.get();
        if (cp.getUpdatedAt() == null) {
            return true; // no timestamp = cannot verify freshness = treat as expired
        }
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                Instant.now().minus(timeoutHours, ChronoUnit.HOURS),
                ZoneOffset.UTC);
        return cp.getUpdatedAt().isBefore(cutoff);
    }
}
