package com.arcclaw.core.agent;

import java.util.List;

/**
 * Abstraction over LLM reasoning — produces the next {@link ReasoningStep}
 * given a goal and the current reasoning history.
 * <p>
 * In production, this wraps a {@code ChatLanguageModel} call. In tests,
 * a mock implementation returns scripted responses for deterministic
 * verification of loop behavior.
 */
public interface ReasoningEngine {

    /**
     * Determine the next reasoning step.
     *
     * @param goal    The user's goal / task description.
     * @param history Previous reasoning steps (may be empty on first call).
     * @return The next step with thought, action, and actionInput filled in.
     *         The {@code observation} field will be {@code null} — it is
     *         filled by the {@link AgentLoop} after tool execution.
     */
    ReasoningStep nextStep(String goal, List<ReasoningStep> history);
}
