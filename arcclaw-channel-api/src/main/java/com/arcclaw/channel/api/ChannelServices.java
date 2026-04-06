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
}
