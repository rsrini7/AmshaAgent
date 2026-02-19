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
 */
public record AgentResult(
        String finalAnswer,
        List<ReasoningStep> steps,
        boolean success,
        String terminationReason) {
    public static AgentResult finished(String answer, List<ReasoningStep> steps) {
        return new AgentResult(answer, steps, true, "FINISH");
    }

    public static AgentResult loopExceeded(List<ReasoningStep> steps, int maxIterations) {
        return new AgentResult(
                "Loop terminated: exceeded " + maxIterations + " iterations without reaching a conclusion.",
                steps, false, "MAX_ITERATIONS_EXCEEDED");
    }

    public static AgentResult error(List<ReasoningStep> steps, String reason) {
        return new AgentResult("Error: " + reason, steps, false, reason);
    }
}
