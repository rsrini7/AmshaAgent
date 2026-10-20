package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.InteractionNode;
import com.amsha.channel.api.MediaTypes;
import com.amsha.channel.api.ReplyNode;
import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.common.util.LogUtil;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.EditMessageTextMessage;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.OutgoingAudioMessage;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.apache.camel.component.telegram.model.SendChatActionMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Telegram implementation of ChannelSender.
 */
@ApplicationScoped
public class TelegramChannelSender implements ChannelSender {

    private static final String TELEGRAM_BOTS_ENDPOINT = "telegram:bots";
    private static final int DEFAULT_MAX_LENGTH = 4096;

    /**
     * MIME type → safe file extension. Only the formats V1 can emit are meaningful, but a few
     * common audio types are recognised so a future provider that returns a different format still
     * gets a sane, non-executable attachment name. Unknown or blank MIME falls back to {@code .wav}.
     */
    private static final Map<String, String> MIME_TO_EXT = Map.ofEntries(
            Map.entry(MediaTypes.WAV, ".wav"),
            Map.entry(MediaTypes.X_WAV, ".wav"),
            Map.entry(MediaTypes.WAVE, ".wav"),
            Map.entry(MediaTypes.MPEG, ".mp3"),
            Map.entry(MediaTypes.MP3, ".mp3"),
            Map.entry(MediaTypes.OGG, ".ogg"),
            Map.entry(MediaTypes.OPUS, ".opus"),
            Map.entry(MediaTypes.FLAC, ".flac"),
            Map.entry(MediaTypes.AAC, ".aac"),
            Map.entry(MediaTypes.X_M4A, ".m4a"),
            Map.entry(MediaTypes.WEBM, ".weba"));

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
                toolNames = Arrays.stream(toolNamesStr.split("[|,]", -1))
                        .filter(s -> !s.isBlank())
                        .toList();
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
     * Delivers synthesized speech as a Telegram audio message, separate from any text reply.
     *
     * <p>Telegram's {@link OutgoingAudioMessage} has no caption field, so the contract's "separate
     * message" expectation is exactly its native shape: the audio is sent on its own and the text
     * answer (if any) is delivered independently by the caller. The file extension is derived purely
     * from the declared {@code mimeType}; the caller-supplied {@code fileName} is untrusted and never
     * used, preventing a crafted name from influencing the attachment.
     *
     * <p>Returns {@code false} (never throws) when the recipient, chat, or payload is invalid, or
     * when the Camel send fails, so the caller's text fallback always applies.
     */
    private static final long COMPRESSION_THRESHOLD_BYTES = 1_048_576L; // 1 MB

    @Override
    public boolean sendAudio(ChannelUser recipient, byte[] audio, String mimeType, String fileName) {
        if (recipient == null
                || recipient.channelId() == null
                || recipient.channelId().isBlank()) {
            log.warn("TELEGRAM-SENDAUDIO: recipient or channelId is null - dropping");
            return false;
        }
        if (audio == null || audio.length == 0) {
            log.warn("TELEGRAM-SENDAUDIO: empty audio payload - dropping");
            return false;
        }
        String chatId = recipient.channelId();
        byte[] payload = compressIfLargeWav(audio, mimeType);
        String safeName = payload.length != audio.length ? "amsha-reply.ogg" : deriveAudioFileName(mimeType);
        OutgoingAudioMessage msg = new OutgoingAudioMessage();
        msg.setAudio(payload);
        msg.setFilenameWithExtension(safeName);
        try {
            log.infof("TELEGRAM-SENDAUDIO to=%s bytes=%d name=%s", chatId, payload.length, safeName);
            producerTemplate.sendBodyAndHeader(TELEGRAM_BOTS_ENDPOINT, msg, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
            return true;
        } catch (Exception e) {
            log.warnf("TELEGRAM-SENDAUDIO failed for chat %s: %s", chatId, LogUtil.rootMessage(e));
            return false;
        }
    }

    private byte[] compressIfLargeWav(byte[] audio, String mimeType) {
        if (audio == null || audio.length < COMPRESSION_THRESHOLD_BYTES) {
            return audio;
        }
        String normalized = mimeType != null ? mimeType.trim().toLowerCase(Locale.ROOT) : "";
        if (!normalized.contains("wav") && !normalized.isBlank()) {
            return audio;
        }
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("amsha-tel-in-", ".wav");
            out = Files.createTempFile("amsha-tel-out-", ".ogg");
            Files.write(in, audio);
            Process process = new ProcessBuilder(
                            "ffmpeg",
                            "-y",
                            "-loglevel",
                            "error",
                            "-i",
                            in.toString(),
                            "-c:a",
                            "libopus",
                            "-b:a",
                            "32k",
                            "-f",
                            "ogg",
                            out.toString())
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0 && Files.exists(out)) {
                byte[] compressed = Files.readAllBytes(out);
                if (compressed.length > 0 && compressed.length < audio.length) {
                    log.infof(
                            "TELEGRAM-AUDIO compressed %d bytes WAV -> %d bytes OGG", audio.length, compressed.length);
                    return compressed;
                }
            }
        } catch (Exception e) {
            log.debugf("TELEGRAM-AUDIO compression skipped: %s", LogUtil.rootMessage(e));
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
        return audio;
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception _) {
            }
        }
    }

    /** Derives a safe attachment name from the declared MIME type; untrusted fileName is ignored. */
    private static String deriveAudioFileName(String mimeType) {
        if (mimeType != null && !mimeType.isBlank()) {
            String ext = MIME_TO_EXT.get(mimeType.trim().toLowerCase(Locale.ROOT));
            if (ext != null) {
                return "amsha-reply" + ext;
            }
        }
        return "amsha-reply.wav";
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
     * Sends a Telegram Bot API chat action (e.g. typing) through governed Camel transport.
     *
     * @param chatId the destination chat ID
     * @param action the action to broadcast (e.g. {@link SendChatActionMessage.Action#TYPING})
     */
    public void sendChatAction(String chatId, SendChatActionMessage.Action action) {
        if (chatId == null || chatId.isBlank() || action == null) {
            log.warn("TELEGRAM-ACTION: chatId or action is null/blank - dropping");
            return;
        }
        try {
            SendChatActionMessage msg = new SendChatActionMessage(action);
            msg.setChatId(chatId);
            producerTemplate.sendBodyAndHeader(TELEGRAM_BOTS_ENDPOINT, msg, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
            log.debugf("sendChatAction %s sent for chatId=%s", action, chatId);
        } catch (Exception e) {
            log.warnf("sendChatAction failed for chatId=%s action=%s: %s", chatId, action, LogUtil.rootMessage(e));
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
