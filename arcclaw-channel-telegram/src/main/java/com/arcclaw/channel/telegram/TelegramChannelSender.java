package com.arcclaw.channel.telegram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.jboss.logging.Logger;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
            InlineKeyboardMarkup markup = BatchHitlKeyboardBuilder.buildKeyboard(promptOpt.get().sessionId(), list);
            sendWithKeyboard(recipient, rendered, markup);
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
    public void sendWithKeyboard(ChannelUser recipient, String text, InlineKeyboardMarkup markup) {
        if (markup == null) {
            sendRawToChatId(recipient.channelId(), text);
            return;
        }
        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SEND: recipient or channelId is null — dropping");
            return;
        }
        String chatId = recipient.channelId();

        OutgoingTextMessage msg = new OutgoingTextMessage();
        msg.setText(text);
        msg.setParseMode("MarkdownV2");
        msg.setReplyMarkup(markup);

        producerTemplate.sendBodyAndHeader("telegram:bots", msg,
            TelegramConstants.TELEGRAM_CHAT_ID, chatId);
    }

    public void editMessage(String chatId, int messageId, String newText, InlineKeyboardMarkup markup) {
        // P74-3: editMessageReplyMarkup not yet supported via camel-telegram.
        // Proper implementation requires OutgoingEditMessage or direct Bot API call.
        // Stubbing to avoid sending redundant new messages.
        log.debugf("editMessage: deferred to P74 (chatId=%s, msgId=%d)", chatId, messageId);
    }

    private static final int TELEGRAM_MAX_LENGTH = 4096;

    /**
     * Internal helper to send a message to a specific chat ID, handling chunking if needed.
     */
    private void sendRawToChatId(String chatId, String text) {
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
