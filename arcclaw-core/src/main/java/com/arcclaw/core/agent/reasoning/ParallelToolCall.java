package com.arcclaw.core.agent.reasoning;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Represents one tool call within a parallel LLM response batch.
 * Immutable. Pre-execution: observation is null.
 * Post-execution: observation holds the sanitized ToolResponse output.
 *
 * Serialized to/from JSON via ObjectMapper as the "batch" key in
 * ReasoningStep.actionInput for Architecture_Invariants.md §7.1.
 */
public record ParallelToolCall(
    String callId,                    // stable ID: "call_0", "call_1", ...
    String toolName,                  // LLM-requested tool name
    Map<String, Object> params,       // deserialized tool parameters
    String observation,               // null pre-execution; filled post-execution
    boolean success                   // false pre-execution; set post-execution
) {
    /** Creates a pre-execution call with null observation. */
    public static ParallelToolCall pending(
            String callId, String toolName, Map<String, Object> params) {
        return new ParallelToolCall(callId, toolName, params, null, false);
    }

    /** Returns a copy of this call with the execution result filled in. */
    public ParallelToolCall withResult(String observation, boolean success) {
        return new ParallelToolCall(
            this.callId, this.toolName, this.params, observation, success);
    }

    // Static because ParallelToolCall is a record (no CDI injection).
    // Used only for resumeParallelBatch() → invokeTool() serialization.
    // See Architecture_Invariants.md §7.9 (JSON encoding of batch payload).
    private static final ObjectMapper
        PARAMS_MAPPER = new ObjectMapper();

    /** Utility to safely get params as json string if needed */
    public String paramsAsString() {
        if (params == null) return "{}";
        try {
            return PARAMS_MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }
}
