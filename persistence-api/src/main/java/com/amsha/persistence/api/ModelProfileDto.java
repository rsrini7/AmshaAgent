package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Durable, non-secret model profile attached to a connection.
 */
public record ModelProfileDto(
        String id,
        String connectionId,
        String modelId,
        String structuredOutputMode,
        String capabilityJson,
        String safeLimitsJson,
        boolean enabled,
        int revision,
        Instant createdAt,
        Instant updatedAt) {

    public ModelProfileDto {
        requireText(id, "id");
        requireText(connectionId, "connectionId");
        requireText(modelId, "modelId");
        if (structuredOutputMode == null) {
            structuredOutputMode = "PROMPT_DIRECTIVE";
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
