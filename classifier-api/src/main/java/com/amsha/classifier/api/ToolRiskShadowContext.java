package com.amsha.classifier.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

/**
 * Minimum in-process context observed after deterministic policy provisionally allows a tool.
 *
 * <p>The goal and parameter values are sensitive, ephemeral classifier input. Implementations
 * must not log, persist, export, metric-label, or retain them beyond one synchronous evaluation.
 * Nested parameter values must be treated as read-only.
 */
public record ToolRiskShadowContext(
        String agentRole,
        AutonomyLevel autonomyLevel,
        String toolName,
        ToolOperation requiredOperation,
        Map<String, Object> parameters,
        String goal) {

    /** Camel-case field name serialized in a Jev decision state object. */
    public static final String REQUIRED_OPERATION_FIELD = "requiredOperation";

    /** Snake-case key used by evidence corpora and the ONNX input envelope. */
    public static final String REQUIRED_OPERATION_INPUT_KEY = "required_operation";

    public ToolRiskShadowContext {
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
        return "ToolRiskShadowContext[agentRole=" + agentRole + ", autonomyLevel=" + autonomyLevel + ", toolName="
                + toolName + ", requiredOperation=" + requiredOperation + ", parameterCount=" + parameters.size() + "]";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
