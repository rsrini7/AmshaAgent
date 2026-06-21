package com.arcclaw.channel.api;

import java.util.concurrent.CompletableFuture;

/**
 * Core services available to channel implementations.
 * Allows channels to interact with the ArcClaw engine without depending on core internals.
 */
public interface ChannelServices {

    /**
     * Resolves an existing sessionId or creates a new one for the given identity triplet.
     */
    String resolveOrCreateSession(String channelType, String channelId, String userId);

    /**
     * Ingests a message into the processing pipeline asynchronously.
     * Propagates security and session context.
     */
    CompletableFuture<Void> processAsync(ChannelMessage message);

    /**
     * Signals that a channel-side session (e.g. WebSocket connection) has closed
     * and any associated workspace resources should be released.
     */
    void terminateSession(String sessionId);

    /**
     * Proactively records a heartbeat for the session, initializing its
     * in-memory presence and autonomy level.
     */
    void recordHeartbeat(String sessionId);

    /**
     * Binds a human-friendly owner (userId / key label) to the given session.
     * Required for proper tenant isolation of REST/HITL endpoints accessed
     * by channel-created sessions (e.g. WebSocket).
     */
    void bindSessionOwner(String sessionId, String ownerKeyName);

    /**
     * Checks whether a raw identity credential (e.g. API key) is valid.
     */
    boolean isValidIdentity(String credential);
}
