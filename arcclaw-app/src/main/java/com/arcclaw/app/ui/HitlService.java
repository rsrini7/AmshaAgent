package com.arcclaw.app.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ApproveReflex;
import com.arcclaw.core.reflex.DenyReflex;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service to manage Human-In-The-Loop approvals for the UI.
 */
@ApplicationScoped
public class HitlService {

    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;

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

    @Inject ApproveReflex approveReflex;
    @Inject DenyReflex denyReflex;

    public void resolve(String taskId, boolean approved, String reason) {
        Long id = Long.valueOf(taskId);
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
        if (cp == null) {
            throw new IllegalArgumentException("No pending approval found for ID: " + taskId);
        }
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
             throw new IllegalStateException("Already resolved for ID: " + taskId);
        }

        ChannelType chType = 
            ChannelType.fromString(cp.getChannelType())
                .orElse(ChannelType.TELEGRAM);

        ChannelUser user = new ChannelUser(
            cp.getChannelPlatformId(), cp.getChannelPlatformId(), "Operator", chType);
        
        MessageContext context = new MessageContext(
            cp.getChannelPlatformId(), user, null);
        context.setSessionId(cp.getSessionId());

        if (approved) {
            approveReflex.execute(cp.getSessionId(), cp.getChannelPlatformId(), cp.getChannelPlatformId(), "/approve", context);
        } else {
            String input = "/deny" + (reason != null && !reason.isBlank() ? " " + reason : "");
            denyReflex.execute(cp.getSessionId(), cp.getChannelPlatformId(), cp.getChannelPlatformId(), input, context);
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
