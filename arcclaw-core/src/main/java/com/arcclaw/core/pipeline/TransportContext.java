package com.arcclaw.core.pipeline;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;

/**
 * Encapsulates transport-layer metadata for a message.
 */
public record TransportContext(String chatId, ChannelUser user, ChannelMessage message) {
    public ChannelType channelType() {
        return user != null ? user.channel() : ChannelType.TELEGRAM;
    }
}
