package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.sqlite.entity.AgentCheckpointEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Handles batch-specific operations for checkpoints, including tool decision updates
 * and staleness protection during joins.
 */
@ApplicationScoped
public class CheckpointBatchOperations {

    private static final Logger log = Logger.getLogger(CheckpointBatchOperations.class);

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Inject
    public CheckpointBatchOperations(EntityManager em,
                                    ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    protected CheckpointBatchOperations() {
        this(null, null);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public boolean doAtomicUpdateBatchToolDecision(String sessionId, String agentRole,
                                                    String toolName, String decision) {
        log.infof("[BatchOps] Updating tool decision for session=%s tool=%s -> %s",
                sessionId, toolName, decision);

        // Targeted SELECT to avoid loading the full entity graph
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT batch_tool_decisions, status FROM agent_checkpoint " +
                "WHERE session_id = :sid AND agent_role = :role " +
                "ORDER BY checkpoint_id DESC")
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList();

        if (rows.isEmpty()) {
            log.warnf("[BatchOps] No checkpoint found for session=%s", sessionId);
            return false;
        }

        String rawJson = (String) rows.get(0)[0];
        String status = (String) rows.get(0)[1];

        if (rawJson == null) {
            log.warnf("[BatchOps] batchToolDecisions is null for session=%s", sessionId);
            return false;
        }
        if (!CheckpointStatus.AWAITING_APPROVAL.name().equals(status)) {
            log.warnf("[BatchOps] Checkpoint status is '%s' instead of %s for session=%s", status, CheckpointStatus.AWAITING_APPROVAL, sessionId);
            return false;
        }

        try {
            Map<String, ToolDecision> map = objectMapper.readValue(
                rawJson, new TypeReference<Map<String, ToolDecision>>() {});
            
            if (!map.containsKey(toolName)) {
                log.warnf("[BatchOps] Tool '%s' not in batch for session %s. Available keys: %s", toolName, sessionId, map.keySet());
                return false;
            }

            map.put(toolName, ToolDecision.valueOf(decision));
            String updatedJson = objectMapper.writeValueAsString(map);

            // Targeted UPDATE to minimize lock duration
            int updated = em.createNativeQuery(
                    "UPDATE agent_checkpoint SET batch_tool_decisions = :json, updated_at = :now " +
                    "WHERE session_id = :sid AND agent_role = :role AND status = :status")
                    .setParameter("json", updatedJson)
                    .setParameter("now", Instant.now())
                    .setParameter("sid", sessionId)
                    .setParameter("role", agentRole)
                    .setParameter("status", CheckpointStatus.AWAITING_APPROVAL.name())
                    .executeUpdate();

            if (updated > 0) {
                log.infof("[BatchOps] Successfully updated decision for tool '%s'", toolName);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.errorf("[BatchOps] Failed to update batch tool decision for session=%s tool=%s: %s", sessionId, toolName, e.getMessage());
            log.debugf(e, "[BatchOps] Exception detail for session=%s tool=%s", sessionId, toolName);
            return false;
        }
    }

    /**
     * A16 - Stale Join Guard: merge batch decisions to prevent overwriting active decisions
     * from a concurrent update turn.
     */
    public void mergeBatchDecisions(AgentCheckpointEntity managed, CheckpointDto incoming) {
        log.debugf("[BatchOps] Merging batch decisions for session %s", managed.getSessionId());
        if (incoming.batchToolDecisions() == null) {
            log.debugf("[BatchOps] Incoming batchToolDecisions is null for session %s, skipping merge", managed.getSessionId());
            return;
        }
        if (managed.getBatchToolDecisions() == null) {
            log.debugf("[BatchOps] Managed batchToolDecisions is null for session %s, accepting incoming", managed.getSessionId());
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

            log.debugf("[BatchOps] Merge: managed=%s incoming=%s for session %s", managedMap, incomingMap, managed.getSessionId());

            boolean changed = false;
            for (Map.Entry<String, ToolDecision> entry : managedMap.entrySet()) {
                String tool = entry.getKey();
                ToolDecision managedDecision = entry.getValue();
                ToolDecision incomingDecision = incomingMap.get(tool);

                if (ToolDecision.PENDING != managedDecision
                        && ToolDecision.PENDING == incomingDecision) {
                    log.debugf("[BatchOps] Merge: preserving managed decision %s=%s over incoming PENDING for session %s",
                            tool, managedDecision, managed.getSessionId());
                    incomingMap.put(tool, managedDecision);
                    changed = true;
                }
            }

            if (changed) {
                String mergedJson = objectMapper.writeValueAsString(incomingMap);
                log.infof("[BatchOps] Stale join guard: merged %d active decisions for session %s -> %s",
                        managedMap.size(), managed.getSessionId(), mergedJson);
                managed.setBatchToolDecisions(mergedJson);
            } else {
                log.debugf("[BatchOps] Merge: no conflicts, accepting incoming for session %s", managed.getSessionId());
                managed.setBatchToolDecisions(incoming.batchToolDecisions());
            }

        } catch (Exception e) {
            log.warnf("Failed to merge batch decisions for session %s: %s", 
                    managed.getSessionId(), e.getMessage());
            managed.setBatchToolDecisions(incoming.batchToolDecisions());
        }
    }
}

