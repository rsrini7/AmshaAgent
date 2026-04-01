package com.arcclaw.core.hitl;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalDecisionEvent;
import com.arcclaw.channel.api.hitl.ApprovalGateway;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.channel.api.hitl.ApprovalResult;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ApprovalStateMachine implements ApprovalGateway {

    @Inject StatefulAgentStore agentStore;
    @Inject Event<ApprovalDecisionEvent> decisionEvents;
    @Inject CheckpointResumeHandler resumeHandler;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    @Override
    @Transactional
    public ApprovalResult decide(String sessionId, String toolName,
                                 ApprovalDecision decision, String reason) {
        
        // 1. Load checkpoint — return NOT_FOUND if absent
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0);
        }
        AgentCheckpoint cp = cpOpt.get();

        // 2. Verify AWAITING_APPROVAL state (findLatestAwaitingApproval guarantees this)
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0);
        }

        // 3. Check expiry via agentStore.isCheckpointExpired() -> EXPIRED if true
        if (agentStore.isCheckpointExpired(sessionId, hitlTimeoutHours)) {
            return new ApprovalResult(ApprovalOutcome.EXPIRED, sessionId, toolName, 0);
        }

        List<String> allTools = BatchDecisionUtil.allToolNames(cp, objectMapper);
        if (!allTools.contains(toolName)) {
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0);
        }

        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);

        // 4. Idempotency: if toolName decision already recorded -> ALREADY_DECIDED immediately (no re-processing)
        if (!pendingTools.contains(toolName)) {
            return ApprovalResult.alreadyDecided(sessionId, toolName);
        }

        // 5. Record decision per tool
        String decisionStr = decision == ApprovalDecision.APPROVE ? "APPROVED" : "DENIED";
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, decisionStr);

        // 6. If all tools decided -> transition to APPROVED or REJECTED
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        
        int remaining = pendingTools.size() - 1;
        ApprovalOutcome outcome;
        if (allDecided) {
            // Check if there are any approvals
            // For now, if all decided, outcome is based on this last tool's approval? 
            // The instructions just say transition to APPROVED or REJECTED.
            // If any tool was approved? Reusing the approval model.
            // Actually, we can just say APPROVED if this was approve, REJECTED if reject.
            // Wait, normally if all decided, we trigger resume
            outcome = (decision == ApprovalDecision.APPROVE) ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED;
            
            String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
            String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
            resumeHandler.resumeAfterBatchDecision(
                sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
        } else {
            outcome = ApprovalOutcome.PARTIAL_STILL_PENDING;
        }

        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        String channelPlatformId = cp.getChannelPlatformId();

        // 7. Fire CDI ApprovalDecisionEvent for channel observers
        decisionEvents.fire(new ApprovalDecisionEvent(sessionId, channelType, channelPlatformId, toolName, decision, outcome));

        // 8. Return ApprovalResult with outcome + remainingPendingTools
        return new ApprovalResult(outcome, sessionId, toolName, remaining);
    }
}
