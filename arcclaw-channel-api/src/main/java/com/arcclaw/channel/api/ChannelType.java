package com.arcclaw.channel.api;

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
     * Safe parse from a DB-sourced string.
     * Returns TELEGRAM if the value is null, blank, or unrecognized —
     * prevents IllegalArgumentException on legacy rows.
     */
    public static ChannelType fromString(String value) {
        if (value == null || value.isBlank()) return TELEGRAM;
        try {
            return ChannelType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TELEGRAM;
        }
    }
}
