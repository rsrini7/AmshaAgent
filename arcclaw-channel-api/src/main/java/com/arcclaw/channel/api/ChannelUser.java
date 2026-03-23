package com.arcclaw.channel.api;

/**
 * Immutable identity of a user on a specific channel.
 *
 * @param platformId  Platform-native user identifier (e.g. Telegram numeric user ID as string).
 *                    Must be non-null and non-blank.
 * @param channelId   Platform channel/chat identifier (e.g. Telegram chat ID). Must be non-null and non-blank.
 * @param displayName Human-readable name shown in replies. Defaults to {@code "unknown"} if null.
 * @param channel     The originating channel. Must be non-null.
 */
public record ChannelUser(String platformId, String channelId, String displayName, ChannelType channel) {

    public ChannelUser {
        if (platformId == null || platformId.isBlank()) {
            throw new IllegalArgumentException("ChannelUser.platformId must not be null or blank");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("ChannelUser.channelId must not be null or blank");
        }
        if (channel == null) {
            throw new IllegalArgumentException("ChannelUser.channel must not be null");
        }
        if (displayName == null) {
            displayName = "unknown";
        }
    }
}
