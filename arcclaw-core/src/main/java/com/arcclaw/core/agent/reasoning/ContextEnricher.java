package com.arcclaw.core.agent.reasoning;

/**
 * CDI extension point for injecting context into system prompts before
 * the role-specific Qute template renders. Implementations in downstream
 * modules (arcclaw-rag) are discovered via Instance<ContextEnricher>.
 */
public interface ContextEnricher {
    /**
     * Enrich the base system prompt with additional context.
     * Must return a non-null string - if no context is available,
     * return basePrompt unchanged.
     *
     * @param sessionId current workspace session ID
     * @param goal      the user's current goal/message text
     * @param basePrompt the fully assembled system prompt so far
     */
    String enrich(String sessionId, String goal, String basePrompt);
}
