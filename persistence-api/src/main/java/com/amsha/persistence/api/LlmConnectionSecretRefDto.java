package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Opaque, non-secret reference from a connection to a vault-stored credential slot.
 *
 * <p>The {@code slotId} is an adapter-declared closed identifier; {@code vaultRef} is an opaque
 * key into the connection's authorized {@code USER:} or {@code WORKSPACE:} vault partition.</p>
 */
public record LlmConnectionSecretRefDto(
        String connectionId, String slotId, String vaultRef, int revision, Instant createdAt, Instant updatedAt) {

    public LlmConnectionSecretRefDto {
        requireText(connectionId, "connectionId");
        requireText(slotId, "slotId");
        requireText(vaultRef, "vaultRef");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
