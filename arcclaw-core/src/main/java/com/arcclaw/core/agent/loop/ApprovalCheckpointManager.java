package com.arcclaw.core.agent.loop;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Persists AWAITING_APPROVAL checkpoints and produces the corresponding AgentResult.
 */
@ApplicationScoped
public class ApprovalCheckpointManager {

    private static final Logger log = Logger.getLogger(ApprovalCheckpointManager.class);

    @Inject StatefulAgentStore agentStore;
    @Inject ObjectMapper objectMapper;

    /**
     * Loads an existing non-terminal checkpoint for the session, or creates a
     * fresh one. A checkpoint with status COMPLETED, FAILED, or CANCELLED is
     * treated as absent — we never overwrite a terminal record in place.
     *
     * NOTE: DENIED is not a separate status in this system. DenyReflex stores
     * denials as CheckpointStatus.FAILED with AgentSentinels.DENY_SENTINEL as
     * the fail reason. Therefore FAILED covers both failed and denied checkpoints.
     * There is no separate DENIED enum value to filter.
     */
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
     * Persists an AWAITING_APPROVAL checkpoint and returns the approval-pending AgentResult.
     * Captures the original user goal so RAG context enrichment on resume uses the
     * correct embedding query instead of the synthetic resume sentinel string.
     */
    AgentResult persist(AgentExecutionContext context,
            String toolName, String paramsJson,
            List<ReasoningStep> history) {
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolParametersJson(paramsJson);
        
        try {
            Map<String, String> decisions = new LinkedHashMap<>();
            decisions.put(toolName, AgentSentinels.TOOL_DECISION_PENDING);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.errorf("Failed to serialize batch decisions for single tool %s: %s", toolName, e.getMessage());
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);
        log.infof("STATE-MACHINE Pausing session %s for human approval of tool %s",
                sessionId, toolName);
        return AgentResult.awaitingApproval(toolName, paramsJson, sessionId, List.copyOf(history));
    }

    /**
     * Persists an AWAITING_APPROVAL checkpoint for a parallel tool batch.
     * Initialises per-tool decision state (§7.20) so ApproveReflex and
     * DenyReflex can record individual tool decisions before resuming.
     *
     * @param calls The full parallel batch (used to seed batchToolDecisions).
     */
    public AgentResult persistBatch(
            AgentExecutionContext context,
            String compositeName,
            String batchJson,
            List<ParallelToolCall> calls,
            List<ReasoningStep> history) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);

        Map<String, String> decisions = new LinkedHashMap<>();
        calls.forEach(call -> decisions.put(
            call.toolName(), AgentSentinels.TOOL_DECISION_PENDING));

        String decisionsJson;
        try {
            decisionsJson = objectMapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL] Failed to serialize decisions for " +
                       "session %s: %s", sessionId,
                       e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return persist(context, compositeName, batchJson, history);
        }

        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolParametersJson(batchJson);
        cp.setBatchToolDecisions(decisionsJson);
        cp.setOriginalGoal(context.goal());
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);

        log.infof("[STATE-MACHINE] Pausing session %s for parallel HITL: %s",
                  sessionId, compositeName);
        return AgentResult.awaitingApproval(
            compositeName, batchJson, sessionId, List.copyOf(history));
    }

    /**
     * Persists an AWAITING_APPROVAL checkpoint for a parallel tool batch
     * that contains both HITL-pending and pre-executed tool results. §7.30
     *
     * @param originalBatchJson      The full batch JSON (all tools).
     * @param hitlCalls              Only the tools requiring approval (seeds decisions).
     * @param preExecutedResultsJson JSON map of toolName → ToolResponse for safe tools.
     */
    public AgentResult persistBatchWithPreExecuted(
            AgentExecutionContext context,
            String compositeName,
            String originalBatchJson,
            List<ParallelToolCall> hitlCalls,
            String preExecutedResultsJson,
            List<ReasoningStep> history) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);

        Map<String, String> decisions = new LinkedHashMap<>();
        hitlCalls.forEach(call ->
            decisions.put(call.toolName(), AgentSentinels.TOOL_DECISION_PENDING));

        String decisionsJson;
        try {
            decisionsJson = objectMapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL] Failed to serialize decisions: %s",
                       e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return persist(context, compositeName, originalBatchJson, history);
        }

        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolParametersJson(originalBatchJson);
        cp.setBatchToolDecisions(decisionsJson);
        cp.setPreExecutedResults(preExecutedResultsJson);
        cp.setOriginalGoal(context.goal());
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);

        log.infof("[STATE-MACHINE] Pausing session %s — HITL tools pending: %d | batch: %s",
            sessionId, hitlCalls.size(), compositeName);

        return AgentResult.awaitingApproval(
            compositeName, originalBatchJson, sessionId,
            List.copyOf(history));
    }
}