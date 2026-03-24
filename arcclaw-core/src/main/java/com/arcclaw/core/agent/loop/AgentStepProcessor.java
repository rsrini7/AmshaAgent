package com.arcclaw.core.agent.loop;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.skill.CompiledSkillEntry;

/**
 * Facade delegating to focused handlers.
 * {@link AgentStateMachineRunner} calls this class unchanged.
 */
@ApplicationScoped
public class AgentStepProcessor {

    @Inject EngineStepHandler engineStepHandler;
    @Inject ToolStepHandler toolStepHandler;
    @Inject ApprovalCheckpointManager checkpointManager;

    StepDecision processEngineStep(String sessionId, String goal, ReasoningEngine engine,
            List<ReasoningStep> history, String agentRole, String transientError,
            int llmSchemaErrorCount, int iteration) {
        return engineStepHandler.processEngineStep(
                sessionId, goal, engine, history, agentRole, transientError, llmSchemaErrorCount, iteration);
    }

    StepDecision validateTool(String sessionId, String agentRole, List<ReasoningStep> history,
            ReasoningStep step, String goal, int consecutiveFailures) {
        return toolStepHandler.validateTool(sessionId, agentRole, history, step, goal, consecutiveFailures);
    }

    StepDecision invokeToolAndObserve(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step,
            int consecutiveFailures, String goal, List<CompiledSkillEntry> skillSnapshot) {
        return toolStepHandler.invokeToolAndObserve(
                sessionId, agentRole, history, step, consecutiveFailures, goal, skillSnapshot);
    }

    AgentResult persistApprovalCheckpoint(String sessionId, String agentRole,
            String toolName, String paramsJson,
            List<ReasoningStep> history, String goal) {
        return checkpointManager.persist(sessionId, agentRole, toolName, paramsJson, history, goal);
    }
}
