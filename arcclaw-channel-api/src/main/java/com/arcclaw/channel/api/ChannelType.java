package com.arcclaw.channel.api;

import java.util.Optional;

/**
 * Identifies the originating channel for a message or command.
 * Used by {@link ChannelSender} and {@link ReplyRenderer} to dispatch to
 * the correct adapter without instanceof checks.
 */
public record ChannelType(String id) {

    public ChannelType {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ChannelType id must not be null or blank");
        }
        id = id.toLowerCase().trim();
    }

    public static final ChannelType TELEGRAM   = new ChannelType("telegram");
    public static final ChannelType DISCORD    = new ChannelType("discord");
    public static final ChannelType SLACK      = new ChannelType("slack");
    public static final ChannelType WEBSOCKET  = new ChannelType("websocket");
    public static final ChannelType REST_API   = new ChannelType("rest_api");
    public static final ChannelType CLI        = new ChannelType("cli");

    /**
     * @return Uppercased id for backward compatibility with ChannelType enum name() callers.
     */
    public String name() {
        return id.toUpperCase();
    }

    public static Optional<ChannelType> fromString(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(new ChannelType(value.toLowerCase().trim()));
    }

    @Override
    public String toString() {
        return id;
    }
}
