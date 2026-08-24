package com.amsha.classifier.api;

import java.util.Objects;

/**
 * Minimum in-process context observed after the authoritative answer guard accepts.
 *
 * <p>The goal and answer are sensitive, ephemeral classifier input. Implementations must not
 * log, persist, export, metric-label, or retain them beyond one synchronous evaluation.
 */
public record AnswerQualityShadowContext(String agentRole, String goal, String answer, boolean toolInvokedInTurn) {

    public AnswerQualityShadowContext {
        agentRole = requireNonBlank(agentRole, "agentRole");
        goal = Objects.requireNonNullElse(goal, "");
        Objects.requireNonNull(answer, "answer");
    }

    /** Returns a payload-free diagnostic summary. */
    @Override
    public String toString() {
        return "AnswerQualityShadowContext[agentRole=" + agentRole + ", toolInvokedInTurn=" + toolInvokedInTurn + "]";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
