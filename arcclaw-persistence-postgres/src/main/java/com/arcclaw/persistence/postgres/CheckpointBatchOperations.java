package com.arcclaw.persistence.postgres;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.LogUtil;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointBatchOperations {

    private static final Logger log = Logger.getLogger(CheckpointBatchOperations.class);

    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean atomicUpdateToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        log.infof("[Postgres] Updating tool decision for session=%s role=%s tool=%s -> %s",
                sessionId, agentRole, toolName, decision);

        List<AgentCheckpointEntity> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.agentRole = :role " +
                "ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList();
        if (results.isEmpty()) {
            log.warnf("[Postgres] No checkpoint found for session=%s role=%s", sessionId, agentRole);
            return false;
        }

        AgentCheckpointEntity cp = results.get(0);
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL || cp.getBatchToolDecisions() == null) {
            log.warnf("[Postgres] Checkpoint session=%s not in AWAITING_APPROVAL or no batch decisions", sessionId);
            return false;
        }

        try {
            Map<String, ToolDecision> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            if (!map.containsKey(toolName)) {
                log.warnf("[Postgres] Tool '%s' not found in batch for session %s", toolName, sessionId);
                return false;
            }
            map.put(toolName, ToolDecision.valueOf(decision));
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(map));
            em.merge(cp);
            log.infof("[Postgres] Successfully updated decision for tool '%s'", toolName);
            return true;
        } catch (Exception e) {
            log.errorf("Failed to update batch tool decision for session=%s tool=%s: %s",
                       sessionId, toolName, LogUtil.rootMessage(e));
            return false;
        }
    }

    public boolean hasPendingApprovalForChat(String chatId) {
        if (chatId == null) return false;
        Long count = em.createQuery("SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.channelPlatformId = :chatId AND c.status = :status AND c.sessionId NOT LIKE 'cron-%'", Long.class)
                .setParameter("chatId", chatId)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .getSingleResult();
        return count > 0;
    }
}

