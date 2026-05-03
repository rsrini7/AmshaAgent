package com.arcclaw.core.agent.reasoning;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.qute.TemplateData;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single step in the agent's reasoning process.
 * <p>
 * This sealed hierarchy represents the different types of steps an agent can take:
 * <ul>
 *   <li>{@link ToolCallStep} - Calling a tool.</li>
 *   <li>{@link ObservationStep} - Recording an observation or system directive.</li>
 *   <li>{@link FinishStep} - Concluding the task with an answer.</li>
 *   <li>{@link ParallelStep} - Executing multiple tools in parallel.</li>
 * </ul>
 */
@TemplateData
@RegisterForReflection
@JsonDeserialize(using = ReasoningStepDeserializer.class)
public sealed interface ReasoningStep permits 
    ReasoningStep.ToolCallStep, 
    ReasoningStep.ObservationStep, 
    ReasoningStep.FinishStep, 
    ReasoningStep.ParallelStep {

    String thought();
    String action();
    Map<String, Object> actionInput();
    String observation();
    int inputTokens();
    int outputTokens();
    Boolean success();
    String errorCode();
    String traceId();
    String rootCauseClass();
    boolean isFastPath();
    boolean isTruncationRecovery();

    // Sentinel constants
    String FINISH = "FINISH";
    String OBSERVE = "OBSERVE";
    String PARALLEL_SENTINEL_DUNDER = "__parallel__";
    String PARALLEL_SENTINEL_PIPE_PREFIX = "parallel|";
    String ACTION_INPUT_ANSWER_KEY = "answer";

    @JsonIgnore
    default boolean isParallelBatch() {
        return this instanceof ParallelStep;
    }

    /** Tool invocation step. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "stepType")
    @JsonTypeName("TOOL_CALL")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCallStep(
        String thought,
        String action,
        Map<String, Object> actionInput,
        String observation,
        int inputTokens,
        int outputTokens,
        Boolean success,
        String errorCode,
        String traceId,
        String rootCauseClass,
        boolean isFastPath,
        @JsonProperty(defaultValue = "false") boolean isTruncationRecovery
    ) implements ReasoningStep {}

    /** Observation or system directive step. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "stepType")
    @JsonTypeName("OBSERVATION")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ObservationStep(
        String thought,
        String observation,
        int inputTokens,
        int outputTokens,
        Boolean success,
        String errorCode,
        String traceId,
        String rootCauseClass,
        @JsonProperty(defaultValue = "false") boolean isTruncationRecovery
    ) implements ReasoningStep {
        @Override public String action() { return OBSERVE; }
        @Override public Map<String, Object> actionInput() { return Map.of(); }
        @Override public boolean isFastPath() { return false; }
    }

    /** Final conclusion step. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "stepType")
    @JsonTypeName("FINISH")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinishStep(
        String thought,
        Map<String, Object> actionInput,
        int inputTokens,
        int outputTokens,
        @JsonProperty(defaultValue = "false") boolean isTruncationRecovery
    ) implements ReasoningStep {
        @Override public String action() { return FINISH; }
        @Override public String observation() { return null; }
        @Override public Boolean success() { return true; }
        @Override public String errorCode() { return null; }
        @Override public String traceId() { return null; }
        @Override public String rootCauseClass() { return null; }
        @Override public boolean isFastPath() { return false; }
    }

    /** Parallel tool execution batch. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "stepType")
    @JsonTypeName("PARALLEL")
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParallelStep(
        String thought,
        Map<String, Object> actionInput,
        String observation,
        int inputTokens,
        int outputTokens,
        @JsonProperty(defaultValue = "false") boolean isTruncationRecovery
    ) implements ReasoningStep {
        @Override public String action() { return PARALLEL_SENTINEL_DUNDER; }
        @Override public Boolean success() { return null; }
        @Override public String errorCode() { return null; }
        @Override public String traceId() { return null; }
        @Override public String rootCauseClass() { return null; }
        @Override public boolean isFastPath() { return false; }
    }

    // --- Static Factories ---

    static ReasoningStep fastPath(String thought, String action, Map<String, Object> actionInput) {
        return new ToolCallStep(thought, action, actionInput, null, 0, 0, null, null, null, null, true, false);
    }

    static ReasoningStep parallelBatch(String thought, Map<String, Object> batchPayload, int inputTokens, int outputTokens) {
        return new ParallelStep(thought, batchPayload, null, inputTokens, outputTokens, false);
    }

    static ReasoningStep pending(String thought, String action, Map<String, Object> actionInput) {
        if (FINISH.equals(action)) return new FinishStep(thought, actionInput, 0, 0, false);
        return new ToolCallStep(thought, action, actionInput, null, 0, 0, null, null, null, null, false, false);
    }

    static ReasoningStep pending(String thought, String action, Map<String, Object> actionInput, int inputTk, int outputTk) {
        if (FINISH.equals(action)) return new FinishStep(thought, actionInput, inputTk, outputTk, false);
        return new ToolCallStep(thought, action, actionInput, null, inputTk, outputTk, null, null, null, null, false, false);
    }

    static ReasoningStep schemaError(String observation, int inputTk, int outputTk) {
        return new ObservationStep("Parsing Error", observation, inputTk, outputTk, false, "SCHEMA_ERROR", null, null, false);
    }

    static ReasoningStep systemMessage(String observation) {
        return new ObservationStep("System Directive", observation, 0, 0, true, null, null, null, false);
    }

    static ReasoningStep observe(String action, String observation, String thought) {
        return new ToolCallStep(thought, action, Map.of(), observation, 0, 0, true, null, null, null, false, false);
    }

    static ReasoningStep of(String thought, String action, Map<String, Object> actionInput, String observation, int inputTk, int outputTk) {
        return of(thought, action, actionInput, observation, inputTk, outputTk, null, null, null, null);
    }

    static ReasoningStep of(String thought, String action, Map<String, Object> actionInput, String observation, int inputTk, int outputTk, Boolean success) {
        return of(thought, action, actionInput, observation, inputTk, outputTk, success, null, null, null);
    }

    static ReasoningStep of(String thought, String action, Map<String, Object> actionInput, String observation, int inputTk, int outputTk, Boolean success, String errorCode, String traceId, String rootCauseClass) {
        if (FINISH.equals(action)) return new FinishStep(thought, actionInput, inputTk, outputTk, false);
        if (PARALLEL_SENTINEL_DUNDER.equals(action)) return new ParallelStep(thought, actionInput, observation, inputTk, outputTk, false);
        if (OBSERVE.equals(action)) return new ObservationStep(thought, observation, inputTk, outputTk, success, errorCode, traceId, rootCauseClass, false);
        return new ToolCallStep(thought, action, actionInput, observation, inputTk, outputTk, success, errorCode, traceId, rootCauseClass, false, false);
    }

    // --- Copy Methods ---

    default ReasoningStep withObservation(String observation) {
        return withObservation(observation, success());
    }

    default ReasoningStep withObservation(String observation, Boolean success) {
        return switch (this) {
            case ToolCallStep s -> new ToolCallStep(s.thought, s.action, s.actionInput, observation, s.inputTokens, s.outputTokens, success, s.errorCode, s.traceId, s.rootCauseClass, s.isFastPath, s.isTruncationRecovery);
            case ObservationStep s -> new ObservationStep(s.thought, observation, s.inputTokens, s.outputTokens, success, s.errorCode, s.traceId, s.rootCauseClass, s.isTruncationRecovery);
            case FinishStep s -> s;
            case ParallelStep s -> new ParallelStep(s.thought, s.actionInput, observation, s.inputTokens, s.outputTokens, s.isTruncationRecovery);
        };
    }

    default ReasoningStep withDiagnostics(String observation, Boolean success, String errorCode, String traceId, String rootCauseClass) {
         return switch (this) {
            case ToolCallStep s -> new ToolCallStep(s.thought, s.action, s.actionInput, observation, s.inputTokens, s.outputTokens, success, errorCode, traceId, rootCauseClass, s.isFastPath, s.isTruncationRecovery);
            case ObservationStep s -> new ObservationStep(s.thought, observation, s.inputTokens, s.outputTokens, success, errorCode, traceId, rootCauseClass, s.isTruncationRecovery);
            case FinishStep s -> s;
            case ParallelStep s -> new ParallelStep(s.thought, s.actionInput, observation, s.inputTokens, s.outputTokens, s.isTruncationRecovery);
        };
    }

    default ReasoningStep withTruncationRecovery(boolean truncationRecovery) {
        return switch (this) {
            case ToolCallStep s -> new ToolCallStep(s.thought, s.action, s.actionInput, s.observation, s.inputTokens, s.outputTokens, s.success, s.errorCode, s.traceId, s.rootCauseClass, s.isFastPath, truncationRecovery);
            case ObservationStep s -> new ObservationStep(s.thought, s.observation, s.inputTokens, s.outputTokens, s.success, s.errorCode, s.traceId, s.rootCauseClass, truncationRecovery);
            case FinishStep s -> new FinishStep(s.thought, s.actionInput, s.inputTokens, s.outputTokens, truncationRecovery);
            case ParallelStep s -> new ParallelStep(s.thought, s.actionInput, s.observation, s.inputTokens, s.outputTokens, truncationRecovery);
        };
    }
}
