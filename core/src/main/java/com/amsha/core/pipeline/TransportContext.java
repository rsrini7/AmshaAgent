package com.amsha.core.pipeline;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;

/**
 * Encapsulates transport-layer metadata for a message.
 */
public record TransportContext(String chatId, ChannelUser user, ChannelMessage message) {
    public ChannelType channelType() {
        return user != null ? user.channel() : ChannelType.TELEGRAM;
    }
}
