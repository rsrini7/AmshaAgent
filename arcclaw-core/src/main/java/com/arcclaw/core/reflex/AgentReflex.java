package com.arcclaw.core.reflex;

public interface AgentReflex {
    /**
     * Evaluates if the input triggers this reflex.
     */
    boolean matches(String input);

    /**
     * Executes the deterministic logic and returns the response string to send directly to the user.
     */
    String execute(String sessionId, String userId);
}
