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

    StepDecision processEngineStep(AgentExecutionContext context, ReasoningEngine engine,
            List<ReasoningStep> history, String transientError,
            int llmSchemaErrorCount, int iteration) {
        return engineStepHandler.processEngineStep(
                context, engine, history, transientError, llmSchemaErrorCount, iteration);
    }

    StepDecision validateTool(AgentExecutionContext context, List<ReasoningStep> history,
            ReasoningStep step, int consecutiveFailures) {
        return toolStepHandler.validateTool(context, history, step, consecutiveFailures);
    }

    StepDecision invokeToolAndObserve(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            int consecutiveFailures, List<CompiledSkillEntry> skillSnapshot) {
        return toolStepHandler.invokeToolAndObserve(
                context, history, step, consecutiveFailures, skillSnapshot);
    }

    AgentResult persistApprovalCheckpoint(AgentExecutionContext context,
            String toolName, String paramsJson,
            List<ReasoningStep> history) {
        return checkpointManager.persist(context, toolName, paramsJson, history);
    }
}
