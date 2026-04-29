package com.arcclaw.channel.telegram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
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

    private final ProducerTemplate producerTemplate;
    private final TelegramMarkdownV2Renderer renderer;
    private static final Logger log = Logger.getLogger(TelegramChannelSender.class);

    @ConfigProperty(name = "arcclaw.channel.telegram.max-length", defaultValue = "4096")
    int maxLength;

    @Inject
    public TelegramChannelSender(ProducerTemplate producerTemplate,
                                 TelegramMarkdownV2Renderer renderer) {
        this.producerTemplate = producerTemplate;
        this.renderer = renderer;
    }

    @Override
    public ChannelType supports() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SEND: recipient or channelId is null - dropping");
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

        // Component 73-A4 - Intercept ApprovalPrompt to add Telegram inline keyboard
        Optional<ReplyNode.ApprovalPrompt> promptOpt = nodes.stream()
            .filter(n -> n instanceof ReplyNode.ApprovalPrompt)
            .map(n -> (ReplyNode.ApprovalPrompt) n)
            .findFirst();

        if (promptOpt.isPresent()) {
            ReplyNode.ApprovalPrompt prompt = promptOpt.get();
            List<String> toolNames;
            if (prompt.toolName() != null && prompt.toolName().startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
                String toolNamesStr = prompt.toolName().substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length());
                toolNames = Arrays.asList(toolNamesStr.split(","));
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
        log.infof("TELEGRAM-SEND to=%s text=[%s]", recipient.channelId(), text.length() > 100 ? text.substring(0, 100) + "..." : text);

        if (recipient == null || recipient.channelId() == null) {
            log.warn("TELEGRAM-SENDRAW: recipient or channelId is null - dropping");
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
            log.warn("TELEGRAM-SEND: recipient or channelId is null - dropping");
            return;
        }
        String chatId = recipient.channelId();

        OutgoingTextMessage msg = new OutgoingTextMessage();
        // §7.28: Text is assumed pre-escaped by caller. sendWithKeyboard() is
        // deliberately dumb - it sets ParseMode and sends, nothing else.
        msg.setText(text);
        msg.setParseMode("MarkdownV2");
        msg.setReplyMarkup(markup);

        producerTemplate.sendBodyAndHeader("telegram:bots", msg,
            TelegramConstants.TELEGRAM_CHAT_ID, chatId);
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
        try {
            EditMessageTextMessage edit = EditMessageTextMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(newText != null ? newText : "")
                .replyMarkup(markup != null ? markup : new InlineKeyboardMarkup())
                .build();
            producerTemplate.sendBodyAndHeader(
                "telegram:bots", edit,
                TelegramConstants.TELEGRAM_CHAT_ID, chatId);
            log.debugf("editMessage sent for chatId=%s msgId=%d", chatId, messageId);
        } catch (Exception e) {
            log.warnf("editMessage failed for chatId=%s msgId=%d: %s",
                      chatId, messageId, e.getMessage());
        }
    }

    /**
     * Internal helper to send a message to a specific chat ID, handling chunking if needed.
     */
    private void sendRawToChatId(String chatId, String text) {
        if (text == null || text.isBlank()) {
            log.warn("Attempted to send empty message to " + chatId + " - skipping");
            return;
        }
        if (text.length() <= maxLength) {
            producerTemplate.sendBodyAndHeader(
                "telegram:bots", text, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
        } else {
            List<String> chunks = splitAtWordBoundary(text, maxLength);
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
