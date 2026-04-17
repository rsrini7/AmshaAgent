package com.arcclaw.core.agent.loop;

/**
 * Strategy interface for managing schema self-correction budget and hints.
 * Extracted from SelfCorrectionRetryManager as part of Tier 2-4 hardening.
 */
public interface CorrectionStrategy {

    /**
     * Builds a correction hint to be injected into the next LLM iteration.
     * Implementing classes MUST NOT catch BudgetExceededException (§3.5, §8.6).
     *
     * @param currentRetryCount the current attempt (1-based)
     * @param failureReason the reason the previous output failed validation
     * @return the hint string
     */
    String buildCorrectionHint(int currentRetryCount, String failureReason);

    /**
     * Checks if the session is still within its retry budget.
     *
     * @param currentRetryCount the current number of failures already encountered
     * @return true if retry is allowed
     */
    boolean withinBudget(int currentRetryCount);
}
