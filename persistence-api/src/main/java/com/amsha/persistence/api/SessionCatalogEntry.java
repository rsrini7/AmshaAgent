package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;

/** Durable, scoped metadata for a conversation session. */
public record SessionCatalogEntry(
        String id,
        String ownerKey,
        String channelType,
        String channelId,
        String sessionId,
        String role,
        String goalPreview,
        Instant createdAt,
        Instant lastUsedAt,
        int seq,
        boolean active,
        String status) {

    public SessionCatalogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(channelType, "channelType");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(status, "status");
        if (goalPreview != null && goalPreview.length() > 140) {
            goalPreview = goalPreview.substring(0, 140);
        }
    }
}
