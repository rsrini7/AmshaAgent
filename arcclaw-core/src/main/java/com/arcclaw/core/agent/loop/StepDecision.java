package com.arcclaw.core.agent.loop;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;

public record StepDecision(
        DecisionType type,
        AgentResult result,
        ReasoningStep step,
        String transientError,
        int llmSchemaErrorCount,
        int consecutiveFailures,
        boolean isRetryValidation) {

    public static StepDecision proceed(ReasoningStep step) {
        return new StepDecision(DecisionType.PROCEED, null, step, null, 0, 0, false);
    }

    public static StepDecision terminal(AgentResult result) {
        return new StepDecision(DecisionType.TERMINAL, result, null, null, 0, 0, false);
    }

    public static StepDecision continueLoop(String error, int schemaErrorCount) {
        return new StepDecision(DecisionType.CONTINUE, null, null, error, schemaErrorCount, 0, false);
    }

    public static StepDecision retryEngine(String error, int schemaErrorCount) {
        return new StepDecision(DecisionType.CONTINUE, null, null, error, schemaErrorCount, 0, false);
    }

    public static StepDecision retryValidation(int consecutiveFailures) {
        return new StepDecision(DecisionType.CONTINUE, null, null, null, 0, consecutiveFailures, true);
    }

    public static StepDecision proceedWithFailures(ReasoningStep step, int consecutiveFailures) {
        return new StepDecision(DecisionType.PROCEED, null, step, null, 0, consecutiveFailures, false);
    }
}
