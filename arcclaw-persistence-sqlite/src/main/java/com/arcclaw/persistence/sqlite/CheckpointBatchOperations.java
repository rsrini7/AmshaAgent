package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.persistence.api.CheckpointDto;
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doAtomicUpdateBatchToolDecision(String sessionId, String agentRole,
                                                    String toolName, String decision) {
        log.infof("[BatchOps] Updating tool decision for session=%s role=%s tool=%s -> %s",
                sessionId, agentRole, toolName, decision);
        em.clear();
        List<AgentCheckpoint> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.agentRole = :role " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList();
        if (results.isEmpty()) {
            log.warnf("[BatchOps] No checkpoint found for session=%s role=%s", sessionId, agentRole);
            return false;
        }

        AgentCheckpoint cp = results.get(0);
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL || cp.getBatchToolDecisions() == null) {
            log.warnf("[BatchOps] Checkpoint session=%s not in AWAITING_APPROVAL or no batch decisions", sessionId);
            return false;
        }

        try {
            Map<String, ToolDecision> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            if (!map.containsKey(toolName)) {
                log.warnf("[BatchOps] Tool '%s' not found in batch for session %s", toolName, sessionId);
                return false;
            }
            map.put(toolName, ToolDecision.valueOf(decision));
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(map));
            log.infof("[BatchOps] Successfully updated decision for tool '%s'", toolName);
            return true;
        } catch (Exception e) {
            log.errorf("Failed to update batch tool decision for session=%s tool=%s: %s",
                       sessionId, toolName, e.getMessage());
            return false;
        }
    }

    /**
     * A16 - Stale Join Guard: merge batch decisions to prevent overwriting active decisions
     * from a concurrent update turn.
     */
    public void mergeBatchDecisions(AgentCheckpoint managed, CheckpointDto incoming) {
        log.debugf("[BatchOps] Merging batch decisions for session %s", managed.getSessionId());
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
                log.infof("[BatchOps] Stale join guard: merged %d active decisions for session %s",
                        managedMap.size(), managed.getSessionId());
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
