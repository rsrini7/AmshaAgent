package com.amsha.core.observability;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;

/**
 * Observer interface for the Agent reasoning engine.
 * Receives lifecycle events and consumption metrics.
 */
public interface AgentObserver {

    /**
     * Record LLM token consumption for a reasoning step.
     *
     * @param sessionId    The workspace session ID.
     * @param inputTokens  The number of input tokens consumed.
     * @param outputTokens The number of output tokens generated.
     */
    void recordLlmCall(String sessionId, int inputTokens, int outputTokens);

    /**
     * Called after each reasoning step completes (observation appended, before
     * checkpoint save). Allows observers to opt into step-level events without
     * breaking existing implementations.
     *
     * @param sessionId The workspace session ID.
     * @param role      The agent role identifier.
     * @param step      The completed reasoning step (with observation filled).
     */
    default void onStepComplete(String sessionId, String role, ReasoningStep step) {
        // no-op by default - existing implementations (GovernanceLayer,
        // AgentOtelContext) are unaffected.
    }

    default void onLoopComplete(
            String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {}
}
