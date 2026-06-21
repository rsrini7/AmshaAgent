package com.amsha.core.agent.loop;

import com.amsha.core.agent.reasoning.ReasoningStep;

public class ToolExecutionState implements RunnerState {
    private final RunnerServices services;
    private final ReasoningStep step;

    public ToolExecutionState(RunnerServices services, ReasoningStep step) {
        this.services = services;
        this.step = step;
    }

    @Override
    public RunnerState execute(StateContext context) {
        var outcome = services.stepDecisionRouter()
                .route(
                        context.executionContext(),
                        context.history(),
                        step,
                        context.llmSchemaErrorCount(),
                        context.consecutiveFailures(),
                        services.skillRegistry().currentSnapshot());

        if (outcome.isTerminal()) {
            context.setFinalResult(outcome.result());
            return services.terminalStateForResult(outcome.result());
        }

        if (outcome.retryEngine()) {
            context.setTransientError(outcome.transientError());
            if (outcome.llmSchemaErrorCount() != null) {
                context.setLlmSchemaErrorCount(outcome.llmSchemaErrorCount());
            }
            return services.preflightState();
        }

        if (outcome.consecutiveFailures() != null) {
            context.setConsecutiveFailures(outcome.consecutiveFailures());
        }
        context.setTransientError(null);
        return services.preflightState();
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
