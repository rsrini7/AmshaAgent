package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.agent.sse.HitlEventType;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCheckpointRecord;
import com.arcclaw.core.governance.ApprovalFrame;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.LogUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Handles checkpoint persistence and SSE notification logic.
 */
@ApplicationScoped
public class ApprovalCheckpointManager {

    private static final Logger log = Logger.getLogger(ApprovalCheckpointManager.class);

    private final StatefulAgentStore agentStore;
    private final ObjectMapper objectMapper;
    private final AgentSseService agentSseService;

    @Inject
    public ApprovalCheckpointManager(StatefulAgentStore agentStore,
                                     AgentSseService agentSseService,
                                     ObjectMapper objectMapper) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.objectMapper = objectMapper;
    }

    public void save(AgentCheckpoint cp) {
        agentStore.save(cp);
    }

    private AgentCheckpoint loadActiveOrCreate(String sessionId, String agentRole) {
        return agentStore.load(sessionId, agentRole)
                .filter(cp -> cp.getStatus() != CheckpointStatus.COMPLETED
                           && cp.getStatus() != CheckpointStatus.FAILED
                           && cp.getStatus() != CheckpointStatus.DENIED)
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpointRecord();
                    c.setSessionId(sessionId);
                    c.setAgentRole(agentRole);
                    return c;
                });
    }

    /**
     * Persists an AWAITING_APPROVAL checkpoint including channel info atomically.
     * Channel type and platformId MUST be stamped before save so resumeCheckpoint()
     * can read the correct Telegram/WebSocket target after approval.
     */
    public AgentResult persist(AgentExecutionContext context,
            ApprovalFrame frame,
            List<ReasoningStep> history,
            String channelType, String channelPlatformId) {
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        String toolName = frame.tool();
        String paramsJson = "{}";
        try {
            paramsJson = objectMapper.writeValueAsString(frame.params());
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        agentStore.clearBatchResuming(sessionId);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolRole(agentRole);
        cp.setPendingToolParametersJson(paramsJson);

        try {
            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            decisions.put(toolName, ToolDecision.PENDING);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.errorf("Failed to serialize batch decisions for single tool %s: %s",
                       toolName, LogUtil.rootMessage(e));
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(Instant.now());

        if (context.hasChannelInfo()) {
            cp.setChannelType(context.channelType());
            cp.setChannelPlatformId(context.channelPlatformId());
        }
        
        // Save checkpoint metadata and reasoning steps in one atomic store write.
        cp = agentStore.saveWithSteps(cp, history);

        log.infof("STATE-MACHINE Pausing session %s for human approval of tool %s " +
                  "(channel=%s platformId=%s, risk=%s)",
                  sessionId, toolName, channelType, channelPlatformId, frame.riskLevel());

        // SSE HitlEvent is always sent for UI/Web tracking
        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, HitlEventType.AWAITING_APPROVAL.wireValue(), toolName, paramsJson, frame.riskLevel().name(), null, Instant.now()   
        ));
        
        return AgentResult.awaitingApproval(frame, sessionId, List.copyOf(history));
    }

    @SuppressWarnings("unchecked")
    public AgentResult persist(AgentExecutionContext context,
            String toolName, String paramsJson,
            List<ReasoningStep> history,
            Object channelType, Object channelPlatformId) {
        Map<String, Object> params = Map.of();
        try {
            params = objectMapper.readValue(paramsJson, Map.class);
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }
        ApprovalFrame frame = new ApprovalFrame(toolName, params, ApprovalFrame.RiskLevel.MEDIUM);
        return persist(context, frame, history, channelType != null ? channelType.toString() : null, channelPlatformId != null ? channelPlatformId.toString() : null);
    }

    /**
     * Specialized persistence for parallel tool batches.
     * Encapsulates the composite name generation and multi-tool status mapping.
     */
    public AgentResult persistBatch(AgentExecutionContext context,
            List<ParallelToolCall> calls,
            List<ReasoningStep> history,
            String channelType, String channelPlatformId) {
        
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        // 1. Generate composite name and JSON payload
        String compositeName = ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX +
                calls.stream().map(ParallelToolCall::toolName).distinct().reduce((a, b) -> a + "|" + b).orElse("batch");
        
        String originalBatchJson = "[]";
        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        try {
            originalBatchJson = objectMapper.writeValueAsString(calls);
            for (ParallelToolCall call : calls) {
                decisions.put(call.toolName(), ToolDecision.PENDING);
            }
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        // 2. Load or create checkpoint
        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        agentStore.clearBatchResuming(sessionId);
        
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolRole(agentRole);
        cp.setPendingToolParametersJson(originalBatchJson);
        try {
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(Instant.now());

        if (context.hasChannelInfo()) {
            cp.setChannelType(context.channelType());
            cp.setChannelPlatformId(context.channelPlatformId());
        }

        // 3. Save
        agentStore.saveWithSteps(cp, history);

        log.infof("STATE-MACHINE Pausing session %s for parallel batch approval: %s",
                sessionId, compositeName);

        // 4. Notify UI via SSE
        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, HitlEventType.AWAITING_APPROVAL.wireValue(), compositeName, originalBatchJson, ApprovalFrame.RiskLevel.MEDIUM.name(), null, Instant.now()
        ));

        // Create a synthetic frame for the result
        ApprovalFrame frame = new ApprovalFrame(compositeName, Map.of("batch", originalBatchJson), ApprovalFrame.RiskLevel.MEDIUM);
        return AgentResult.awaitingApproval(frame, sessionId, List.copyOf(history));
    }

    /**
     * Specialized persistence for parallel batches where some tools already executed (pre-execution pass).
     * Preserves pre-executed results in the checkpoint so they aren't lost during the HITL pause. §7.30
     */
    public AgentResult persistBatchWithPreExecuted(AgentExecutionContext context,
            List<ParallelToolCall> allCalls,
            Map<String, ToolResponse> results,
            List<ReasoningStep> history,
            String channelType, String channelPlatformId) {
        
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        // §Issue-3 Fix: Build composite name from HITL-pending tools only (not pre-executed).
        // This prevents ResumeStrategyDispatcher from attempting to re-execute already-completed tools.
        String compositeName = ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX +
                allCalls.stream()
                    .filter(call -> !results.containsKey(call.toolName()))
                    .map(ParallelToolCall::toolName).distinct()
                    .reduce((a, b) -> a + "|" + b).orElse("batch");
        
        String originalBatchJson = "[]";
        String preExecutedJson = "{}";
        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        
        try {
            originalBatchJson = objectMapper.writeValueAsString(allCalls);
            preExecutedJson = objectMapper.writeValueAsString(results);
            for (ParallelToolCall call : allCalls) {
                if (results.containsKey(call.toolName())) {
                    decisions.put(call.toolName(), ToolDecision.APPROVED);
                } else {
                    decisions.put(call.toolName(), ToolDecision.PENDING);
                }
            }
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        agentStore.clearBatchResuming(sessionId);
        
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolRole(agentRole);
        cp.setPendingToolParametersJson(originalBatchJson);
        cp.setPreExecutedResults(preExecutedJson);
        try {
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(Instant.now());

        if (context.hasChannelInfo()) {
            cp.setChannelType(context.channelType());
            cp.setChannelPlatformId(context.channelPlatformId());
        }

        agentStore.saveWithSteps(cp, history);

        log.infof("STATE-MACHINE Pausing session %s for parallel batch approval (with %d pre-executed): %s",
                sessionId, results.size(), compositeName);

        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, HitlEventType.AWAITING_APPROVAL.wireValue(), compositeName, originalBatchJson, ApprovalFrame.RiskLevel.MEDIUM.name(), null, Instant.now()
        ));
        
        ApprovalFrame frame = new ApprovalFrame(compositeName, Map.of("batch", originalBatchJson), ApprovalFrame.RiskLevel.MEDIUM);
        return AgentResult.awaitingApproval(frame, sessionId, List.copyOf(history));
    }
}
