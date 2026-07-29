package com.amsha.core.agent.loop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.amsha.core.security.AutonomyLevel;

/** Minimum immutable context exposed only after deterministic policy provisionally allows a tool. */
public record PostPolicyToolContext(
        String agentRole,
        AutonomyLevel autonomyLevel,
        String toolName,
        Map<String, Object> parameters,
        String goal,
        boolean toolInvokedInTurn) {

    public PostPolicyToolContext {
        agentRole = requireNonBlank(agentRole, "agentRole");
        Objects.requireNonNull(autonomyLevel, "autonomyLevel");
        toolName = requireNonBlank(toolName, "toolName");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
        goal = Objects.requireNonNullElse(goal, "");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
