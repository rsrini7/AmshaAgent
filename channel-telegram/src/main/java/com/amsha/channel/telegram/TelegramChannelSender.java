package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.InteractionNode;
import com.amsha.channel.api.ReplyNode;
import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.common.util.LogUtil;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.EditMessageTextMessage;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Telegram implementation of ChannelSender.
 */
@ApplicationScoped
public class TelegramChannelSender implements ChannelSender {

    private static final String TELEGRAM_BOTS_ENDPOINT = "telegram:bots";
    private static final int DEFAULT_MAX_LENGTH = 4096;

    private final ProducerTemplate producerTemplate;
    private final TelegramMarkdownV2Renderer renderer;
    private static final Logger log = Logger.getLogger(TelegramChannelSender.class);

    @ConfigProperty(name = "amsha.channel.telegram.max-length", defaultValue = "4096")
    int maxLength;

    @Inject
    public TelegramChannelSender(ProducerTemplate producerTemplate, TelegramMarkdownV2Renderer renderer) {
        this.producerTemplate = producerTemplate;
        this.renderer = renderer;
    }

    @Override
    public ChannelType supports() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        if (recipient == null
                || recipient.channelId() == null
                || recipient.channelId().isBlank()) {
            log.warn("TELEGRAM-SEND: recipient or channelId is null - dropping");
            return;
        }
        String chatId = recipient.channelId(); // ← was platformId()
        if (nodes == null || nodes.isEmpty()) {
            sendRawToChatId(chatId, "(No response generated)");
            return;
        }
        String rendered = renderer.render(nodes);
        if (rendered == null || rendered.isBlank()) {
            sendRawToChatId(chatId, "(No response generated)");
            return;
        }

        // Component 73-A4 - Intercept ApprovalPrompt to add Telegram inline keyboard
        Optional<ApprovalPrompt> promptOpt = nodes.stream()
                .filter(n -> n instanceof ApprovalPrompt)
                .map(n -> (ApprovalPrompt) n)
                .findFirst();

        if (promptOpt.isPresent()) {
            ApprovalPrompt prompt = promptOpt.get();
            List<String> toolNames;
            if (prompt.toolName() != null
                    && prompt.toolName().startsWith(InteractionNode.PARALLEL_SENTINEL_PIPE_PREFIX)) {
                String toolNamesStr =
                        prompt.toolName().substring(InteractionNode.PARALLEL_SENTINEL_PIPE_PREFIX.length());
                toolNames = Arrays.asList(toolNamesStr.split(",", -1));
            } else if (prompt.toolName() != null) {
                toolNames = List.of(prompt.toolName());
            } else {
                toolNames = List.of();
            }

            InlineKeyboardMarkup markup = BatchHitlKeyboardBuilder.buildKeyboard(prompt.sessionId(), toolNames);
            sendWithKeyboard(recipient, rendered, markup);
        } else {
            sendRawToChatId(chatId, rendered);
        }
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        if (recipient == null
                || recipient.channelId() == null
                || recipient.channelId().isBlank()) {
            log.warn("TELEGRAM-SENDRAW: recipient or channelId is null - dropping");
            return;
        }
        log.infof("TELEGRAM-SEND to=%s textLength=%d", recipient.channelId(), text != null ? text.length() : 0);

