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
     * Persists an AWAITING_APPROVAL checkpoint and returns the approval-pending AgentResult.
     * Captures the original user goal so RAG context enrichment on resume uses the
     * correct embedding query instead of the synthetic resume sentinel string.
     */
    AgentResult persist(AgentExecutionContext context,
            String toolName, String paramsJson,
            List<ReasoningStep> history) {
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpoint();
                    c.setSessionId(sessionId);
                    c.setAgentRole(agentRole);
                    return c;
                });
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolParametersJson(paramsJson);
        cp.setOriginalGoal(context.goal());
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);
        log.infof("STATE-MACHINE Pausing session %s for human approval of tool %s", sessionId, toolName);
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

        String sessionId  = context.sessionId();
        String agentRole  = context.agentRole();

        AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
            .orElseGet(() -> {
                AgentCheckpoint c = new AgentCheckpoint();
                c.setSessionId(sessionId);
                c.setAgentRole(agentRole);
                return c;
            });

        // Seed per-tool decisions: all PENDING (§7.20)
        Map<String, String> decisions = new LinkedHashMap<>();
        calls.forEach(call -> decisions.put(
            call.toolName(), AgentSentinels.TOOL_DECISION_PENDING));

        String decisionsJson;
        try {
            decisionsJson = objectMapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL] Failed to serialize decisions for " +
                       "session %s: %s", sessionId, e.getMessage());
            // Fall back to whole-batch persist — no per-tool granularity
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
     * @param originalBatchJson The full batch JSON (all tools).
     * @param hitlCalls         Only the tools requiring approval (seeds decisions).
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

        AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
            .orElseGet(() -> {
                AgentCheckpoint c = new AgentCheckpoint();
                c.setSessionId(sessionId);
                c.setAgentRole(agentRole);
                return c;
            });

        // Seed per-tool decisions only for HITL-pending tools (§7.20, §7.32)
        Map<String, String> decisions = new LinkedHashMap<>();
        hitlCalls.forEach(call ->
            decisions.put(call.toolName(), AgentSentinels.TOOL_DECISION_PENDING));

        String decisionsJson;
        try {
            decisionsJson = objectMapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL] Failed to serialize decisions: %s", e.getMessage());
            return persist(context, compositeName, originalBatchJson, history);
        }

        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName); // only HITL tools per §7.32
        cp.setPendingToolParametersJson(originalBatchJson);
        cp.setBatchToolDecisions(decisionsJson);
        cp.setPreExecutedResults(preExecutedResultsJson); // §7.30
        cp.setOriginalGoal(context.goal());
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);

        log.infof("[STATE-MACHINE] Pausing session %s — HITL: %s | pre-executed: %d tools",
            sessionId, compositeName, hitlCalls.size());

        return AgentResult.awaitingApproval(
            compositeName, originalBatchJson, sessionId,
            List.copyOf(history));
    }
}
