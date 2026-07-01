package com.amsha.selfevolve.api;

/**
 * Typed event types for self-evolution trace capture.
 * Each type corresponds to a specific interaction pattern in the agent lifecycle.
 */
public enum EvolutionEventType {
    USER_MESSAGE,
    AGENT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    TOOL_ERROR,
    RESOLUTION,
    ESCALATION,
    COMPACTION,
    SELF_CORRECTION
}
