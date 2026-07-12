package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Backend-neutral catalog of sessions scoped to an owner and channel. */
public interface SessionCatalogStore {
    void record(SessionCatalogEntry entry);

    void touch(
            String sessionId, String ownerKey, String channelType, String channelId, String role, String goalPreview);

    Optional<SessionCatalogEntry> resolveActive(String ownerKey, String channelType, String channelId);

    void activateNew(SessionCatalogEntry entry);

    void setActive(String sessionId, boolean active);

    List<SessionCatalogEntry> listByScope(String ownerKey, String channelType, String channelId);

    List<SessionCatalogEntry> listInactiveBefore(Instant cutoff);

    Optional<SessionCatalogEntry> latestByScope(String ownerKey, String channelType, String channelId);

    Optional<SessionCatalogEntry> bySessionId(String sessionId);

    Optional<SessionCatalogEntry> bySequence(String ownerKey, String channelType, String channelId, int seq);

    void archive(String sessionId);
}
