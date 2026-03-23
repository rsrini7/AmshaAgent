package com.arcclaw.channel.api;

import java.util.Optional;

/**
 * Identifies the originating channel for a message or command.
 * Used by {@link ChannelSender} and {@link ReplyRenderer} to dispatch to
 * the correct adapter without instanceof checks.
 */
public enum ChannelType {
    TELEGRAM,
    DISCORD,
    SLACK,
    WEBSOCKET,
    REST_API,
    CLI;

    /**
     * Parse from a string.
     * Returns Optional.empty() if the value is null, blank, or unrecognized.
     */
    public static Optional<ChannelType> fromString(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(ChannelType.valueOf(value.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
