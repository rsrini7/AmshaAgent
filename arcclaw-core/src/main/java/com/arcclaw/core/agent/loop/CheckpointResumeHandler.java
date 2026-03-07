package com.arcclaw.core.agent.loop;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;

import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CheckpointResumeHandler {

    private static final Logger log = Logger.getLogger(CheckpointResumeHandler.class);

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ToolRegistry toolRegistry;

    public AgentResult handle(String sessionId, String agentRole, String goal, List<ReasoningStep> history) {
        // 1. Only proceed if the goal explicitly signals a resumption attempt
        if (goal == null || !goal.toLowerCase().startsWith("resuming task")) {
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
        boolean isDenial = goal.toLowerCase().contains("denial");

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
