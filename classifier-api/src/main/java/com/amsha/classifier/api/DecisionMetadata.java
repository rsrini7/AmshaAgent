package com.amsha.classifier.api;

import java.time.Duration;

/**
 * Payload-free execution metadata for decision outcomes.
 * All fields are optional to preserve absent provider telemetry without inventing synthetic numbers.
 *
 * @param providerId decision provider identifier (e.g. "onnx-local", "typesafe")
 * @param modelId exact versioned model identifier (e.g. "jev-1.13.0")
 * @param latency total evaluation elapsed duration
 * @param inputTokens token count consumed for state and questions, if reported
 * @param outputTokens token count emitted, if reported
 */
public record DecisionMetadata(
        String providerId, String modelId, Duration latency, Long inputTokens, Long outputTokens) {

    public DecisionMetadata {
        if (providerId != null && providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank if present");
        }
        if (modelId != null && modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank if present");
        }
        if (latency != null && latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
        if (inputTokens != null && inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must not be negative");
        }
        if (outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must not be negative");
        }
    }

    public static DecisionMetadata empty() {
        return new DecisionMetadata(null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "DecisionMetadata[provider=" + (providerId != null ? providerId : "none")
                + ", model=" + (modelId != null ? modelId : "none")
                + ", latencyMs=" + (latency != null ? latency.toMillis() : -1)
                + ", inTokens=" + (inputTokens != null ? inputTokens : -1)
                + ", outTokens=" + (outputTokens != null ? outputTokens : -1) + "]";
    }
}
