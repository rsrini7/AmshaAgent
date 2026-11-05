package com.amsha.core.agent.reasoning;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amsha.core.agent.reasoning.ReasoningStep.FinishStep;
import com.amsha.core.agent.reasoning.ReasoningStep.ObservationStep;
import com.amsha.core.agent.reasoning.ReasoningStep.ParallelStep;
import com.amsha.core.agent.reasoning.ReasoningStep.ToolCallStep;
import com.amsha.core.tool.attempt.ToolOperationIdentity;

/** Operations shared by the immutable reasoning-step record variants. */
public final class ReasoningStepSupport {

    private ReasoningStepSupport() {}

    public static boolean isParallelBatch(ReasoningStep step) {
        return step instanceof ParallelStep;
    }

    public static boolean isToolCallStep(ReasoningStep step) {
        return step instanceof ToolCallStep;
    }

    public static ReasoningStep withOperationId(ReasoningStep input, String operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        ToolOperationIdentity.validateCanonicalUuid(operationId, "operationId");
        if (!(input instanceof ToolCallStep step)) {
            throw new IllegalStateException("Operation identity can only be attached to a tool call step");
        }
        return new ToolCallStep(
                step.thought(),
                step.action(),
                step.actionInput(),
                step.observation(),
                step.inputTokens(),
                step.outputTokens(),
                step.success(),
                step.errorCode(),
                step.traceId(),
                step.rootCauseClass(),
                step.isFastPath(),
                step.isTruncationRecovery(),
                operationId);
    }

    public static ReasoningStep withParallelCalls(ReasoningStep input, List<ParallelToolCall> calls) {
        if (!(input instanceof ParallelStep step)) {
            throw new IllegalStateException("Parallel calls can only be attached to a parallel step");
        }
        Map<String, Object> actionInput = new HashMap<>(step.actionInput() == null ? Map.of() : step.actionInput());
        actionInput.put(ReasoningStep.PARALLEL_BATCH_ACTION_INPUT_KEY, List.copyOf(calls));
        return new ParallelStep(
                step.thought(),
                Collections.unmodifiableMap(actionInput),
                step.observation(),
                step.inputTokens(),
                step.outputTokens(),
                step.success(),
                step.isTruncationRecovery());
    }

    public static ReasoningStep withObservation(ReasoningStep input, String observation, Boolean success) {
        return switch (input) {
            case ToolCallStep step ->
                new ToolCallStep(
                        step.thought(),
                        step.action(),
                        step.actionInput(),
                        observation,
                        step.inputTokens(),
                        step.outputTokens(),
                        success,
                        step.errorCode(),
                        step.traceId(),
                        step.rootCauseClass(),
                        step.isFastPath(),
                        step.isTruncationRecovery(),
                        step.operationId());
            case ObservationStep step ->
                new ObservationStep(
                        step.thought(),
                        observation,
                        step.inputTokens(),
                        step.outputTokens(),
                        success,
                        step.errorCode(),
                        step.traceId(),
                        step.rootCauseClass(),
                        step.isTruncationRecovery());
            case FinishStep step -> step;
            case ParallelStep step ->
                new ParallelStep(
                        step.thought(),
                        step.actionInput(),
                        observation,
                        step.inputTokens(),
                        step.outputTokens(),
                        success,
                        step.isTruncationRecovery());
        };
    }

    public static ReasoningStep withDiagnostics(
            ReasoningStep input,
            String observation,
            Boolean success,
            String errorCode,
            String traceId,
            String rootCauseClass) {
        return switch (input) {
            case ToolCallStep step ->
                new ToolCallStep(
                        step.thought(),
                        step.action(),
                        step.actionInput(),
                        observation,
                        step.inputTokens(),
                        step.outputTokens(),
                        success,
                        errorCode,
                        traceId,
                        rootCauseClass,
                        step.isFastPath(),
                        step.isTruncationRecovery(),
                        step.operationId());
            case ObservationStep step ->
                new ObservationStep(
                        step.thought(),
                        observation,
                        step.inputTokens(),
                        step.outputTokens(),
                        success,
                        errorCode,
                        traceId,
                        rootCauseClass,
                        step.isTruncationRecovery());
            case FinishStep step -> step;
            case ParallelStep step ->
                new ParallelStep(
                        step.thought(),
                        step.actionInput(),
                        observation,
                        step.inputTokens(),
                        step.outputTokens(),
                        success,
                        step.isTruncationRecovery());
        };
    }

    public static ReasoningStep withTruncationRecovery(ReasoningStep input, boolean truncationRecovery) {
        return switch (input) {
            case ToolCallStep step ->
                new ToolCallStep(
                        step.thought(),
                        step.action(),
                        step.actionInput(),
                        step.observation(),
                        step.inputTokens(),
                        step.outputTokens(),
                        step.success(),
                        step.errorCode(),
                        step.traceId(),
                        step.rootCauseClass(),
                        step.isFastPath(),
                        truncationRecovery,
                        step.operationId());
            case ObservationStep step ->
                new ObservationStep(
                        step.thought(),
                        step.observation(),
                        step.inputTokens(),
                        step.outputTokens(),
                        step.success(),
                        step.errorCode(),
                        step.traceId(),
                        step.rootCauseClass(),
                        truncationRecovery);
            case FinishStep step ->
                new FinishStep(
                        step.thought(),
                        step.actionInput(),
                        step.inputTokens(),
                        step.outputTokens(),
                        truncationRecovery);
            case ParallelStep step ->
                new ParallelStep(
                        step.thought(),
                        step.actionInput(),
                        step.observation(),
                        step.inputTokens(),
                        step.outputTokens(),
                        step.success(),
                        truncationRecovery);
        };
    }
}
