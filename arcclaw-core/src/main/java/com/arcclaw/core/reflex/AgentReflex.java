package com.arcclaw.core.reflex;

public interface AgentReflex {
    /**
     * Evaluates if the input triggers this reflex.
     */
    boolean matches(String input);

    /**
     * Executes the deterministic logic and returns the response string to send directly to the user.
     */
    String execute(String sessionId, String userId, String chatId, String input);

    /**
     * Sanitizes the input (e.g., PII redaction) before it reaches the LLM.
     * Defaults to a no-op returning the input unchanged.
     */
    default String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}
