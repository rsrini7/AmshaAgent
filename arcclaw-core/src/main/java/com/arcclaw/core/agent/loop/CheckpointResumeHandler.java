package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointResumeHandler {

    private static final Logger log = Logger.getLogger(CheckpointResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    protected CheckpointResumeHandler() {
        this.agentStore = null;
        this.toolRegistry = null;
        this.objectMapper = null;
    }

    @Inject
    public CheckpointResumeHandler(StatefulAgentStore agentStore,
                                   ToolRegistry toolRegistry,
                                   ObjectMapper objectMapper) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the effective goal to use when resuming a paused session.
     * <p>
     * Callers ({@code ApproveReflex}, {@code CronApproveHandler}) MUST call
     * this method to obtain the goal string for {@code AgentLoop.run()} instead
     * of passing {@link AgentSentinels#GOAL_RESUME_APPROVED} directly.
     * <p>
     * <strong>Why this matters:</strong> {@code RagContextEnricher} uses the goal
     * string as the embedding query. Passing {@code "Resuming task after approval"}
     * produces semantically empty RAG vectors that return irrelevant chunks.
     * The original user goal (e.g., {@code "what is my github repo used for learning"})
     * produces correct semantic embeddings and retrieves the right context.
     *
     * @param sessionId the workspace session ID
     * @param agentRole the agent role for the paused checkpoint
     * @return the original user goal if captured on the checkpoint,
     *         otherwise falls back to {@link AgentSentinels#GOAL_RESUME_APPROVED}
     */
    public String resolveResumeGoal(String sessionId, String agentRole) {
        return agentStore.load(sessionId, agentRole)
                .map(cp -> {
                    String og = cp.getOriginalGoal();
                    return (og != null && !og.isBlank()) ? og : AgentSentinels.GOAL_RESUME_APPROVED;
                })
                .orElse(AgentSentinels.GOAL_RESUME_APPROVED);
    }

    public AgentResult handle(AgentExecutionContext context,
                              List<ReasoningStep> history,
                              MessageContext messageContext) {
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        String goal = context.goal();

        // 1. Only proceed if the context explicitly signals a resumption attempt
        if (messageContext == null || !messageContext.isResumeSignal()) {
            return null;
        }

        var resumeOpt = agentStore.load(sessionId, agentRole);
        if (resumeOpt.isEmpty()) {
            return null;
        }

        var checkpoint = resumeOpt.get();

        // 2. State Guard: Only handle resumptions for checkpoints waiting for a human.
        // This prevents the "Resuming task..." system goal from accidentally
        // re-triggering completed work.
        if (checkpoint.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            log.warnf("[AgentLoop] Attempted to resume session %s but status is %s. Aborting resume.",
                    sessionId, checkpoint.getStatus());
            return null;
        }

        String pendingTool = checkpoint.getPendingToolName();
        String pendingParams = checkpoint.getPendingToolParametersJson();

        // 3. Logic-Based Denial Check
        // We no longer scan chat history. We check if the "goal" passed from
        // CommandDispatchHandler contains the explicit signal "denial".
        boolean isDenial = AgentSentinels.GOAL_DENY_APPROVED.equals(goal);

        if (isDenial) {
            log.infof("[AgentLoop] User denied tool execution for session %s, tool %s",
                    sessionId, pendingTool);

            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setFailReason("User denied tool execution.");
            checkpoint.setPendingToolName(null);
            checkpoint.setPendingToolParametersJson(null);
            agentStore.save(checkpoint);

            // Return the Tier 2A Sealed Variant
            return AgentResult.finished(
                    "❌ Tool execution was denied and the task has been cancelled.",
                    List.copyOf(history));
        }

        // NEW: Parallel batch resume (§7.4)
        if (checkpoint.isParallelBatch()) {
            return resumeParallelBatch(context, history, checkpoint);
        }

        // 4. Normal Approval Path
        log.infof("[AgentLoop] Resuming session %s executing pending tool %s",
                sessionId, pendingTool);

        try {
            // Use ScopedValue context to execute the tool with FULL autonomy
            ToolResponse resumeResponse = WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    WorkspaceContext.userId(),
                    WorkspaceContext.chatId(),
                    agentRole,
                    () -> toolRegistry.invokeTool(pendingTool, pendingParams, sessionId));

            // history is single-threaded at resume time — no synchronization needed.
            history.add(ReasoningStep.pending(
                    "Human Approved",
                    pendingTool,
                    Map.of("parameters", pendingParams != null ? pendingParams : "{}"),
                    0, 0)
                    .withObservation(resumeResponse.output()));

            // Transition back to IN_PROGRESS so the main loop iteration can proceed
            checkpoint.setPendingToolName(null);
            checkpoint.setPendingToolParametersJson(null);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            agentStore.save(checkpoint);

            // Return null to signal the main run() loop to continue to the next LLM call
            return null;

        } catch (Exception e) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setPendingToolName(null);
            checkpoint.setPendingToolParametersJson(null);
            agentStore.save(checkpoint);

            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            log.errorf("Resume tool execution failed for session=%s tool=%s: %s",
                    sessionId, pendingTool, errMsg);

            return AgentResult.failed(
                    "TOOL_EXECUTION_FAILED: " + errMsg,
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history));
        }
    }

    private AgentResult resumeParallelBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        log.infof("[PARALLEL-HITL-RESUME] session=%s resuming batch: %s",
                  sessionId, checkpoint.parallelToolNames());

        List<ParallelToolCall> calls;
        try {
            calls = objectMapper.readValue(
                checkpoint.getPendingToolParametersJson(),
                new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL-RESUME] session=%s: failed to parse batch: %s",
                       sessionId, e.getMessage());
            checkpoint.setStatus(CheckpointStatus.FAILED);
            agentStore.save(checkpoint);
            return AgentResult.failed(
                "Failed to resume parallel tool batch — corrupt checkpoint.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history));
        }

        // FIX: log the exception message instead of silently swallowing it.
        Map<String, String> decisions = Map.of();
        if (checkpoint.hasBatchToolDecisions()) {
            try {
                decisions = objectMapper.readValue(
                    checkpoint.getBatchToolDecisions(),
                    new TypeReference<Map<String, String>>() {}
                );
            } catch (Exception e) {
                log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load decisions — " +
                          "defaulting to PENDING for all tools: %s",
                          sessionId,
                          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        // §7.30: Load pre-executed results and merge or resume
        Map<String, String> preExecuted = Map.of();
        if (checkpoint.hasPreExecutedResults()) {
            try {
                preExecuted = objectMapper.readValue(
                    checkpoint.getPreExecutedResults(),
                    new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load pre-executed results — " +
                          "will re-invoke affected tools: %s",
                          sessionId,
                          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        List<ParallelToolCall> completed = new ArrayList<>();
        for (ParallelToolCall call : calls) {
            // §7.30: Use pre-stored result if available — never re-execute
            if (preExecuted.containsKey(call.toolName())) {
                try {
                    ToolResponse stored = objectMapper.readValue(
                        preExecuted.get(call.toolName()), ToolResponse.class);
                    completed.add(call.withResult(stored.output(), stored.success()));
                    continue;
                } catch (Exception e) {
                    log.warnf("[PRE-EXEC-RESTORE] session=%s could not restore result for %s" +
                              ", re-invoking: %s",
                              sessionId, call.toolName(),
                              e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    // fall through to normal execution if restore failed
                }
            }

            String decision = decisions.getOrDefault(
                call.toolName(), AgentSentinels.TOOL_DECISION_PENDING);

            if (AgentSentinels.TOOL_DECISION_DENIED.equals(decision)) {
                completed.add(call.withResult("User denied this tool execution.", false));
                continue;
            }

            try {
                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    WorkspaceContext.userId(),
                    WorkspaceContext.chatId(),
                    agentRole,
                    () -> toolRegistry.invokeTool(
                        call.toolName(), call.paramsAsString(), sessionId));
                completed.add(call.withResult(response.output(), response.success()));
            } catch (Exception e) {
                // FIX: null-guard e.getMessage() — some exception types return null here.
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warnf("[PARALLEL-HITL-RESUME] session=%s tool=%s failed: %s",
                          sessionId, call.toolName(), errMsg);
                completed.add(call.withResult(
                    "Tool execution failed on resume: " + errMsg, false));
            }
        }

        String completedJson;
        try {
            completedJson = objectMapper.writeValueAsString(completed);
        } catch (Exception e) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            agentStore.save(checkpoint);
            return AgentResult.failed(
                "Failed to serialize resumed batch results.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history));
        }

        String summary = completed.stream()
            .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
            .collect(Collectors.joining(", "));

        ReasoningStep completedStep = ReasoningStep.parallelBatch(
            "Human Approved (batch)",
            Map.of("batch", completedJson),
            0, 0
        ).withObservation(summary);

        // history is single-threaded at resume time — no synchronization needed.
        // Parallel execution within a batch uses its own CompletableFuture
        // collecting pattern inside ParallelToolStepExecutor (separate list).
        history.add(completedStep);

        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);

        return null; // Signal main loop to continue to next LLM call
    }
}
