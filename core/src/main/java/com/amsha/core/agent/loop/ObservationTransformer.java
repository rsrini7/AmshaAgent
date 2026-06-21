package com.amsha.core.agent.loop;

/**
 * Interface for components that transform tool observations before they are
 * committed to agent history (e.g., truncation, PII redaction, injection filtering).
 */
public interface ObservationTransformer {

    /**
     * Determines the execution order. Lower priorities run first.
     */
    int priority();

    /**
     * Transforms the observation string.
     *
     * @param toolName the name of the tool that produced the observation
     * @param input    the observation to transform
     * @return the transformed observation
     */
    String transform(String toolName, String input);
}
