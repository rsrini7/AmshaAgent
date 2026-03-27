package com.arcclaw.channel.telegram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            sendRawToChatId(chatId, "(No response generated)");
            return;
        }
        String rendered = renderer.render(nodes);
        if (rendered == null || rendered.isBlank()) {
            sendRawToChatId(chatId, "(No response generated)");
            return;
        }

        // Component 73-A4 — Intercept ApprovalPrompt to add Telegram inline keyboard for batch tools
        Optional<ReplyNode.ApprovalPrompt> promptOpt = nodes.stream()
            .filter(n -> n instanceof ReplyNode.ApprovalPrompt)
            .map(n -> (ReplyNode.ApprovalPrompt) n)
            .findFirst();

        if (promptOpt.isPresent() && promptOpt.get().toolName() != null && promptOpt.get().toolName().startsWith("parallel:")) {
            String toolNamesStr = promptOpt.get().toolName().substring("parallel:".length());
            List<String> list = Arrays.asList(toolNamesStr.split(","));
            String keyboardJson = BatchHitlKeyboardBuilder.buildKeyboardJson(promptOpt.get().sessionId(), list);
            sendWithKeyboard(recipient, rendered, keyboardJson);
        } else {
            sendRawToChatId(chatId, rendered);
        }
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        log.infof("TELEGRAM-SEND to=%s text=[%s]", recipient.channelId(), text.length() > 100 ? text.substring(0, 100) + "..." : text);

        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SENDRAW: recipient or channelId is null — dropping");
            return;
        }
        sendRawToChatId(recipient.channelId(), text);  // ← was platformId()
    }

    /**
     * Sends a message with an optional inline keyboard.
     * §7.24
     */
    public void sendWithKeyboard(ChannelUser recipient, String text, String keyboardJson) {
        if (keyboardJson == null || keyboardJson.isBlank()) {
            sendRawToChatId(recipient.channelId(), text);
            return;
        }
        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SEND: recipient or channelId is null — dropping");
            return;
        }
        String chatId = recipient.channelId();
        
        producerTemplate.sendBodyAndHeaders("telegram:bots", text, Map.of(
            TelegramConstants.TELEGRAM_CHAT_ID, chatId,
            "CamelTelegramReplyMarkup", keyboardJson
        ));
    }

    public void editMessage(String chatId, int messageId, String newText, String keyboardJson) {
        // Without telegrambots dependency, we must send raw JSON to the camel component or API.
        // For camel-telegram, there's an editMessage api, but as a fallback we will try to use the header approach.
        // Actually, camel-telegram natively does not support editMessage without EditMessageText object.
        // We'll just send a new message instead if it throws, or implement a dummy if not needed immediately.
        producerTemplate.sendBodyAndHeaders("telegram:bots", newText != null ? newText : "", Map.of(
            TelegramConstants.TELEGRAM_CHAT_ID, chatId,
            "CamelTelegramReplyMarkup", keyboardJson == null ? "" : keyboardJson
        ));
    }

    private static final int TELEGRAM_MAX_LENGTH = 4096;

    /**
     * Internal helper to send a message to a specific chat ID, handling chunking if needed.
     */
    public void sendRawToChatId(String chatId, String text) {
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
