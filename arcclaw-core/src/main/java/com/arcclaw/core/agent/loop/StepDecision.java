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
        boolean isRetryValidation,
        boolean isRateLimitHit) {

    public static StepDecision proceed(ReasoningStep step) {
        return new StepDecision(DecisionType.PROCEED, null, step, null, 0, 0, false, false);
    }

    public static StepDecision terminal(AgentResult result) {
        return new StepDecision(DecisionType.TERMINAL, result, null, null, 0, 0, false, false);
    }

    public static StepDecision continueLoop(String error, int schemaErrorCount) {
        // Semantic alias for retryEngine() - both signal "fetch a new LLM
        // thought on the next iteration". Kept as a named alias for call-site
        // readability at schema-retry paths vs engine-retry paths.
        return retryEngine(error, schemaErrorCount);
    }

    /**
     * Signals that the state machine should invoke the LLM for a new thought
     * on the next iteration. Sets {@code isRetryValidation = false}.
     *
     * <p>Contrast with {@link #retryValidation(int)}, which skips the LLM
     * call entirely and re-enters the inner validation loop immediately
     * (used for deduplication-cache hits).
     */
    public static StepDecision retryEngine(String error, int schemaErrorCount) {
        return new StepDecision(DecisionType.CONTINUE, null, null, error, schemaErrorCount, 0, false, false);
    }

    public static StepDecision retryRateLimit(String error, int schemaErrorCount) {
        return new StepDecision(DecisionType.CONTINUE, null, null, error, schemaErrorCount, 0, false, true);
    }

    public static StepDecision retryValidation(int consecutiveFailures) {
        return new StepDecision(DecisionType.CONTINUE, null, null, null, 0, consecutiveFailures, true, false);
    }

    public static StepDecision proceedWithFailures(ReasoningStep step, int consecutiveFailures) {
        return new StepDecision(DecisionType.PROCEED, null, step, null, 0, consecutiveFailures, false, false);
    }
}
