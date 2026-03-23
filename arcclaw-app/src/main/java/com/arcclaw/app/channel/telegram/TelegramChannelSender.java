package com.arcclaw.app.channel.telegram;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * Telegram implementation of ChannelSender.
 */
@ApplicationScoped
public class TelegramChannelSender implements ChannelSender {

    private final ProducerTemplate producerTemplate;
    private final TelegramMarkdownV2Renderer renderer;
    private final Logger log;

    @Inject
    public TelegramChannelSender(ProducerTemplate producerTemplate,
                                 TelegramMarkdownV2Renderer renderer,
                                 Logger log) {
        this.producerTemplate = producerTemplate;
        this.renderer = renderer;
        this.log = log;
    }

    @Override
    public ChannelType supports() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        if (recipient == null || recipient.platformId() == null) {
            log.warn("TELEGRAM-SEND: recipient or platformId is null — dropping");
            return;
        }
        String chatId = recipient.platformId();
        if (nodes == null || nodes.isEmpty()) {
            sendRaw(chatId, "(No response generated)");
            return;
        }
        String rendered = renderer.render(nodes);
        if (rendered == null || rendered.isBlank()) {
            sendRaw(chatId, "(No response generated)");
            return;
        }
        sendRaw(chatId, rendered);
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        if (recipient == null || recipient.platformId() == null) {
            log.warn("TELEGRAM-SENDRAW: recipient or platformId is null — dropping");
            return;
        }
        sendRaw(recipient.platformId(), text);
    }

    private void sendRaw(String chatId, String text) {
        if (text == null || text.isBlank()) {
            log.warn("Attempted to send empty message to " + chatId + " — skipping");
            return;
        }
        String endpoint = "telegram:bots";
        producerTemplate.sendBodyAndHeader(endpoint, text, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
    }
}
