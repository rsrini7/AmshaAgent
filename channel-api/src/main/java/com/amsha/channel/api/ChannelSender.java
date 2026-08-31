package com.amsha.channel.api;

import java.util.List;

/**
 * Contract for sending replies back to a specific channel.
 * Implementations are channel-specific (e.g. {@code TelegramChannelSender}).
 */
public interface ChannelSender {

    /** Returns the channel this sender handles. */
    ChannelType supports();

    /**
     * Renders {@code nodes} via the appropriate {@link ReplyRenderer} and delivers
     * the result to {@code recipient}.
     */
    void send(ChannelUser recipient, List<ReplyNode> nodes);

    /**
     * Delivers a pre-rendered plaintext {@code text} to {@code recipient} without
     * going through a renderer. Use for simple confirmations or error messages.
     */
    void sendRaw(ChannelUser recipient, String text);

    /**
     * Sends a result back to a session's communication channel (e.g. all WebSocket tabs).
     * Default implementation does nothing if session-based delivery is not supported.
     */
    default void sendBySession(String sessionId, String text) {
        // no-op by default
    }

    /**
     * Delivers synthesized audio as a message separate from any text reply.
     *
     * <p>The default returns {@code false} so channels without audio support stay unchanged and
     * callers can detect the unsupported case. A {@code false} return, or a thrown exception, means
     * the caller must fall back to the text answer; the default must never report silent success.
     *
     * @param recipient the target user
     * @param audio the encoded audio bytes
     * @param mimeType declared audio MIME type; implementations derive a safe file extension from
     *                 this value and must not trust {@code fileName}
     * @param fileName a caller-supplied base file name, treated as untrusted input
     * @return true only when the audio message was actually delivered
     */
    default boolean sendAudio(ChannelUser recipient, byte[] audio, String mimeType, String fileName) {
        return false;
    }
}
