package com.amsha.core.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Immutable result from a tool execution.
 * <p>
 * Use pattern matching to handle variants:
 * {@link ToolSuccess}, {@link ToolFailure}, {@link ToolPending}.
 * <p>
 * Exit code 0 with {@code success=true} indicates clean execution.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolResponse.ToolSuccess.class, name = "success"),
    @JsonSubTypes.Type(value = ToolResponse.ToolFailure.class, name = "failure"),
    @JsonSubTypes.Type(value = ToolResponse.ToolPending.class, name = "pending")
})
public sealed interface ToolResponse
        permits ToolResponse.ToolSuccess, ToolResponse.ToolFailure, ToolResponse.ToolPending {

    /** Common output or message from the tool. */
    String output();

    /** Convenience flag for simple success checks. Use pattern matching where possible. */
    default boolean success() {
        return this instanceof ToolSuccess;
    }

    /** Returns 0 for success/pending, or the variant-specific exit code for failures. */
    default int exitCode() {
        return 0;
    }

    /** Convenience flag for infrastructure failures. */
    default boolean isInfraFailure() {
        return false;
    }

    /** Optional: Categorized error code for diagnostics. */
    default String errorCode() {
        return null;
    }

    /** Optional: Correlation ID for tracking failures. */
    default String traceId() {
        return null;
    }

    /** Optional: Simple name of the original Java exception causing the failure. */
    default String rootCauseClass() {
        return null;
    }

    /** Successful execution with output. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolSuccess(String output) implements ToolResponse {}

    /** Failed execution with detailed diagnostics. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolFailure(
            int exitCode,
            String output,
            boolean isInfraFailure,
            String errorCode,
            String traceId,
            String rootCauseClass)
            implements ToolResponse {
        @Override
        public boolean success() {
            return false;
        }
    }

    /** HITL pause - tool execution is pending user approval. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolPending(String output, String approvalToken) implements ToolResponse {
        @Override
        public boolean success() {
            return false;
        }
    }

    /** Successful execution with output. */
    static ToolResponse success(String output) {
        return new ToolSuccess(output);
    }

    /** Failed execution with exit code and error output. */
    static ToolResponse failure(int exitCode, String output) {
        return new ToolFailure(exitCode, output, false, null, null, null);
    }

    /** Extended failure with diagnostics metadata. */
    static ToolResponse failure(int exitCode, String output, String errorCode, String traceId, String rootCauseClass) {
        return new ToolFailure(exitCode, output, false, errorCode, traceId, rootCauseClass);
    }

    /** Infrastructure failure - sandbox/external system is offline. */
    static ToolResponse infraFailure(String reason) {
        return new ToolFailure(1, reason, true, "INFRA_FAILURE", null, null);
    }

    /** HITL pause - tool execution is pending user approval. */
    static ToolResponse pending(String output, String approvalToken) {
        return new ToolPending(output, approvalToken);
    }
}
