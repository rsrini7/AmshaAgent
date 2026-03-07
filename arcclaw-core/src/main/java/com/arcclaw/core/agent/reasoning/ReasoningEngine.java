package com.arcclaw.core.agent.reasoning;

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
     * Computes the next reasoning step given the current agent role, goal, 
     * and prior step history.
     *
     * @param sessionId The sessionId for the active workspace.
     * @param agentRole The active persona role (e.g., "coordinator", "coder").
     *                  Used to enforce strict tool binding per persona.
     * @param goal      The current task goal.
     * @param history   The prior reasoning steps in this loop iteration.
     * @return The next reasoning step.
     */
    ReasoningStep nextStep(String sessionId, String agentRole, String goal, List<ReasoningStep> history);
}
