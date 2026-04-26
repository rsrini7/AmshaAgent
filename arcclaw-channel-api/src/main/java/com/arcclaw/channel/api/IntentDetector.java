package com.arcclaw.channel.api;

/**
 * Classifies a free-text user message into an agent role name.
 *
 * <p>
 * Implementations use an LLM-backed classification model. On failure the
 * implementation must default to {@code "coordinator"} and never throw.
 *
 * @see com.arcclaw.app.channel.telegram.IntentDetectorService
 */
public interface IntentDetector {

    /**
     * Classifies {@code userMessage} and returns a canonical agent role name
     * (e.g. {@code "coordinator"}, {@code "coder"}, {@code "researcher"},
     * {@code "debugger"}).
     *
     * @param userMessage the raw user input; must not be null
     * @return a canonical role name, never null, defaults to {@code "coordinator"}
     */
    String classify(String userMessage);
}
