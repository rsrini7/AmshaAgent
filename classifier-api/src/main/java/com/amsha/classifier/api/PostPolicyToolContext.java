package com.amsha.classifier.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

/**
 * Minimum immutable context exposed only after deterministic policy provisionally allows a tool.
 *
 * <p>The goal and parameter values are sensitive, ephemeral classifier input. Implementations
 * must not log, persist, export, metric-label, or retain them beyond one synchronous evaluation.
 * Nested parameter values must be treated as read-only.
 */
public record PostPolicyToolContext(
        String agentRole,
        AutonomyLevel autonomyLevel,
        String toolName,
        ToolOperation requiredOperation,
        Map<String, Object> parameters,
        String goal,
        boolean toolInvokedInTurn) {

    public PostPolicyToolContext {
        agentRole = requireNonBlank(agentRole, "agentRole");
        Objects.requireNonNull(autonomyLevel, "autonomyLevel");
        toolName = requireNonBlank(toolName, "toolName");
        Objects.requireNonNull(requiredOperation, "requiredOperation");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
        goal = Objects.requireNonNullElse(goal, "");
    }

    /** Returns a payload-free diagnostic summary. */
    @Override
    public String toString() {
        return "PostPolicyToolContext[agentRole=" + agentRole + ", autonomyLevel=" + autonomyLevel + ", toolName="
                + toolName + ", requiredOperation=" + requiredOperation + ", parameterCount=" + parameters.size()
                + ", toolInvokedInTurn=" + toolInvokedInTurn
                + "]";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
