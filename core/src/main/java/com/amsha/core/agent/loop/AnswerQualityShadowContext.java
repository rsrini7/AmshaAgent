package com.amsha.core.agent.loop;

import java.util.Objects;

/** Minimum in-process context observed after the authoritative answer guard accepts. */
public record AnswerQualityShadowContext(String agentRole, String goal, String answer, boolean toolInvokedInTurn) {

    public AnswerQualityShadowContext {
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
