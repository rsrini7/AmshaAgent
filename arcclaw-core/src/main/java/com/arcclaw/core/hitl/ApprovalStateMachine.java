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
        String effectiveTool = resolveByPrefix(toolName, allTools, sessionId);
        if (effectiveTool == null) {
            log.warnf("[ASM] Tool '%s' not found in allTools=%s for session=%s", toolName, allTools, sessionId);
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0, List.of());
        }
        if (!effectiveTool.equals(toolName)) {
            log.debugf("[ASM] Prefix-matched truncated tool '%s' -> '%s' for session=%s", toolName, effectiveTool, sessionId);
        }

        if ("all".equalsIgnoreCase(effectiveTool)) {
            log.infof("[ASM] Approving all pending tools for session=%s: %s", sessionId, pendingTools);
            for (String pending : pendingTools) {
                agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, decisionEnum.name());
            }
        } else {
            if (!pendingTools.contains(effectiveTool)) {
                log.infof("[ASM] Tool '%s' already decided for session=%s, pendingTools=%s", effectiveTool, sessionId, pendingTools);
                return ApprovalResult.alreadyDecided(sessionId, effectiveTool);
            }
            agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), effectiveTool, decisionEnum.name());
        }

        // 6. Resolve final outcome — all decided or still pending
        var result = resolveOutcome(sessionId, cp, effectiveTool, decisionEnum, allTools, pendingTools);

        // 7. Fire CDI ApprovalDecisionEvent for channel observers
        decisionEvents.fire(new ApprovalDecisionEvent(sessionId, result.channelType(), cp.getChannelPlatformId(),
            effectiveTool, decision, result.outcome()));
        log.debugf("[ASM] Fired ApprovalDecisionEvent for session=%s outcome=%s", sessionId, result.outcome());

        // 8. Return ApprovalResult with outcome + remainingPendingTools
        return new ApprovalResult(result.outcome(), sessionId, effectiveTool,
            result.remaining(), result.nextPending());
    }

    private record OutcomeResult(ApprovalOutcome outcome, String channelType, int remaining, List<String> nextPending) {}

    private OutcomeResult resolveOutcome(String sessionId, AgentCheckpoint cp, String effectiveTool,
                                          ToolDecision decisionEnum, List<String> allTools, List<String> pendingTools) {
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        log.infof("[ASM] session=%s allDecided=%s", sessionId, allDecided);
        int remaining = pendingTools.size() - 1;
        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        if (allDecided) {
            String freshJson = agentStore.findLatestAwaitingApproval(sessionId)
                .map(AgentCheckpoint::getBatchToolDecisions)
                .orElse(cp.getBatchToolDecisions());
            Map<String, ToolDecision> decisions = readDecisions(freshJson, effectiveTool, decisionEnum, sessionId);
            boolean anyApproved = allTools.stream()
                .anyMatch(t -> ToolDecision.APPROVED == decisions.get(t));
            ApprovalOutcome outcome = anyApproved ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED;
            log.infof("[ASM] All decided for session=%s: decisions=%s anyApproved=%s outcome=%s",
                    sessionId, decisions, anyApproved, outcome);
            String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
            resumeHandler.resumeAfterBatchDecision(
                sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
            log.infof("[ASM] Called resumeAfterBatchDecision for session=%s", sessionId);
            return new OutcomeResult(outcome, channelType, 0, List.of());
        }
        log.infof("[ASM] session=%s still pending: %d tool(s) remaining", sessionId, remaining);
        List<String> nextPending = "all".equalsIgnoreCase(effectiveTool)
            ? List.of()
            : pendingTools.stream().filter(t -> !t.equals(effectiveTool)).toList();
        return new OutcomeResult(ApprovalOutcome.PARTIAL_STILL_PENDING, channelType, remaining, nextPending);
    }

    /**
     * Resolves a potentially truncated tool name by prefix-matching against registered tools.
     * Returns the resolved tool name, or the original if ambiguous (no match or multiple matches).
     * Returns null if the tool name is not found and no prefix match resolves it.
     */
    private Map<String, ToolDecision> readDecisions(String freshJson, String effectiveTool,
                                                      ToolDecision decisionEnum, String sessionId) {
        try {
            return objectMapper.readValue(freshJson, new TypeReference<Map<String, ToolDecision>>() {});
        } catch (Exception e) {
            log.warnf("[ASM] Failed to deserialize decisions for session=%s: %s", sessionId, e.getMessage());
            return Map.of(effectiveTool, decisionEnum);
        }
    }

    private static String resolveByPrefix(String toolName, List<String> allTools, String sessionId) {
        if (allTools.contains(toolName) || "all".equalsIgnoreCase(toolName)) return toolName;
        List<String> matches = allTools.stream()
            .filter(t -> t.startsWith(toolName))
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    @Override
    public String resolveSessionIdPrefix(String prefix) {
        // Prefer exact UUID match over delegate/cron sub-sessions with same prefix
        Optional<AgentCheckpoint> exact = agentStore.findLatestAwaitingApproval(prefix);
        if (exact.isPresent()) {
            log.infof("[ASM] Resolved session prefix '%s' -> '%s' (exact match)", prefix, exact.get().getSessionId());
            return exact.get().getSessionId();
        }
        String resolved = agentStore.findLatestAwaitingApprovalBySessionContaining(prefix)
                .map(AgentCheckpoint::getSessionId)
                .orElse(prefix);
        if (!resolved.equals(prefix)) {
            log.infof("[ASM] Resolved session prefix '%s' -> '%s' (segment match)", prefix, resolved);
        } else {
            log.warnf("[ASM] Could not resolve session prefix '%s', using as-is", prefix);
        }
        return resolved;
    }
}
