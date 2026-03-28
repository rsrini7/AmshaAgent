package com.arcclaw.channel.telegram;

import java.util.ArrayList;
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
        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SEND: recipient or channelId is null — dropping");
            return;
        }
        String chatId = recipient.channelId();  // ← was platformId()
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
        log.infof("TELEGRAM-SEND to=%s text=[%s]", recipient.channelId(), text.length() > 100 ? text.substring(0, 100) + "..." : text);

        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SENDRAW: recipient or channelId is null — dropping");
            return;
        }
        sendRaw(recipient.channelId(), text);  // ← was platformId()
    }

    private static final int TELEGRAM_MAX_LENGTH = 4096;

    private void sendRaw(String chatId, String text) {
        if (text == null || text.isBlank()) {
            log.warn("Attempted to send empty message to " + chatId + " — skipping");
            return;
        }
        if (text.length() <= TELEGRAM_MAX_LENGTH) {
            producerTemplate.sendBodyAndHeader(
                "telegram:bots", text, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
        } else {
            List<String> chunks = splitAtWordBoundary(text, TELEGRAM_MAX_LENGTH);
            log.infof("TELEGRAM-SEND splitting %d-char response into %d chunks for %s",
                text.length(), chunks.size(), chatId);
            for (String chunk : chunks) {
                producerTemplate.sendBodyAndHeader(
                    "telegram:bots", chunk, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
            }
        }
    }

    private List<String> splitAtWordBoundary(String text, int maxLen) {
        List<String> parts = new ArrayList<>();
        while (text.length() > maxLen) {
            int split = text.lastIndexOf(' ', maxLen);
            if (split <= 0) split = maxLen;
            parts.add(text.substring(0, split));
            text = text.substring(split).stripLeading();
        }
        if (!text.isBlank()) parts.add(text);
        return parts;
    }
}
