package com.amsha.core.hitl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.channel.api.hitl.ApprovalGateway;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.channel.api.hitl.ApprovalResult;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApprovalStateMachine implements ApprovalGateway {

    private static final Logger log = Logger.getLogger(ApprovalStateMachine.class);

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    DomainEventBus eventBus;

    @Inject
    CheckpointResumeHandler resumeHandler;

    @Inject
    ApprovalLeaseStore approvalLeaseStore;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "amsha.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    // ✅ Split the write and the resume - write inside NOT_SUPPORTED,
    // resume after the write is committed
    @Override
    @Transactional(TxType.NOT_SUPPORTED) // was @Transactional
    public ApprovalResult decide(String sessionId, String toolName, ApprovalDecision decision, String reason) {
        log.infof("[ASM] decide(session=%s, tool=%s, decision=%s, reason=%s)", sessionId, toolName, decision, reason);

        // 1. Load checkpoint - check exact session match first, then child delegated session if tool is not in parent
        Optional<AgentCheckpoint> exactCpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        AgentCheckpoint cp = null;
        List<String> allTools = null;
        String effectiveTool = null;

        if (exactCpOpt.isPresent()) {
            AgentCheckpoint exactCp = exactCpOpt.get();
            List<String> exactTools = BatchDecisionUtil.allToolNames(exactCp, objectMapper);
            String matched = resolveByPrefix(toolName, exactTools, exactCp.getSessionId());
            if (matched != null) {
                cp = exactCp;
                allTools = exactTools;
                effectiveTool = matched;
            }
        }

        if (cp == null) {
            Optional<AgentCheckpoint> childCpOpt = agentStore.findLatestAwaitingApprovalBySessionContaining(sessionId);
            if (childCpOpt.isPresent()) {
                AgentCheckpoint childCp = childCpOpt.get();
                List<String> childTools = BatchDecisionUtil.allToolNames(childCp, objectMapper);
                String matched = resolveByPrefix(toolName, childTools, childCp.getSessionId());
                if (matched != null) {
                    cp = childCp;
                    allTools = childTools;
                    effectiveTool = matched;
                } else if (exactCpOpt.isEmpty()) {
                    cp = childCp;
                    allTools = childTools;
                }
            }
        }

        if (cp == null && exactCpOpt.isPresent()) {
            cp = exactCpOpt.get();
            allTools = BatchDecisionUtil.allToolNames(cp, objectMapper);
        }

        if (cp == null) {
            log.warnf("[ASM] Checkpoint not found for session=%s, returning NOT_FOUND", sessionId);
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, sessionId, toolName, 0, List.of());
        }
        String targetSessionId = cp.getSessionId();
        log.debugf(
                "[ASM] Loaded checkpoint id=%d status=%s for session=%s (targetSession=%s)",
                cp.getId(), cp.getStatus(), sessionId, targetSessionId);

        // 2. Verify AWAITING_APPROVAL state (findLatestAwaitingApproval guarantees this)
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            log.warnf(
                    "[ASM] Checkpoint session=%s has status=%s instead of AWAITING_APPROVAL, returning NOT_FOUND",
                    targetSessionId, cp.getStatus());
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, targetSessionId, toolName, 0, List.of());
        }

        // 3. Check expiry via agentStore.isCheckpointExpired() -> EXPIRED if true
        if (agentStore.isCheckpointExpired(targetSessionId, hitlTimeoutHours)) {
            log.infof(
                    "[ASM] Checkpoint expired for session=%s (timeout=%dh), returning EXPIRED",
                    targetSessionId, hitlTimeoutHours);
            return new ApprovalResult(ApprovalOutcome.EXPIRED, targetSessionId, toolName, 0, List.of());
        }

        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        log.debugf("[ASM] session=%s allTools=%s pendingTools=%s", targetSessionId, allTools, pendingTools);

        // 5. Record decision per tool
        ToolDecision decisionEnum = decision == ApprovalDecision.APPROVE ? ToolDecision.APPROVED : ToolDecision.DENIED;
        if (effectiveTool == null) {
            effectiveTool = resolveByPrefix(toolName, allTools, targetSessionId);
        }
        if (effectiveTool == null) {
            log.warnf("[ASM] Tool '%s' not found in allTools=%s for session=%s", toolName, allTools, targetSessionId);
            return new ApprovalResult(ApprovalOutcome.NOT_FOUND, targetSessionId, toolName, 0, List.of());
        }
        if (!effectiveTool.equals(toolName)) {
            log.debugf(
                    "[ASM] Prefix-matched truncated tool '%s' -> '%s' for session=%s",
                    toolName, effectiveTool, targetSessionId);
        }

        boolean updatedAny = false;
        if ("all".equalsIgnoreCase(effectiveTool)) {
            log.infof("[ASM] Approving all pending tools for session=%s: %s", targetSessionId, pendingTools);
            for (String pending : pendingTools) {
                boolean updated = agentStore.updateBatchToolDecision(
                        targetSessionId, cp.getAgentRole(), pending, decisionEnum.name());
                if (updated) {
                    updatedAny = true;
                    if (decisionEnum == ToolDecision.APPROVED) {
                        grantLease(targetSessionId, cp, pending);
                    }
                }
            }
        } else {
            if (!pendingTools.contains(effectiveTool)) {
                log.infof(
                        "Tool '%s' already decided for session=%s, pendingTools=%s",
                        effectiveTool, targetSessionId, pendingTools);
                return ApprovalResult.alreadyDecided(targetSessionId, effectiveTool);
            }
            updatedAny = agentStore.updateBatchToolDecision(
                    targetSessionId, cp.getAgentRole(), effectiveTool, decisionEnum.name());
            if (!updatedAny) return ApprovalResult.alreadyDecided(targetSessionId, effectiveTool);
            if (decisionEnum == ToolDecision.APPROVED) grantLease(targetSessionId, cp, effectiveTool);
        }

        if (!updatedAny) return ApprovalResult.alreadyDecided(targetSessionId, effectiveTool);

        // 6. Resolve final outcome — all decided or still pending
        var result = resolveOutcome(targetSessionId, cp, effectiveTool, decisionEnum, allTools, pendingTools);

        // 7. Fire CDI ApprovalDecisionEvent for channel observers
        eventBus.publish(
                EventTypeCatalog.APPROVAL_DECISION,
                new ApprovalDecisionEvent(
                        targetSessionId,
                        result.channelType(),
                        cp.getChannelPlatformId(),
                        effectiveTool,
                        decision,
                        result.outcome()));
        log.debugf("[ASM] Fired ApprovalDecisionEvent for session=%s outcome=%s", targetSessionId, result.outcome());

        // 8. Return ApprovalResult with outcome + remainingPendingTools
        return new ApprovalResult(
                result.outcome(), targetSessionId, effectiveTool, result.remaining(), result.nextPending());
    }

    private record OutcomeResult(
            ApprovalOutcome outcome, String channelType, int remaining, List<String> nextPending) {}

    /**
     * Records an approval lease for the approved call shape (ADR-approval-lease-for-parallel-batches).
     * The checkpoint's {@code pendingToolParametersJson} holds either a parallel-batch array
     * ({@code [{toolName, params}, …]}) or a single tool's parameter object. Failures are
     * non-fatal: a missing lease degrades to re-prompting (the safe direction).
     */
    private void grantLease(String sessionId, AgentCheckpoint cp, String toolName) {
        try {
            String paramsJson = cp.getPendingToolParametersJson();
            if (paramsJson == null || paramsJson.isBlank()) {
                return;
            }
            JsonNode root = objectMapper.readTree(paramsJson);
            Map<String, Object> params = null;
            if (root.isArray()) {
                for (JsonNode element : root) {
                    if (toolName.equals(element.path("toolName").asText(null))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> extracted = objectMapper.convertValue(element.path("params"), Map.class);
                        params = extracted;
                        break;
                    }
                }
            } else if (root.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> extracted = objectMapper.convertValue(root, Map.class);
                params = extracted;
            }
            approvalLeaseStore.grant(sessionId, toolName, params);
        } catch (Exception e) {
            log.warnf(
                    "[ASM] Lease grant skipped for session=%s tool=%s: %s",
                    sessionId, toolName, LogUtil.rootMessage(e));
        }
    }

    private OutcomeResult resolveOutcome(
            String sessionId,
            AgentCheckpoint cp,
            String effectiveTool,
            ToolDecision decisionEnum,
            List<String> allTools,
            List<String> pendingTools) {
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        log.infof("[ASM] session=%s allDecided=%s", sessionId, allDecided);
        int remaining = pendingTools.size() - 1;
        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        if (allDecided) {
            String freshJson = agentStore
                    .findLatestAwaitingApproval(sessionId)
                    .map(AgentCheckpoint::getBatchToolDecisions)
                    .orElse(cp.getBatchToolDecisions());
            Map<String, ToolDecision> decisions = readDecisions(freshJson, effectiveTool, decisionEnum, sessionId);
            boolean anyApproved = allTools.stream().anyMatch(t -> ToolDecision.APPROVED == decisions.get(t));
            ApprovalOutcome outcome = anyApproved ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED;
            log.infof(
                    "[ASM] All decided for session=%s: decisions=%s anyApproved=%s outcome=%s",
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
    private Map<String, ToolDecision> readDecisions(
            String freshJson, String effectiveTool, ToolDecision decisionEnum, String sessionId) {
        try {
            return objectMapper.readValue(freshJson, new TypeReference<Map<String, ToolDecision>>() {});
        } catch (Exception e) {
            log.warnf("[ASM] Failed to deserialize decisions for session=%s: %s", sessionId, LogUtil.rootMessage(e));
            return Map.of(effectiveTool, decisionEnum);
        }
    }

    private static String resolveByPrefix(String toolName, List<String> allTools, String sessionId) {
        if (allTools.contains(toolName) || "all".equalsIgnoreCase(toolName)) return toolName;

        // Handle combined parallel-batch identifiers that concatenate the batch's tool names
        // with underscores. Tool names themselves may contain underscores, so split-based parsing
        // is ambiguous; use an exact batch join match instead.
        if (toolName.equals(String.join("_", allTools))) {
            log.infof(
                    "[ASM] Combined parallel-batch tool '%s' resolved to approve-all for session=%s",
                    toolName, sessionId);
            return "all";
        }

        List<String> matches =
                allTools.stream().filter(t -> t.startsWith(toolName)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    @Override
    public String resolveSessionIdPrefix(String prefix) {
        // Prefer exact UUID match over delegate/cron sub-sessions with same prefix
        Optional<AgentCheckpoint> exact = agentStore.findLatestAwaitingApproval(prefix);
        if (exact.isPresent()) {
            log.infof(
                    "[ASM] Resolved session prefix '%s' -> '%s' (exact match)",
                    prefix, exact.get().getSessionId());
            return exact.get().getSessionId();
        }
        String resolved = agentStore
                .findLatestAwaitingApprovalBySessionContaining(prefix)
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
