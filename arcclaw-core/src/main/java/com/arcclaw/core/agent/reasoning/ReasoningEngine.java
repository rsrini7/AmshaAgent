package com.arcclaw.core.agent.reasoning;

import java.util.List;

import com.arcclaw.core.agent.loop.AgentExecutionContext;

/**
 * Abstraction over LLM reasoning - produces the next {@link ReasoningStep}
 * given a goal and the current reasoning history.
 * <p>
 * In production, this wraps a {@code ChatModel} call. In tests,
 * a mock implementation returns scripted responses for deterministic
 * verification of loop behavior.
 */
public interface ReasoningEngine {

    /**
     * Computes the next reasoning step given the current agent role, goal, 
     * and prior step history.
     *
     * @param context   The execution context containing dynamic session data.
     * @param agentRole The active persona role (e.g., "coordinator", "coder").
     *                  Used to enforce strict tool binding per persona.
     * @param goal      The current task goal.
     * @param history   The prior reasoning steps in this loop iteration.
     * @return The next reasoning step.
     */
    ReasoningStep nextStep(AgentExecutionContext context, String agentRole, String goal, List<ReasoningStep> history);

    /**
     * Optional method for structured (non-ReAct) LLM calls.
     */
    String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt);
}