        sendRawToChatId(recipient.channelId(), text); // ← was platformId()
    }

    /**
     * Sends a message with an optional inline keyboard.
     * §7.24
     */
    public void sendWithKeyboard(ChannelUser recipient, String text, InlineKeyboardMarkup markup) {
        if (recipient == null
                || recipient.channelId() == null
                || recipient.channelId().isBlank()) {
            log.warn("TELEGRAM-SEND: recipient or channelId is null - dropping");
            return;
        }
        String chatId = recipient.channelId();
        if (markup == null) {
            sendRawToChatId(chatId, text);
            return;
        }
        text = HtmlArtifactUtil.collapseHtmlArtifact(text);
        text = stripGarbledLines(text);
        if (text == null || text.isBlank()) {
            log.warnf("Attempted to send empty or garbled keyboard message to %s - skipping", chatId);
            return;
        }

        OutgoingTextMessage msg = new OutgoingTextMessage();
        // §7.28: Text is assumed pre-escaped by caller. sendWithKeyboard() is
        // deliberately dumb - it sets ParseMode and sends, nothing else.
        msg.setText(text);
        msg.setParseMode("MarkdownV2");
        msg.setReplyMarkup(markup);

        try {
            producerTemplate.sendBodyAndHeader(TELEGRAM_BOTS_ENDPOINT, msg, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
        } catch (Exception e) {
            String root = LogUtil.rootMessage(e);
            if (root != null && root.toLowerCase(Locale.ROOT).contains("can't parse entities")) {
                log.errorf("TELEGRAM-SEND MarkdownV2 parse failed for chat %s; retrying as plain text", chatId);
                OutgoingTextMessage fallback = new OutgoingTextMessage();
                fallback.setText(text);
                fallback.setReplyMarkup(markup);
                producerTemplate.sendBodyAndHeader(
                        TELEGRAM_BOTS_ENDPOINT, fallback, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
            } else {
                throw e;
            }
        }
    }

    /**
     * Edits an existing message's text and/or inline keyboard. §7.27
     * <p>
     * Uses Camel's {@link EditMessageTextMessage} which maps to the
     * Telegram Bot API {@code editMessageText} endpoint.
     *
     * @param chatId    the chat containing the message
     * @param messageId the message to edit
     * @param newText   replacement text (plain, not MarkdownV2-escaped)
     * @param markup    new inline keyboard, or {@code null} to remove all buttons
     */
    public void editMessage(String chatId, int messageId, String newText, InlineKeyboardMarkup markup) {
        if (chatId == null || chatId.isBlank()) {
            log.warn("TELEGRAM-EDIT: chatId is null or blank - dropping");
            return;
        }
        try {
            String sanitizedText = HtmlArtifactUtil.collapseHtmlArtifact(newText != null ? newText : "");
            sanitizedText = stripGarbledLines(sanitizedText);
            EditMessageTextMessage edit = EditMessageTextMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(sanitizedText)
                    .replyMarkup(markup != null ? markup : new InlineKeyboardMarkup())
                    .build();
            producerTemplate.sendBodyAndHeader(
                    TELEGRAM_BOTS_ENDPOINT, edit, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
            log.debugf("editMessage sent for chatId=%s msgId=%d", chatId, messageId);
        } catch (Exception e) {
            log.warnf("editMessage failed for chatId=%s msgId=%d: %s", chatId, messageId, LogUtil.rootMessage(e));
        }
    }

    /**
     * Strips garbled output lines (mostly ?, …, ‑, em-dash, special punctuation) that free models
     * sometimes produce before real content. Keeps lines with actual alphanumeric content.
     */
    static String stripGarbledLines(String text) {
        if (text == null || text.isBlank()) return text;
        StringBuilder result = new StringBuilder();
        boolean insideCodeFence = false;
        for (String line : text.split("\n", -1)) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("```")) {
                result.append(line).append('\n');
                insideCodeFence = !insideCodeFence;
                continue;
            }
            if (insideCodeFence) {
                result.append(line).append('\n');
                continue;
            }
            // Count garbled characters: ?, …, ‑, —, em-dash, special punctuation, spaces
            long garbled = line.chars()
                    .filter(c -> c == '?'
                            || c == '…'
                            || c == '\u2011' // non-breaking hyphen
                            || c == '\u2014' // em-dash
                            || c == '\u2013' // en-dash
                            || c == '\u2026' // horizontal ellipsis
                            || c == '.'
                            || c == ','
                            || c == ' '
                            || Character.getType(c) == Character.OTHER_PUNCTUATION)
                    .count();
            // If more than 80% of non-space characters are garbled, skip the line
            long nonSpace = line.chars().filter(c -> !Character.isWhitespace(c)).count();
            if (nonSpace > 0 && garbled > nonSpace * 0.8) {
                continue; // skip garbled line
            }
            result.append(line).append('\n');
        }
        // Trim trailing newline
        String cleaned = result.toString();
        if (cleaned.endsWith("\n")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    /**
     * Internal helper to send a message to a specific chat ID, handling chunking if needed.
     */
    private void sendRawToChatId(String chatId, String text) {
        if (text == null || text.isBlank()) {
            log.warnf("Attempted to send empty message to %s - skipping", chatId);
            return;
        }
        text = HtmlArtifactUtil.collapseHtmlArtifact(text);
        text = stripGarbledLines(text);
        if (text.isBlank()) {
            log.warnf("Attempted to send garbled-only message to %s - skipping", chatId);
            return;
        }
        int effectiveMaxLength = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;
        if (text.length() <= effectiveMaxLength) {
            producerTemplate.sendBodyAndHeader(
                    TELEGRAM_BOTS_ENDPOINT, text, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
        } else {
            List<String> chunks = splitAtWordBoundary(text, effectiveMaxLength);
            log.infof(
                    "TELEGRAM-SEND splitting %d-char response into %d chunks for %s",
                    text.length(), chunks.size(), chatId);
            for (String chunk : chunks) {
                producerTemplate.sendBodyAndHeader(
                        TELEGRAM_BOTS_ENDPOINT, chunk, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
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
