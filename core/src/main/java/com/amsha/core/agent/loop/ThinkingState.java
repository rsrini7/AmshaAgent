package com.amsha.core.agent.loop;

import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.structured.StructuredOutputException;

public class ThinkingState implements RunnerState {

    private final RunnerServices services;

    public ThinkingState(RunnerServices services) {
        this.services = services;
    }

    @Override
    public RunnerState execute(StateContext context) {
        String currentTransientError = context.transientError();
        if (context.history().isEmpty()
                && services.intentDetector()
                        .isWriteIntent(context.executionContext().goal())) {
            currentTransientError = "The user wants to store data. Call ingest_document immediately.";
        }

        try {
            StepDecision engineDecision = services.engineStepHandler()
                    .processEngineStep(
                            context.executionContext(),
                            context.engine(),
                            context.history(),
                            currentTransientError,
                            context.llmSchemaErrorCount(),
                            context.iteration());

            if (engineDecision.isRateLimitHit()) {
                if (context.rateLimitRetryCount() + 1 > services.maxRateLimitRetries()) {
                    context.setFinalResult(AgentResult.failed(
                            "Repeated rate limits.",
                            AgentResult.FailureCategory.SYSTEM_ERROR,
                            List.copyOf(context.history())));
                    return services.failedState();
                }
                context.incrementRateLimitRetryCount();
                context.decrementIteration();
                return services.preflightState();
            }

            return handleEngineDecision(context, engineDecision);

        } catch (StructuredOutputException soe) {
            if (!services.correctionStrategy().withinBudget(context.llmSchemaErrorCount())) {
                context.setFinalResult(AgentResult.loopExceeded(
                        context.executionContext().maxIterations(), List.copyOf(context.history())));
                return services.failedState();
            }
            int nextSchemaCount = context.llmSchemaErrorCount() + 1;
            context.setLlmSchemaErrorCount(nextSchemaCount);
            context.setTransientError(
                    services.correctionStrategy().buildCorrectionHint(nextSchemaCount, soe.getMessage()));
            return services.preflightState();
        }
    }

    private RunnerState handleEngineDecision(StateContext context, StepDecision engineDecision) {
        if (engineDecision.type() == DecisionType.TERMINAL) {
            context.setFinalResult(engineDecision.result());
            return services.terminalStateForResult(engineDecision.result());
        }
        if (engineDecision.type() == DecisionType.CONTINUE) {
            context.setTransientError(engineDecision.transientError());
            context.setLlmSchemaErrorCount(engineDecision.llmSchemaErrorCount());
            return services.preflightState();
        }

        ReasoningStep step = engineDecision.step();
        return new ToolExecutionState(services, step);
    }

    @Override
    public boolean isTerminal() {
        return false;
    }

    @Override
    public boolean isInsideIterationSpan() {
        return true;
    }
}
