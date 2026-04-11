package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.time.LocalDateTime;
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
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApprovalCheckpointManager {

    private static final Logger log = Logger.getLogger(ApprovalCheckpointManager.class);

    @Inject StatefulAgentStore agentStore;
    @Inject ObjectMapper objectMapper;
    @Inject AgentSseService agentSseService;

    private AgentCheckpoint loadActiveOrCreate(String sessionId, String agentRole) {
        return agentStore.load(sessionId, agentRole)
                .filter(cp -> cp.getStatus() != CheckpointStatus.COMPLETED
                           && cp.getStatus() != CheckpointStatus.FAILED
                           && cp.getStatus() != CheckpointStatus.DENIED)
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpoint();
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
    AgentResult persist(AgentExecutionContext context,
            String toolName, String paramsJson,
            List<ReasoningStep> history,
            String channelType, String channelPlatformId) {  // ← added params
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolParametersJson(paramsJson);

        try {
            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            decisions.put(toolName, ToolDecision.PENDING);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.errorf("Failed to serialize batch decisions for single tool %s: %s",
                       toolName, e.getMessage());
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(LocalDateTime.now());

        if (context.hasChannelInfo()) {
            cp.setChannelType(context.channelType());
            cp.setChannelPlatformId(context.channelPlatformId());
        }

        agentStore.save(cp);
        log.infof("STATE-MACHINE Pausing session %s for human approval of tool %s " +
                  "(channel=%s platformId=%s)",
                  sessionId, toolName, channelType, channelPlatformId);

        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, HitlEventType.AWAITING_APPROVAL.wireValue(), toolName, paramsJson, null, Instant.now()
        ));
        return AgentResult.awaitingApproval(toolName, paramsJson, sessionId, List.copyOf(history));
    }

    /**
     * Persists an AWAITING_APPROVAL checkpoint for a parallel tool batch.
     */
    public AgentResult persistBatch(
            AgentExecutionContext context,
            String compositeName,
            String batchJson,
            List<ParallelToolCall> calls,
            List<ReasoningStep> history,
            String channelType, String channelPlatformId) {  // ← added params

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);

        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        calls.forEach(call -> decisions.put(
            call.toolName(), ToolDecision.PENDING));

        String decisionsJson;
        try {
            decisionsJson = objectMapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL] Failed to serialize decisions for " +
                       "session %s: %s", sessionId,
                       e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return persist(context, compositeName, batchJson, history,
                           channelType, channelPlatformId);  // ← pass through
        }

        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolParametersJson(batchJson);
        cp.setBatchToolDecisions(decisionsJson);
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(LocalDateTime.now());

        if (context.hasChannelInfo()) {
            cp.setChannelType(context.channelType());
            cp.setChannelPlatformId(context.channelPlatformId());
        }

        agentStore.save(cp);

        log.infof("[STATE-MACHINE] Pausing session %s for parallel HITL: %s " +
                  "(channel=%s platformId=%s)",
                  sessionId, compositeName, channelType, channelPlatformId);

        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, HitlEventType.AWAITING_APPROVAL.wireValue(), compositeName, batchJson, null, Instant.now()
        ));
        return AgentResult.awaitingApproval(
            compositeName, batchJson, sessionId, List.copyOf(history));
    }

    /**
     * Persists an AWAITING_APPROVAL checkpoint for a parallel tool batch
     * that contains both HITL-pending and pre-executed tool results.
     */
    public AgentResult persistBatchWithPreExecuted(
            AgentExecutionContext context,
            String compositeName,
            String originalBatchJson,
            List<ParallelToolCall> hitlCalls,
            String preExecutedResultsJson,
            List<ReasoningStep> history,
            String channelType, String channelPlatformId) {  // ← added params

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);

        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        hitlCalls.forEach(call ->
            decisions.put(call.toolName(), ToolDecision.PENDING));

        String decisionsJson;
        try {
            decisionsJson = objectMapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL] Failed to serialize decisions: %s",
                       e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return persist(context, compositeName, originalBatchJson, history,
                           channelType, channelPlatformId);  // ← pass through
        }

        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolParametersJson(originalBatchJson);
        cp.setBatchToolDecisions(decisionsJson);
        cp.setPreExecutedResults(preExecutedResultsJson);
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(LocalDateTime.now());

        if (context.hasChannelInfo()) {
            cp.setChannelType(context.channelType());
            cp.setChannelPlatformId(context.channelPlatformId());
        }

        agentStore.save(cp);

        log.infof("[STATE-MACHINE] Pausing session %s — HITL tools pending: %d | batch: %s " +
                  "(channel=%s platformId=%s)",
                  sessionId, hitlCalls.size(), compositeName, channelType, channelPlatformId);

        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, HitlEventType.AWAITING_APPROVAL.wireValue(), compositeName, originalBatchJson, null, Instant.now()
        ));
        return AgentResult.awaitingApproval(
            compositeName, originalBatchJson, sessionId,
            List.copyOf(history));
    }
}