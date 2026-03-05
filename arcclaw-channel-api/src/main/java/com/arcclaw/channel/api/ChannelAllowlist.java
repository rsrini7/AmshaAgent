package com.arcclaw.channel.api;

/**
 * Channel-agnostic allowlist contract.
 *
 * <p>Implementations are channel-specific (e.g. {@code TelegramAllowlistGuard}).
 * The {@code platformUserId} is always passed as a String so that the interface
 * remains type-agnostic across channels (Telegram uses numeric IDs, Discord uses
 * snowflakes, etc.).
 *
 * @see com.arcclaw.app.security.TelegramAllowlistGuard
 */
public interface ChannelAllowlist {

    /**
     * Returns {@code true} if the given user is permitted to interact with the agent.
     *
     * @param platformUserId platform-native user identifier as a string (never null)
     * @param username       display username, may be null or blank
     */
    boolean isAllowed(String platformUserId, String username);

    /**
     * If {@code true}, blocked users receive no response (silent drop).
     * If {@code false}, the response from {@link #getBlockResponse()} is sent.
     */
    boolean isSilentBlock();

    /** Message sent to blocked users when {@link #isSilentBlock()} is {@code false}. */
    String getBlockResponse();
}
