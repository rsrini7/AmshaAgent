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
    CLI
}
