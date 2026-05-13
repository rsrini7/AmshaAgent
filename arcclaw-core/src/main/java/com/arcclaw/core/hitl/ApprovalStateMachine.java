package com.arcclaw.core.hitl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalDecisionEvent;
import com.arcclaw.channel.api.hitl.ApprovalGateway;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.channel.api.hitl.ApprovalResult;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApprovalStateMachine implements ApprovalGateway {

    private static final Logger log = Logger.getLogger(ApprovalStateMachine.class);

    @Inject StatefulAgentStore agentStore;
    @Inject Event<ApprovalDecisionEvent> decisionEvents;
    @Inject CheckpointResumeHandler resumeHandler;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    // ✅ Split the write and the resume - write inside NOT_SUPPORTED,
    // resume after the write is committed
    @Override
    @Transactional(TxType.NOT_SUPPORTED)   // was @Transactional
    public ApprovalResult decide(String sessionId, String toolName,
                                 ApprovalDecision decision, String reason) {
        log.infof("[ASM] decide(session=%s, tool=%s, decision=%s, reason=%s)", sessionId, toolName, decision, reason);
        
        // 1. Load checkpoint - return NOT_FOUND if absent
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            log.warnf("[ASM] Checkpoint not found for session=%s, returning NOT_FOUND", sessionId);
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0, List.of());
        }
        AgentCheckpoint cp = cpOpt.get();
        log.debugf("[ASM] Loaded checkpoint id=%d status=%s for session=%s", cp.getId(), cp.getStatus(), sessionId);

        // 2. Verify AWAITING_APPROVAL state (findLatestAwaitingApproval guarantees this)
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            log.warnf("[ASM] Checkpoint session=%s has status=%s instead of AWAITING_APPROVAL, returning NOT_FOUND",
                    sessionId, cp.getStatus());
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0, List.of());
        }

        // 3. Check expiry via agentStore.isCheckpointExpired() -> EXPIRED if true
        if (agentStore.isCheckpointExpired(sessionId, hitlTimeoutHours)) {
            log.infof("[ASM] Checkpoint expired for session=%s (timeout=%dh), returning EXPIRED", sessionId, hitlTimeoutHours);
            return new ApprovalResult(ApprovalOutcome.EXPIRED, sessionId, toolName, 0, List.of());
        }

        List<String> allTools = BatchDecisionUtil.allToolNames(cp, objectMapper);
        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        log.debugf("[ASM] session=%s allTools=%s pendingTools=%s", sessionId, allTools, pendingTools);

        // 5. Record decision per tool
        ToolDecision decisionEnum = decision == ApprovalDecision.APPROVE ? ToolDecision.APPROVED : ToolDecision.DENIED;
        
        if ("all".equalsIgnoreCase(toolName)) {
            log.infof("[ASM] Approving all pending tools for session=%s: %s", sessionId, pendingTools);
            for (String pending : pendingTools) {
                agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, decisionEnum.name());
            }
        } else {
            if (!allTools.contains(toolName)) {
                log.warnf("[ASM] Tool '%s' not found in allTools=%s for session=%s", toolName, allTools, sessionId);
                return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0, List.of());
            }
            if (!pendingTools.contains(toolName)) {
                log.infof("[ASM] Tool '%s' already decided for session=%s, pendingTools=%s", toolName, sessionId, pendingTools);
                return ApprovalResult.alreadyDecided(sessionId, toolName);
            }
            agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, decisionEnum.name());
        }

        // 6. If all tools decided -> transition to APPROVED or REJECTED
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        log.infof("[ASM] session=%s allDecided=%s", sessionId, allDecided);
        
        int remaining = pendingTools.size() - 1;
        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        ApprovalOutcome outcome;
        if (allDecided) {
            // Reload fresh from DB — cp is stale (loaded before native SQL update)
            String freshJson = agentStore.findLatestAwaitingApproval(sessionId)
                .map(AgentCheckpoint::getBatchToolDecisions)
                .orElse(cp.getBatchToolDecisions());
            Map<String, ToolDecision> decisions;
            try {
                decisions = objectMapper.readValue(
                    freshJson, new TypeReference<Map<String, ToolDecision>>() {});
            } catch (Exception e) {
                log.warnf("[ASM] Failed to deserialize decisions for session=%s: %s", sessionId, e.getMessage());
                decisions = Map.of(toolName, decisionEnum);  // safe fallback
            }
            final Map<String, ToolDecision> finalDecisions = decisions;

            boolean anyApproved = allTools.stream()
                .anyMatch(t -> ToolDecision.APPROVED == finalDecisions.get(t));
            outcome = anyApproved ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED;
            log.infof("[ASM] All decided for session=%s: decisions=%s anyApproved=%s outcome=%s",
                    sessionId, finalDecisions, anyApproved, outcome);
                        
            String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
            resumeHandler.resumeAfterBatchDecision(
                sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
            log.infof("[ASM] Called resumeAfterBatchDecision for session=%s", sessionId);
        } else {
            outcome = ApprovalOutcome.PARTIAL_STILL_PENDING;
            log.infof("[ASM] session=%s still pending: %d tool(s) remaining", sessionId, remaining);
        }

        String channelPlatformId = cp.getChannelPlatformId();

        // 7. Fire CDI ApprovalDecisionEvent for channel observers
        decisionEvents.fire(new ApprovalDecisionEvent(sessionId, channelType, channelPlatformId, toolName, decision, outcome));
        log.debugf("[ASM] Fired ApprovalDecisionEvent for session=%s outcome=%s", sessionId, outcome);

        // 8. Return ApprovalResult with outcome + remainingPendingTools
        List<String> nextPending = "all".equalsIgnoreCase(toolName) 
            ? List.of() 
            : pendingTools.stream().filter(t -> !t.equals(toolName)).toList();
        return new ApprovalResult(outcome, sessionId, toolName, remaining, nextPending);
    }

    @Override
    public String resolveSessionIdPrefix(String prefix) {
        String resolved = agentStore.findLatestAwaitingApprovalBySessionContaining(prefix)
                .map(AgentCheckpoint::getSessionId)
                .orElse(prefix);
        if (!resolved.equals(prefix)) {
            log.infof("[ASM] Resolved session prefix '%s' -> '%s'", prefix, resolved);
        } else {
            log.warnf("[ASM] Could not resolve session prefix '%s', using as-is", prefix);
        }
        return resolved;
    }
}
