package com.amsha.core.governance;

/**
 * Pluggable governance policy for LLM budget enforcement.
 * <p>
 * The default implementation {@link GovernanceLayer} enforces per-session
 * USD budget limits. Swap the implementation via CDI {@code @Alternative}
 * for custom billing engines, per-tenant policies, or test doubles.
 */
public interface GovernanceStrategy {

    /**
     * Records a completed LLM call and enforces budget limits.
     *
     * @throws BudgetWarningException  if session cost crosses the soft warning threshold
     * @throws BudgetExceededException if session cost crosses the hard budget limit
     */
    void recordLlmCall(String sessionId, int inputTokens, int outputTokens);

    /**
     * Asserts the session has not already exceeded its budget.
     *
     * @throws BudgetExceededException if already over limit
     */
    void assertWithinBudget(String sessionId);

    /** Returns total tracked USD cost for the session. */
    double getSessionCost(String sessionId);

    /** Clears budget state on session termination. */
    void clearSession(String sessionId);

    /** Total configured budget in USD. */
    double getBudgetUsd();

    /** Returns total input tokens consumed for the session. */
    int getSessionInputTokens(String sessionId);

    /** Returns total output tokens consumed for the session. */
    int getSessionOutputTokens(String sessionId);
}
