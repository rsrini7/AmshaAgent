package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.MediaAttachment;

import org.apache.camel.component.telegram.model.IncomingAudio;
import org.apache.camel.component.telegram.model.IncomingDocument;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.IncomingPhotoSize;
import org.apache.camel.component.telegram.model.IncomingVoice;

@ApplicationScoped
public class TelegramMessageMapper {

    private final TelegramMediaResolver mediaResolver;

    @Inject
    public TelegramMessageMapper(TelegramMediaResolver mediaResolver) {
        this.mediaResolver = Objects.requireNonNull(mediaResolver, "mediaResolver must not be null");
    }

    /**
     * Maps raw text into a text-only Telegram message.
     */
    public ChannelMessage map(String text, ChannelUser user, String chatId) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return new ChannelMessage.TelegramMessage(user, text.trim(), chatId, Instant.now());
    }

    /**
     * Maps an incoming Camel Telegram message (with potential media attachments and captions)
     * into a {@link ChannelMessage.TelegramMessage}.
     */
    public ChannelMessage map(IncomingMessage msg, ChannelUser user, String chatId) {
        if (msg == null) {
            return null;
        }

        List<MediaAttachment> attachments = extractAttachments(msg);
        String text = msg.getText() != null && !msg.getText().isBlank()
                ? msg.getText().trim()
                : null;
        String rawCaption = msg.getCaption() != null && !msg.getCaption().isBlank()
                ? msg.getCaption().trim()
                : null;

        if (text == null && rawCaption != null) {
            text = rawCaption;
        }

        if (text == null && attachments.isEmpty()) {
            return null;
        }

        Instant receivedAt = msg.getDate() != null ? msg.getDate() : Instant.now();
        return new ChannelMessage.TelegramMessage(user, text, chatId, receivedAt, attachments, rawCaption);
    }

    private List<MediaAttachment> extractAttachments(IncomingMessage msg) {
        List<MediaAttachment> attachments = new ArrayList<>();
        extractPhoto(msg.getPhoto(), attachments);
        extractDocument(msg.getDocument(), attachments);
        extractAudio(msg.getAudio(), attachments);
        extractVoice(msg.getVoice(), attachments);
        return List.copyOf(attachments);
    }

    private void extractPhoto(List<IncomingPhotoSize> photos, List<MediaAttachment> attachments) {
        if (photos == null || photos.isEmpty()) {
            return;
        }
        IncomingPhotoSize best = photos.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingLong(TelegramMessageMapper::photoRank))
                .orElse(null);
        if (best == null) {
            return;
        }
        MediaAttachment att = mediaResolver.resolvePhoto(best);
        if (att != null) {
            attachments.add(att);
        }
    }

    private void extractDocument(IncomingDocument doc, List<MediaAttachment> attachments) {
        if (doc == null) {
            return;
        }
        MediaAttachment att = mediaResolver.resolveDocument(doc);
        if (att != null) {
            attachments.add(att);
        }
    }

    private void extractAudio(IncomingAudio audio, List<MediaAttachment> attachments) {
        if (audio == null) {
            return;
        }
        MediaAttachment att = mediaResolver.resolveAudio(audio);
        if (att != null) {
            attachments.add(att);
        }
    }

    private void extractVoice(IncomingVoice voice, List<MediaAttachment> attachments) {
        if (voice == null) {
            return;
        }
        MediaAttachment att = mediaResolver.resolveVoice(voice);
        if (att != null) {
            attachments.add(att);
        }
    }

    private static long photoRank(IncomingPhotoSize photo) {
        if (photo.getFileSize() != null && photo.getFileSize() >= 0) {
            return photo.getFileSize();
        }
        return (long) (photo.getWidth() != null ? photo.getWidth() : 0)
                * (photo.getHeight() != null ? photo.getHeight() : 0);
    }
}
