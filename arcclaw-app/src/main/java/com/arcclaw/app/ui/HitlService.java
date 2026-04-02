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
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service to manage Human-In-The-Loop approvals for the UI.
 */
@ApplicationScoped
public class HitlService {

    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;

    @Transactional
    public Map<String, PendingApproval> getPendingApprovals() {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
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

    @Inject CheckpointApprovalService checkpointApprovalService;

    @Transactional
    public void resolve(String taskId, String toolName, boolean approved, String reason) {
        Long id = Long.valueOf(taskId);
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
        if (cp == null) {
            throw new IllegalArgumentException("No pending approval found for ID: " + taskId);
        }
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Already resolved for ID: " + taskId);
        }

        if (approved) {
            if (toolName != null && !toolName.isBlank()) {
                checkpointApprovalService.approveTool(cp.getSessionId(), toolName, reason);
            } else {
                List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
                if (pending.size() == 1) {
                    checkpointApprovalService.approveTool(cp.getSessionId(), pending.get(0), reason);
                } else {
                    checkpointApprovalService.approveAll(cp.getSessionId(), reason);
                }
            }
        } else {
            if (toolName != null && !toolName.isBlank()) {
                checkpointApprovalService.denyTool(cp.getSessionId(), toolName, reason);
            } else {
                List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
                if (pending.size() == 1) {
                    checkpointApprovalService.denyTool(cp.getSessionId(), pending.get(0), reason);
                } else {
                    checkpointApprovalService.denyAll(cp.getSessionId(), reason);
                }
            }
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
