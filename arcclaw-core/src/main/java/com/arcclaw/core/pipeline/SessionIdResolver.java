package com.arcclaw.core.pipeline;

/**
 * Resolves a stable session ID for a (channelType, channelId, userId) triplet.
 * <p>
 * The default implementation in {@code arcclaw-core} uses deterministic UUID v3
 * (MD5). The production implementation in {@code arcclaw-app} is
 * {@code SessionRegistry}, which uses a persistent table and UUID v4.
 * <p>
 * CDI will inject the highest-priority available implementation at runtime.
 */
public interface SessionIdResolver {
    /**
     * Resolves or creates a stable session ID for the given triplet.
     *
     * @param channelType channel type name (e.g. "TELEGRAM", "CLI")
     * @param channelId   platform channel/chat identifier
     * @param userId      platform user identifier
     * @return a stable session ID string
     */
    String resolveOrCreate(String channelType, String channelId, String userId);
}
