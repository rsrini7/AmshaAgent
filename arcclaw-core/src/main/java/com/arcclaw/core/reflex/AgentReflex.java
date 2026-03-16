package com.arcclaw.core.reflex;

import com.arcclaw.core.pipeline.MessageContext;

public interface AgentReflex {
    /**
     * Evaluates if the input triggers this reflex.
     */
    boolean matches(String input);

    /**
     * Executes the deterministic logic and returns the structured result.
     */
    ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context);

    /**
     * Sanitizes the input (e.g., PII redaction) before it reaches the LLM.
     * Defaults to a no-op returning the input unchanged.
     */
    default String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}

