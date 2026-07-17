package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Durable, non-secret metadata for an LLM API connection.
 *
 * <p>Auth material is never stored here; only adapter-declared secret-slot references
 * ({@link LlmConnectionSecretRefDto}) point at {@code SecretVault} partitions.</p>
 */
public record LlmConnectionDto(
        String id,
        String scopeType,
        String scopeId,
        String createdByUserId,
        String displayName,
        String providerAdapterId,
        String endpointProfileId,
        String customBaseUrl,
        String authScheme,
        String networkProfileJson,
        String state,
        int revision,
        Instant createdAt,
        Instant updatedAt) {

    public LlmConnectionDto {
        requireText(id, "id");
        requireText(scopeType, "scopeType");
        requireText(scopeId, "scopeId");
        requireText(createdByUserId, "createdByUserId");
        requireText(displayName, "displayName");
        requireText(providerAdapterId, "providerAdapterId");
        requireText(state, "state");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
