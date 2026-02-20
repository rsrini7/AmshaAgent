package com.arcclaw.core.observability;

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

}
