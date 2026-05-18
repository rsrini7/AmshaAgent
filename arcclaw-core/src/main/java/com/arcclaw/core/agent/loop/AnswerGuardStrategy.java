package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.agent.reasoning.ReasoningStep;

/**
 * Strategy interface for validating and sanitizing agent answers.
 * Extracted from AnswerQualityGuard as part of Tier 2-4 hardening.
 */
public interface AnswerGuardStrategy {

    /**
     * Guards the candidate answer against common failures (echoes, leaks, etc).
     *
     * @param rawAnswer the raw answer produced by the LLM
     * @param history current reasoning history
     * @return an AnswerGuardResult containing the (possibly sanitized) answer and status
     */
    AnswerGuardResult guard(String rawAnswer, List<ReasoningStep> history);

    /**
     * Checks if the answer contains a raw tool-call leak.
     */
    boolean isRawToolCallLeak(String answer);

    /**
     * Strips raw JSON tool-call envelopes.
     */
    String stripRawToolJson(String answer);

    /**
     * Extracts the tool name from a leak.
     */
    String extractLeakedToolName(String answer);

    /**
     * Attempts to parse a leaked tool call from the answer text.
     * Returns a LeakedToolCall if the text contains a parseable tool invocation, empty otherwise.
     */
    Optional<LeakedToolCall> extractLeakedToolCall(String answer);

    /**
     * A parsed tool call extracted from leaked FINISH answer text.
     */
    record LeakedToolCall(String toolName, Map<String, Object> arguments) {}

    /**
     * Represents the outcome of an answer guard check.
     *
     * @param answer effective answer to return or show to the user
     * @param salvaged true if the answer was modified to remove echoes/leaks
     * @param failureReason if the guard failed decisively, why (for logging/retry)
     */
    record AnswerGuardResult(String answer, boolean salvaged, String failureReason) {
        public boolean isFailure() {
            return failureReason != null;
        }
    }
}
