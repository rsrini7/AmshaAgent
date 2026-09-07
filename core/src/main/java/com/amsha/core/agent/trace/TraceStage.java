package com.amsha.core.agent.trace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle stage of a single agent turn, as published on the execution trace.
 *
 * <p>The serialized value is an explicit lowercase {@code snake_case} string. It is declared
 * per constant rather than derived from {@link #name()} so the Java constant convention and the
 * JSON wire contract stay independently versionable. See
 * {@code .scratch/amsha-visual-cockpit/spec.md} §2.4.
 */
public enum TraceStage {
    TURN_START("turn_start"),
    PREFLIGHT("preflight"),
    ROUTE("route"),
    MEMORY_DECISION("memory_decision"),
    GUARD_CHECK("guard_check"),
    HITL_REQUESTED("hitl_requested"),
    HITL_RESOLVED("hitl_resolved"),
    LLM_REASONING("llm_reasoning"),
    TOOL_START("tool_start"),
    TOOL_END("tool_end"),
    TOOL_ERROR("tool_error"),
    QUALITY_CHECK("quality_check"),
    RETRY("retry"),
    HITL_TIMEOUT("hitl_timeout"),
    LOOP_STUCK("loop_stuck"),
    CONSOLIDATION("consolidation"),
    TURN_END("turn_end");

    private final String wireValue;

    TraceStage(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Returns the explicit lowercase {@code snake_case} wire value of this stage. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolves a stage from its wire value.
     *
     * @throws IllegalArgumentException if the value is not part of this contract version
     */
    @JsonCreator
    public static TraceStage fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TraceStage stage : values()) {
            if (stage.wireValue.equals(value)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unknown TraceStage wire value: " + value);
    }
}
