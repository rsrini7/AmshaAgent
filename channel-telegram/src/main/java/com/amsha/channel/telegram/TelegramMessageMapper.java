package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelUser;

@ApplicationScoped
public class TelegramMessageMapper {

    public ChannelMessage map(String text, ChannelUser user, String chatId) {
        if (text == null || text.isBlank()) {
            return null;
        }

        return new ChannelMessage.TelegramMessage(user, text.trim(), chatId, Instant.now());
    }
}
