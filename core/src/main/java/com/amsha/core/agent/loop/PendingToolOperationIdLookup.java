package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.amsha.core.agent.reasoning.ReasoningStep;

/** Resolves the persisted identity for a pending sequential checkpoint tool call. */
final class PendingToolOperationIdLookup {
    private PendingToolOperationIdLookup() {}

    static Optional<String> find(List<ReasoningStep> history, String toolName, Map<String, Object> parameters) {
        if (history == null || toolName == null || parameters == null) {
            return Optional.empty();
        }
        int matchingIndex = -1;
        for (int index = 0; index < history.size(); index++) {
            ReasoningStep step = history.get(index);
            if (step instanceof ReasoningStep.ToolCallStep call
                    && Objects.equals(toolName, call.action())
                    && call.observation() == null
                    && Objects.equals(parameters, call.actionInput())) {
                if (matchingIndex >= 0) {
                    return Optional.empty();
                }
                matchingIndex = index;
            }
        }
        if (matchingIndex < 0) {
            return Optional.empty();
        }
        String operationId = history.get(matchingIndex).operationId();
        if (operationId == null || findUniquePendingIndex(history, operationId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(operationId);
    }

    static OptionalInt findUniquePendingIndex(List<ReasoningStep> history, String operationId) {
        if (history == null || operationId == null) {
            return OptionalInt.empty();
        }
        int matchingIndex = -1;
        for (int index = 0; index < history.size(); index++) {
            ReasoningStep step = history.get(index);
            if (step instanceof ReasoningStep.ToolCallStep call
                    && call.observation() == null
                    && Objects.equals(operationId, call.operationId())) {
                if (matchingIndex >= 0) {
                    return OptionalInt.empty();
                }
                matchingIndex = index;
            }
        }
        return matchingIndex < 0 ? OptionalInt.empty() : OptionalInt.of(matchingIndex);
    }
}
