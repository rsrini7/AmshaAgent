package com.arcclaw.channel.telegram;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelUser;

@ApplicationScoped
public class TelegramMessageMapper {

    public ChannelMessage map(String text, ChannelUser user, String chatId) {
        if (text == null || text.isBlank()) {
            return null;
        }

        return new ChannelMessage.TelegramMessage(user, text.trim(), chatId, Instant.now());
    }
}
