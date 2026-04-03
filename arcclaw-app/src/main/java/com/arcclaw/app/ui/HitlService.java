package com.arcclaw.app.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.loop.CheckpointApprovalService;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional.TxType;

/**
 * Service to manage Human-In-The-Loop approvals for the UI.
 */
@ApplicationScoped
public class HitlService {

    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;
    @Inject CheckpointApprovalService checkpointApprovalService;

    @Inject SessionHeartbeatTracker heartbeatTracker;

    private static final int MAX_LIMIT = 50;

    /**
     * Returns pending approvals scoped to the given userId.
     * <p>
     * F27/§85-A: The old unfiltered query exposed all users' pending approvals.
     * Now uses {@link SessionHeartbeatTracker#getSessionIdsByOwner(String)} to
     * restrict results to sessions owned by the requesting user only.
     *
     * @param userId  trusted user identity (from API key, never from LLM)
     * @param limit   max results to return (capped at {@value #MAX_LIMIT})
     */
    @Transactional
    public Map<String, PendingApproval> getPendingApprovals(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIMIT);

        List<String> ownedSessionIds = heartbeatTracker.getSessionIdsByOwner(userId);
        if (ownedSessionIds.isEmpty()) {
            return Map.of();
        }

        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status " +
                "AND c.sessionId IN (:sessionIds) " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("sessionIds", ownedSessionIds)
                .setMaxResults(effectiveLimit)
                .getResultList()
                .stream()
                .map(this::mapToPending)
                .collect(Collectors.toMap(PendingApproval::taskId, p -> p));
    }

    private PendingApproval mapToPending(AgentCheckpoint cp) {
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(
                cp.getPendingToolParametersJson(),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            params = Map.of();
        }
        return new PendingApproval(
            cp.getId().toString(),
            cp.getPendingToolName(),
            params,
            cp.getUpdatedAt().toInstant(ZoneOffset.UTC),
            cp.getAgentRole()
        );
    }

    @Transactional(TxType.NOT_SUPPORTED)   // ← was @Transactional
    public void resolve(String taskId, String toolName, boolean approved, String reason) {
        Long id = Long.valueOf(taskId);
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
        if (cp == null) {
            throw new IllegalArgumentException("No pending approval found for ID: " + taskId);
        }
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Already resolved for ID: " + taskId);
        }

        AgentCheckpoint resolvedCp;
        if (approved) {
            if (toolName != null && !toolName.isBlank())
                resolvedCp = checkpointApprovalService.approveTool(cp.getSessionId(), toolName, reason);
            else {
                List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
                if (pending.size() == 1)
                    resolvedCp = checkpointApprovalService.approveTool(cp.getSessionId(), pending.get(0), reason);
                else
                    resolvedCp = checkpointApprovalService.approveAll(cp.getSessionId(), reason);
            }
        } else {
            if (toolName != null && !toolName.isBlank())
                resolvedCp = checkpointApprovalService.denyTool(cp.getSessionId(), toolName, reason);
            else {
                List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
                if (pending.size() == 1)
                    resolvedCp = checkpointApprovalService.denyTool(cp.getSessionId(), pending.get(0), reason);
                else
                    resolvedCp = checkpointApprovalService.denyAll(cp.getSessionId(), reason);
            }
        }

        // mirrors DenyReflex/ApproveReflex — triggers AgentLoop resume/denial path
        if (resolvedCp != null) {
            checkpointApprovalService.triggerResume(resolvedCp);
        }
    }

    public record PendingApproval(
            String taskId,
            String toolName,
            Map<String, Object> params,
            Instant requestedAt,
            String agentRole
    ) {}
}
