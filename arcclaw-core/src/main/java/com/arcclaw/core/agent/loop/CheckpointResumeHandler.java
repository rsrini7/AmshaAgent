package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointResumeHandler {

    private static final Logger log = Logger.getLogger(CheckpointResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;

    protected CheckpointResumeHandler() {
        this.agentStore = null;
        this.toolRegistry = null;
    }

    @Inject
    public CheckpointResumeHandler(StatefulAgentStore agentStore, ToolRegistry toolRegistry) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
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

    public AgentResult handle(AgentExecutionContext context, List<ReasoningStep> history, MessageContext messageContext) {
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
            log.infof("[AgentLoop] User denied tool execution for session %s, tool %s", sessionId, pendingTool);

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

        // 4. Normal Approval Path
        log.infof("[AgentLoop] Resuming session %s executing pending tool %s", sessionId, pendingTool);

        try {
            // Use ScopedValue context to execute the tool with FULL autonomy
            ToolResponse resumeResponse = WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    WorkspaceContext.userId(),
                    WorkspaceContext.chatId(),
                    agentRole,
                    () -> toolRegistry.invokeTool(pendingTool, pendingParams, sessionId));

            synchronized (history) {
                history.add(new ReasoningStep(
                        "Human Approved",
                        pendingTool,
                        Map.of("parameters", pendingParams != null ? pendingParams : "{}"),
                        resumeResponse.output(),
                        0, 0));
            }

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
}
