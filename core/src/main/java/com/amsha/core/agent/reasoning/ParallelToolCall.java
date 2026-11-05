package com.amsha.core.agent.reasoning;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

import com.amsha.core.tool.attempt.ToolOperationIdentity;

/**
 * Represents one tool call within a parallel LLM response batch.
 * Immutable. Pre-execution: observation is null.
 * Post-execution: observation holds the sanitized ToolResponse output.
 *
 * Serialized to/from JSON via ObjectMapper as the "batch" key in
 * ReasoningStep.actionInput for Architecture_Invariants.md §7.1.
 */
public record ParallelToolCall(
        String callId, // stable ID: "call_0", "call_1", ...
        String toolName, // LLM-requested tool name
        Map<String, Object> params, // deserialized tool parameters
        String paramsJson, // §7.1: serialized JSON of params
        String observation, // null pre-execution; filled post-execution
        boolean success, // false pre-execution; set post-execution
        @Nullable String operationId // stable server-generated logical operation identity
        ) {
    public ParallelToolCall {
        if (operationId != null) {
            ToolOperationIdentity.validateCanonicalUuid(operationId, "operationId");
        }
    }

    /** Compatibility constructor for callers that do not attach operation identity yet. */
    public ParallelToolCall(
            String callId,
            String toolName,
            Map<String, Object> params,
            String paramsJson,
            String observation,
            boolean success) {
        this(callId, toolName, params, paramsJson, observation, success, null);
    }
    /** Creates a pre-execution call with null observation. */
    public static ParallelToolCall pending(
            String callId, String toolName, Map<String, Object> params, String paramsJson) {
        return new ParallelToolCall(callId, toolName, params, paramsJson, null, false);
    }

    /** Returns a copy of this call with the execution result filled in. */
    public ParallelToolCall withResult(String observation, boolean success) {
        return new ParallelToolCall(
                this.callId, this.toolName, this.params, this.paramsJson, observation, success, this.operationId);
    }

    /** Returns a copy carrying the orchestration-owned operation identity. */
    public ParallelToolCall withOperationId(String operationId) {
        ToolOperationIdentity.validateCanonicalUuid(operationId, "operationId");
        return new ParallelToolCall(
                this.callId, this.toolName, this.params, this.paramsJson, this.observation, this.success, operationId);
    }

    /** Assigns missing IDs for programmatically constructed pending batch calls. */
    public static List<ParallelToolCall> ensureOperationIds(List<ParallelToolCall> calls) {
        return calls.stream()
                .map(call -> call.operationId() == null
                        ? call.withOperationId(ToolOperationIdentity.generateCanonicalUuid())
                        : call)
                .toList();
    }
}
