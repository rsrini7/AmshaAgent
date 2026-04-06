package com.arcclaw.channel.api;

import java.time.Instant;

/**
 * Channel-agnostic representation of an inbound user message.
 *
 * <p>Common accessors are declared on the sealed interface; channel-specific
 * fields live on each permit record.
 */
public sealed interface ChannelMessage
        permits ChannelMessage.TelegramMessage,
                ChannelMessage.DiscordMessage,
                ChannelMessage.RestApiMessage,
                ChannelMessage.CliMessage,
                ChannelMessage.WebSocketMessage {

    /** The user who sent this message. */
    ChannelUser sender();

    /** Raw text content of the message. May be null for non-text messages. */
    String text();

    /**
     * Opaque correlation ID for tracing. For Telegram this is the chat ID;
     * for REST it is the session ID; for CLI it is a generated UUID.
     */
    String correlationId();

    /** Wall-clock time at which the message was received by the channel adapter. */
    Instant receivedAt();

    /** The channel the message arrived on. */
    ChannelType channelType();

    // ------------------------------------------------------------------
    // Permit records
    // ------------------------------------------------------------------

    /**
     * A message received from the Telegram Bot API.
     *
     * @param sender        the Telegram user
     * @param text          raw message text
     * @param chatId        Telegram chat ID (used as correlationId)
     * @param receivedAt    ingestion timestamp
     */
    record TelegramMessage(
            ChannelUser sender,
            String text,
            String chatId,
            Instant receivedAt
    ) implements ChannelMessage {
        @Override public String correlationId() { return chatId; }
        @Override public ChannelType channelType() { return ChannelType.TELEGRAM; }
    }

    /**
     * A message received from Discord.
     *
     * @param sender        the Discord user
     * @param text          raw message text
     * @param guildId       Discord guild (server) ID
     * @param channelId     Discord text-channel ID (used as correlationId)
     * @param receivedAt    ingestion timestamp
     */
    record DiscordMessage(
            ChannelUser sender,
            String text,
            String guildId,
            String channelId,
            Instant receivedAt
    ) implements ChannelMessage {
        @Override public String correlationId() { return channelId; }
        @Override public ChannelType channelType() { return ChannelType.DISCORD; }
    }

    /**
     * A message received via the REST API.
     *
     * @param sender        synthetic user based on the API key
     * @param text          the request goal/body
     * @param apiKeyId      the resolved API key identifier (used as correlationId)
     * @param receivedAt    ingestion timestamp
     */
    record RestApiMessage(
            ChannelUser sender,
            String text,
            String apiKeyId,
            Instant receivedAt
    ) implements ChannelMessage {
        @Override public String correlationId() { return apiKeyId; }
        @Override public ChannelType channelType() { return ChannelType.REST_API; }
    }

    /**
     * A message received from the CLI (picocli {@code ask} command).
     *
     * @param sender     synthetic CLI user
     * @param text       the goal string
     * @param sessionId  the generated CLI session ID (used as correlationId)
     * @param receivedAt ingestion timestamp
     */
    record CliMessage(
            ChannelUser sender,
            String text,
            String sessionId,
            Instant receivedAt
    ) implements ChannelMessage {
        @Override public String correlationId() { return sessionId; }
        @Override public ChannelType channelType() { return ChannelType.CLI; }
    }

    /**
     * A message received from a WebSocket channel.
     *
     * @param sender     the WebSocket user
     * @param text       the message text
     * @param sessionId  the WebSocket session ID (used as correlationId)
     * @param receivedAt ingestion timestamp
     */
    record WebSocketMessage(
            ChannelUser sender,
            String text,
            String sessionId,
            Instant receivedAt
    ) implements ChannelMessage {
        @Override public String correlationId() { return sessionId; }
        @Override public ChannelType channelType() { return ChannelType.WEBSOCKET; }
    }
}
