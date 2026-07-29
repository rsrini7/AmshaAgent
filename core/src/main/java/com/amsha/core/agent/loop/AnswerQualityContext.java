package com.amsha.core.agent.loop;

import java.util.Objects;

/** Minimum immutable context exposed after the authoritative answer guard accepts a candidate. */
public record AnswerQualityContext(String agentRole, String goal, String answer, boolean toolInvokedInTurn) {

    public AnswerQualityContext {
        agentRole = requireNonBlank(agentRole, "agentRole");
        goal = Objects.requireNonNullElse(goal, "");
        Objects.requireNonNull(answer, "answer");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
