package com.arcclaw.core.state;

import com.arcclaw.core.agent.ReasoningStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

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

    @Override
    @Transactional
    public AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps) {
        String json = serializeSteps(steps);

        // Upsert: find existing or create new
        Optional<AgentCheckpoint> existing = findBySessionAndRole(sessionId, agentRole);

        AgentCheckpoint checkpoint;
        if (existing.isPresent()) {
            checkpoint = existing.get();
            checkpoint.stateJson = json;
            checkpoint.status = CheckpointStatus.IN_PROGRESS;
            checkpoint = em.merge(checkpoint);
            log.debugf("Updated checkpoint for session=%s, role=%s", sessionId, agentRole);
        } else {
            checkpoint = new AgentCheckpoint();
            checkpoint.sessionId = sessionId;
            checkpoint.agentRole = agentRole;
            checkpoint.stateJson = json;
            checkpoint.status = CheckpointStatus.IN_PROGRESS;
            em.persist(checkpoint);
            log.infof("Created checkpoint for session=%s, role=%s", sessionId, agentRole);
        }

        return checkpoint;
    }

    @Override
    @Transactional
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        if (checkpoint.id == null) {
            em.persist(checkpoint);
            return checkpoint;
        } else {
            return em.merge(checkpoint);
        }
    }

    @Override
    @Transactional
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return findBySessionAndRole(sessionId, agentRole);
    }

    @Override
    public List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint) {
        if (checkpoint.stateJson == null || checkpoint.stateJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(checkpoint.stateJson, STEPS_TYPE);
        } catch (JsonProcessingException e) {
            log.errorf(e, "Failed to deserialize stateJson for checkpoint id=%d", checkpoint.id);
            return List.of();
        }
    }

    @Override
    @Transactional
    public void markFailed(Long checkpointId, String reason) {
        AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
        if (checkpoint != null) {
            checkpoint.status = CheckpointStatus.FAILED;
            checkpoint.failedAt = Instant.now();
            checkpoint.failReason = reason;
            em.merge(checkpoint);
            log.warnf("Checkpoint id=%d marked FAILED: %s", checkpointId, reason);
        }
    }

    @Override
    @Transactional
    public void markCompleted(Long checkpointId) {
        AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
        if (checkpoint != null) {
            checkpoint.status = CheckpointStatus.COMPLETED;
            em.merge(checkpoint);
            log.infof("Checkpoint id=%d marked COMPLETED", checkpointId);
        }
    }

    private Optional<AgentCheckpoint> findBySessionAndRole(String sessionId, String agentRole) {
        var results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.agentRole = :role",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private String serializeSteps(List<ReasoningStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reasoning steps", e);
        }
    }
}
