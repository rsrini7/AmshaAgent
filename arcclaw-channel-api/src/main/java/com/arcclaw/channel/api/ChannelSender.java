package com.arcclaw.channel.api;

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
}
