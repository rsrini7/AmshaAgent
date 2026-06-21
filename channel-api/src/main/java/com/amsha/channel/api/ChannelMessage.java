package com.amsha.channel.api;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Channel-agnostic representation of an inbound user message.
 *
 * <p>Common accessors are declared on the interface; channel-specific
 * fields live on each implementation record.
 *
 * <p>Third-party channel plugins can implement this interface in their own modules
 * without modifying {@code amsha-channel-api}. Use {@link #attribute(ChannelMessageAttribute)}
 * to attach channel-specific data that is not part of the core contract.
 */
public interface ChannelMessage {

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

    /**
     * Returns channel-specific attributes attached to this message.
     * Default implementation returns an empty map. Channel implementations
     * can override to provide typed metadata (e.g. Telegram callback query ID,
     * Discord guild ID, Slack thread timestamp).
     */
    default Map<ChannelMessageAttribute<?>, Object> attributes() {
        return Collections.emptyMap();
    }

    /**
     * Retrieves a typed channel-specific attribute by key.
     *
     * @param key the attribute key
     * @param <T> the attribute type
     * @return the attribute value, or empty if not present
     * @throws ClassCastException if the value exists but is not of the expected type
     */
    default <T> Optional<T> attribute(ChannelMessageAttribute<T> key) {
        Object value = attributes().get(key);
        if (value == null) return Optional.empty();
        if (!key.type().isInstance(value)) {
            throw new ClassCastException("ChannelMessageAttribute '" + key.name() + "' expected "
                    + key.type().getName() + " but got " + value.getClass().getName());
        }
        return Optional.of(key.type().cast(value));
    }

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
    record TelegramMessage(ChannelUser sender, String text, String chatId, Instant receivedAt)
            implements ChannelMessage {
        @Override
        public String correlationId() {
            return chatId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.TELEGRAM;
        }
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
    record DiscordMessage(ChannelUser sender, String text, String guildId, String channelId, Instant receivedAt)
            implements ChannelMessage {
        @Override
        public String correlationId() {
            return channelId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.DISCORD;
        }
    }

    /**
     * A message received via the REST API.
     *
     * @param sender        synthetic user based on the API key
     * @param text          the request goal/body
     * @param apiKeyId      the resolved API key identifier (used as correlationId)
     * @param receivedAt    ingestion timestamp
     */
    record RestApiMessage(ChannelUser sender, String text, String apiKeyId, Instant receivedAt)
            implements ChannelMessage {
        @Override
        public String correlationId() {
            return apiKeyId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.REST_API;
        }
    }

    /**
     * A message received from the CLI (picocli {@code ask} command).
     *
     * @param sender     synthetic CLI user
     * @param text       the goal string
     * @param sessionId  the generated CLI session ID (used as correlationId)
     * @param receivedAt ingestion timestamp
     */
    record CliMessage(ChannelUser sender, String text, String sessionId, Instant receivedAt) implements ChannelMessage {
        @Override
        public String correlationId() {
            return sessionId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.CLI;
        }
    }

    /**
     * A message received from a WebSocket channel.
     *
     * @param sender     the WebSocket user
     * @param text       the message text
     * @param sessionId  the WebSocket session ID (used as correlationId)
     * @param receivedAt ingestion timestamp
     */
    record WebSocketMessage(ChannelUser sender, String text, String sessionId, Instant receivedAt)
            implements ChannelMessage {
        @Override
        public String correlationId() {
            return sessionId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.WEBSOCKET;
        }
    }
}
