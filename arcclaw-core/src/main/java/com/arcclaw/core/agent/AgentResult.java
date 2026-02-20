package com.arcclaw.core.agent;

import java.util.List;

/**
 * Result of an {@link AgentLoop} execution.
 *
 * @param finalAnswer       The agent's final answer (if loop completed
 *                          successfully).
 * @param steps             The full reasoning trace.
 * @param success           Whether the loop completed with a FINISH action.
 * @param terminationReason Why the loop ended (e.g., "FINISH",
 *                          "MAX_ITERATIONS_EXCEEDED").
 * @param totalInputTokens  Total LLM input tokens consumed across all steps.
 * @param totalOutputTokens Total LLM output tokens generated across all steps.
 */
public record AgentResult(
        String finalAnswer,
        List<ReasoningStep> steps,
        boolean success,
        String terminationReason,
        int totalInputTokens,
        int totalOutputTokens) {

    public static AgentResult finished(String answer, List<ReasoningStep> steps) {
        return create(answer, steps, true, "FINISH");
    }

    public static AgentResult loopExceeded(List<ReasoningStep> steps, int maxIterations) {
        return create(
                "Loop terminated: exceeded " + maxIterations + " iterations without reaching a conclusion.",
                steps, false, "MAX_ITERATIONS_EXCEEDED");
    }

    public static AgentResult error(List<ReasoningStep> steps, String reason) {
        return create("Error: " + reason, steps, false, reason);
    }

    private static AgentResult create(String answer, List<ReasoningStep> steps, boolean success, String reason) {
        int inTokens = steps.stream().mapToInt(ReasoningStep::inputTokens).sum();
        int outTokens = steps.stream().mapToInt(ReasoningStep::outputTokens).sum();
        return new AgentResult(answer, steps, success, reason, inTokens, outTokens);
    }
}
