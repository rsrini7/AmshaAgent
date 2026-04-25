package com.arcclaw.core.agent.reasoning;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.qute.TemplateData;

/**
 * One tick of the Thought-Plan-Act-Observe reasoning loop.
 * <p>
 * Each step captures the agent's internal reasoning ({@code thought}),
 * the chosen action ({@code action} — a tool name or {@code "FINISH"}),
 * the parameters for that action ({@code actionInput}), and the
 * result observed after execution ({@code observation}).
 *
 * @param thought         The agent's internal reasoning for this step.
 * @param action          Tool name to invoke, or {@code "FINISH"} to end the loop.
 * @param actionInput     Parameters for the tool (may be empty for FINISH).
 * @param observation     The result of executing the action (filled by the loop).
 * @param inputTokens     LLM input tokens consumed in this step.
 * @param outputTokens    LLM output tokens generated in this step.
 * @param success         Optional: True if the tool execution succeeded.
 * @param errorCode       Optional: Categorized error code for diagnostics.
 * @param traceId         Optional: OTel traceId for log correlation.
 * @param rootCauseClass  Optional: Simple name of the Java exception causing a failure.
 */
@TemplateData
public record ReasoningStep(
        String thought,
        String action,
        Map<String, Object> actionInput,
        String observation,
        int inputTokens,
        int outputTokens,
        Boolean success,
        String errorCode,
        String traceId,
        String rootCauseClass) {

    /** Action value indicating the loop should terminate with a final answer. */
    public static final String FINISH = "FINISH";

    /** Action value indicating a system-level observation (e.g. error) needs LLM correction. */
    public static final String OBSERVE = "OBSERVE";

    public static final String ACTION_INPUT_ANSWER_KEY = "answer";

    /**
     * Sentinel action for a parallel LLM turn (N tool calls in one response).
     * actionInput.get("batch") contains the JSON-serialized List<ParallelToolCall>
     * with fields: callId, toolName, params (pre-execution), observation (post-execution).
     * See Architecture_Invariants.md §7.1.
     */
    public static final String PARALLEL_SENTINEL_DUNDER = "__parallel__";

    public static final String PARALLEL_SENTINEL_PIPE_PREFIX = "parallel|";

    /** Convenience: true if this step represents a parallel tool batch. */
    @JsonIgnore
    public boolean isParallelBatch() {
        return PARALLEL_SENTINEL_DUNDER.equals(this.action);
    }


    /**
     * Creates a pre-execution parallel batch step.
     * observation is empty until ParallelToolStepExecutor completes.
     */
    public static ReasoningStep parallelBatch(
            String thought,
            Map<String, Object> batchPayload,
            int inputTokens,
            int outputTokens) {
        return new ReasoningStep(
            thought,
            PARALLEL_SENTINEL_DUNDER,
            batchPayload,
            null,          // observation filled in after execution
            inputTokens,
            outputTokens,
            null, null, null, null
        );
    }

    /**
     * Create a step with no observation yet (pre-execution). Tokens are typically 0
     * until known.
     */
    public static ReasoningStep pending(String thought, String action, Map<String, Object> actionInput) {
        return new ReasoningStep(thought, action, actionInput, null, 0, 0, null, null, null, null);
    }

    /** Create a step with tokens explicitly provided. */
    public static ReasoningStep pending(String thought, String action, Map<String, Object> actionInput, int inputTk,
            int outputTk) {
        return new ReasoningStep(thought, action, actionInput, null, inputTk, outputTk, null, null, null, null);
    }

    /** Create a step representing a schema/parsing error to trigger LLM retry. */
    public static ReasoningStep schemaError(String observation, int inputTk, int outputTk) {
        return new ReasoningStep("Parsing Error", OBSERVE, Map.of(), observation, inputTk, outputTk, false, "SCHEMA_ERROR", null, null);
    }

    /**
     * Create a step representing a system-level directive injected into history.
     */
    public static ReasoningStep systemMessage(String observation) {
        return new ReasoningStep("System Directive", OBSERVE, Map.of(), observation, 0, 0, true, null, null, null);
    }

    /** Create a step representing an observation of an action result. */
    public static ReasoningStep observe(String action, String observation, String thought) {
        return new ReasoningStep(thought, action, Map.of(), observation, 0, 0, true, null, null, null);
    }

    /** Create a copy of this step with the observation filled in, without success metadata. */
    public ReasoningStep withObservation(String observation) {
        return new ReasoningStep(this.thought, this.action, this.actionInput, observation, this.inputTokens,
                this.outputTokens, this.success, this.errorCode, this.traceId, this.rootCauseClass);
    }

    /** Create a copy of this step with the observation and success metadata filled in. */
    public ReasoningStep withObservation(String observation, Boolean success) {
        return new ReasoningStep(this.thought, this.action, this.actionInput, observation, this.inputTokens,
                this.outputTokens, success, this.errorCode, this.traceId, this.rootCauseClass);
    }

    /** Create a copy of this step with full diagnostics metadata. */
    public ReasoningStep withDiagnostics(String observation, Boolean success, String errorCode, String traceId, String rootCauseClass) {
        return new ReasoningStep(this.thought, this.action, this.actionInput, observation, this.inputTokens,
                this.outputTokens, success, errorCode, traceId, rootCauseClass);
    }

    /** Mapping factory: constructs a ReasoningStep from all its constituent fields (legacy). */
    public static ReasoningStep of(String thought, String action, Map<String, Object> actionInput, String observation,
            int inputTk, int outputTk) {
        return new ReasoningStep(thought, action, actionInput, observation, inputTk, outputTk, null, null, null, null);
    }

    /** Mapping factory: constructs a ReasoningStep from all its constituent fields. */
    public static ReasoningStep of(String thought, String action, Map<String, Object> actionInput, String observation,
            int inputTk, int outputTk, Boolean success) {
        return new ReasoningStep(thought, action, actionInput, observation, inputTk, outputTk, success, null, null, null);
    }

    /** Mapping factory: constructs a ReasoningStep with diagnostics. */
    public static ReasoningStep of(String thought, String action, Map<String, Object> actionInput, String observation,
            int inputTk, int outputTk, Boolean success, String errorCode, String traceId, String rootCauseClass) {
        return new ReasoningStep(thought, action, actionInput, observation, inputTk, outputTk, success, errorCode, traceId, rootCauseClass);
    }
}
