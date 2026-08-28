package com.amsha.channel.api;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
     * Optional media attachments (images, audio, documents) accompanying this message.
     * Default implementation returns an empty list.
     */
    default List<MediaAttachment> attachments() {
        return List.of();
    }

    /**
     * Raw media caption when provided separately from the authoritative text/goal.
     * Telegram and generic messages preserve a separate raw caption. Other built-in message
     * records have no separate caption field and therefore fall back to {@link #text()}.
     * Channel adapters may override this when their protocol carries captions separately.
     */
    default Optional<String> caption() {
        return Optional.ofNullable(text());
    }

    /**
     * Returns a copy of this message with the specified effective text,
     * preserving all sender, correlation, channel, attachment, attribute, and caption metadata.
     *
     * @param newText the new text payload
     * @return updated channel message
     */
    default ChannelMessage withText(String newText) {
        return new GenericChannelMessage(
                sender(),
                newText,
                correlationId(),
                receivedAt(),
                channelType(),
                attachments(),
                attributes(),
                caption().orElse(null));
    }

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
        if (value == null) {
            return Optional.empty();
        }
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
     * @param text          raw message text or prompt
     * @param chatId        Telegram chat ID (used as correlationId)
     * @param receivedAt    ingestion timestamp
     * @param attachments   optional media attachments
     * @param rawCaption    optional raw caption string
     */
    record TelegramMessage(
            ChannelUser sender,
            String text,
            String chatId,
            Instant receivedAt,
            List<MediaAttachment> attachments,
            String rawCaption)
            implements ChannelMessage {

        public TelegramMessage {
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
        }

        public TelegramMessage(ChannelUser sender, String text, String chatId, Instant receivedAt) {
            this(sender, text, chatId, receivedAt, List.of(), text);
        }

        public TelegramMessage(
                ChannelUser sender, String text, String chatId, Instant receivedAt, List<MediaAttachment> attachments) {
            this(sender, text, chatId, receivedAt, attachments, text);
        }

        @Override
        public String correlationId() {
            return chatId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.TELEGRAM;
        }

        @Override
        public Optional<String> caption() {
            return Optional.ofNullable(rawCaption != null ? rawCaption : text);
        }

        @Override
        public TelegramMessage withText(String newText) {
            return new TelegramMessage(sender, newText, chatId, receivedAt, attachments, rawCaption);
        }
    }

    /**
     * A message received from Discord.
     * Discord adapters should place a caption in {@code text}; adapters that need a separate
     * caption can use {@link GenericChannelMessage} with its {@code rawCaption} component.
     *
     * @param sender        the Discord user
     * @param text          raw message text
     * @param guildId       Discord guild (server) ID
     * @param channelId     Discord text-channel ID (used as correlationId)
     * @param receivedAt    ingestion timestamp
     * @param attachments   optional media attachments
     */
    record DiscordMessage(
            ChannelUser sender,
            String text,
            String guildId,
            String channelId,
            Instant receivedAt,
            List<MediaAttachment> attachments)
            implements ChannelMessage {

        public DiscordMessage {
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
        }

        public DiscordMessage(ChannelUser sender, String text, String guildId, String channelId, Instant receivedAt) {
            this(sender, text, guildId, channelId, receivedAt, List.of());
        }

        @Override
        public String correlationId() {
            return channelId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.DISCORD;
        }

        @Override
        public DiscordMessage withText(String newText) {
            return new DiscordMessage(sender, newText, guildId, channelId, receivedAt, attachments);
        }
    }

    /**
     * A message received via the REST API.
     *
     * @param sender        synthetic user based on the API key
     * @param text          the request goal/body
     * @param apiKeyId      the resolved API key identifier (used as correlationId)
     * @param receivedAt    ingestion timestamp
     * @param attachments   optional media attachments
     */
    record RestApiMessage(
            ChannelUser sender, String text, String apiKeyId, Instant receivedAt, List<MediaAttachment> attachments)
            implements ChannelMessage {

        public RestApiMessage {
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
        }

        public RestApiMessage(ChannelUser sender, String text, String apiKeyId, Instant receivedAt) {
            this(sender, text, apiKeyId, receivedAt, List.of());
        }

        @Override
        public String correlationId() {
            return apiKeyId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.REST_API;
        }

        @Override
        public RestApiMessage withText(String newText) {
            return new RestApiMessage(sender, newText, apiKeyId, receivedAt, attachments);
        }
    }

    /**
     * A message received from the CLI (picocli {@code ask} command).
     *
     * @param sender      synthetic CLI user
     * @param text        the goal string
     * @param sessionId   the generated CLI session ID (used as correlationId)
     * @param receivedAt  ingestion timestamp
     * @param attachments optional media attachments
     */
    record CliMessage(
            ChannelUser sender, String text, String sessionId, Instant receivedAt, List<MediaAttachment> attachments)
            implements ChannelMessage {

        public CliMessage {
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
        }

        public CliMessage(ChannelUser sender, String text, String sessionId, Instant receivedAt) {
            this(sender, text, sessionId, receivedAt, List.of());
        }

        @Override
        public String correlationId() {
            return sessionId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.CLI;
        }

        @Override
        public CliMessage withText(String newText) {
            return new CliMessage(sender, newText, sessionId, receivedAt, attachments);
        }
    }

    /**
     * A message received from a WebSocket channel.
     *
     * @param sender      the WebSocket user
     * @param text        the message text
     * @param sessionId   the WebSocket session ID (used as correlationId)
     * @param receivedAt  ingestion timestamp
     * @param attachments optional media attachments
     */
    record WebSocketMessage(
            ChannelUser sender, String text, String sessionId, Instant receivedAt, List<MediaAttachment> attachments)
            implements ChannelMessage {

        public WebSocketMessage {
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
        }

        public WebSocketMessage(ChannelUser sender, String text, String sessionId, Instant receivedAt) {
            this(sender, text, sessionId, receivedAt, List.of());
        }

        @Override
        public String correlationId() {
            return sessionId;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.WEBSOCKET;
        }

        @Override
        public WebSocketMessage withText(String newText) {
            return new WebSocketMessage(sender, newText, sessionId, receivedAt, attachments);
        }
    }

    /**
     * A generic channel message fallback for unregistered/custom channels.
     */
    record GenericChannelMessage(
            ChannelUser sender,
            String text,
            String correlationId,
            Instant receivedAt,
            ChannelType channelType,
            List<MediaAttachment> attachments,
            Map<ChannelMessageAttribute<?>, Object> attributes,
            String rawCaption)
            implements ChannelMessage {

        public GenericChannelMessage {
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }

        public GenericChannelMessage(
                ChannelUser sender, String text, String correlationId, Instant receivedAt, ChannelType channelType) {
            this(sender, text, correlationId, receivedAt, channelType, List.of(), Map.of(), text);
        }

        public GenericChannelMessage(
                ChannelUser sender,
                String text,
                String correlationId,
                Instant receivedAt,
                ChannelType channelType,
                List<MediaAttachment> attachments) {
            this(sender, text, correlationId, receivedAt, channelType, attachments, Map.of(), text);
        }

        @Override
        public Optional<String> caption() {
            return Optional.ofNullable(rawCaption != null ? rawCaption : text);
        }

        @Override
        public GenericChannelMessage withText(String newText) {
            return new GenericChannelMessage(
                    sender, newText, correlationId, receivedAt, channelType, attachments, attributes, rawCaption);
        }
    }
}
