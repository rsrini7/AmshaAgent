package com.amsha.core.agent.loop;

import java.util.Objects;

/**
 * Minimum immutable context exposed after the authoritative answer guard accepts a candidate.
 *
 * <p>The goal and answer are sensitive, ephemeral classifier input. Implementations must not
 * log, persist, export, metric-label, or retain them beyond one synchronous evaluation.
 */
public record AnswerQualityContext(String agentRole, String goal, String answer, boolean toolInvokedInTurn) {

    public AnswerQualityContext {
        agentRole = requireNonBlank(agentRole, "agentRole");
        goal = Objects.requireNonNullElse(goal, "");
        Objects.requireNonNull(answer, "answer");
    }

    /** Returns a payload-free diagnostic summary. */
    @Override
    public String toString() {
        return "AnswerQualityContext[agentRole=" + agentRole + ", toolInvokedInTurn=" + toolInvokedInTurn + "]";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
