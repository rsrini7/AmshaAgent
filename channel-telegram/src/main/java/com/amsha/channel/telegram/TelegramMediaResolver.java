package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;

import org.apache.camel.component.telegram.model.IncomingAudio;
import org.apache.camel.component.telegram.model.IncomingDocument;
import org.apache.camel.component.telegram.model.IncomingPhotoSize;
import org.apache.camel.component.telegram.model.IncomingVoice;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Converts Telegram media descriptors into channel-neutral references.
 *
 * <p>This class deliberately performs no network I/O. Telegram file IDs remain opaque
 * {@code telegram://} references and are resolved later by the governed media-download
 * boundary. Keeping mapping side-effect free prevents a Camel/Vert.x event-loop callback
 * from blocking and prevents channel code from bypassing SSRF, MIME, budget, and HITL checks.
 */
@ApplicationScoped
@Unremovable
public class TelegramMediaResolver {

    private static final Logger log = Logger.getLogger(TelegramMediaResolver.class);
    private static final Pattern TELEGRAM_FILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,512}");
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /** Resolves a photo size into an image reference. */
    public MediaAttachment resolvePhoto(IncomingPhotoSize photo) {
        if (photo == null) {
            return null;
        }
        String fileId = validFileId(photo.getFileId());
        if (fileId == null) {
            return null;
        }
        long size = knownSize(photo.getFileSize());
        return resolveMedia(fileId, AttachmentType.IMAGE, "image/jpeg", "photo_" + fileId + ".jpg", size);
    }

    /** Resolves a Telegram document with a conservative type derived from MIME/name. */
    public MediaAttachment resolveDocument(IncomingDocument document) {
        if (document == null) {
            return null;
        }
        String fileId = validFileId(document.getFileId());
        if (fileId == null) {
            return null;
        }
        String fileName = safeFileName(document.getFileName(), "document_" + fileId);
        String mimeType = normalizeMimeType(document.getMimeType(), detectMimeType(fileName));
        AttachmentType type = typeFor(mimeType, fileName);
        return resolveMedia(fileId, type, mimeType, fileName, knownSize(document.getFileSize()));
    }

    /** Resolves a Telegram audio descriptor into an audio reference. */
    public MediaAttachment resolveAudio(IncomingAudio audio) {
        if (audio == null) {
            return null;
        }
        String fileId = validFileId(audio.getFileId());
        if (fileId == null) {
            return null;
        }
        String fileName = safeFileName(audio.getTitle(), "audio_" + fileId + ".mp3");
        String mimeType = normalizeMimeType(audio.getMimeType(), fallbackAudioMimeType(fileName));
        return resolveMedia(fileId, AttachmentType.AUDIO, mimeType, fileName, knownSize(audio.getFileSize()));
    }

    /** Resolves a Telegram voice note into an OGG audio reference. */
    public MediaAttachment resolveVoice(IncomingVoice voice) {
        if (voice == null) {
            return null;
        }
        String fileId = validFileId(voice.getFileId());
        if (fileId == null) {
            return null;
        }
        String mimeType = normalizeMimeType(voice.getMimeType(), "audio/ogg");
        return resolveMedia(
                fileId, AttachmentType.AUDIO, mimeType, "voice_" + fileId + ".ogg", knownSize(voice.getFileSize()));
    }

    /**
     * Creates an opaque Telegram reference without contacting Telegram.
     *
     * @param fileId Telegram file ID supplied by the provider
     * @param type channel-neutral attachment type
     * @param mimeType provider-declared MIME type
     * @param fileName display name supplied by the provider
     * @param sizeBytes provider-declared size, or {@code -1} when unknown
     * @return an opaque reference, or never {@code null} for valid arguments
     */
    public MediaAttachment resolveMedia(
            String fileId, AttachmentType type, String mimeType, String fileName, long sizeBytes) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        String validId = requireValidFileId(fileId);
        String safeName = safeFileName(fileName, "telegram_media_" + validId);
        String normalizedMime = normalizeMimeType(mimeType, "application/octet-stream");
        long safeSize = sizeBytes >= 0 ? sizeBytes : -1L;
        String attachmentId = UUID.randomUUID().toString();
        String reference = "telegram://file/" + validId;
        log.debugf("Created Telegram media reference type=%s size=%d", type, safeSize);
        return MediaAttachment.ofUri(
                attachmentId,
                type,
                normalizedMime,
                safeName,
                safeSize,
                URI.create(reference),
                Map.of("file_id", validId));
    }

    private static AttachmentType typeFor(String mimeType, String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if ("application/pdf".equals(mimeType) || lowerName.endsWith(".pdf")) {
            return AttachmentType.DOCUMENT_PDF;
        }
        if (mimeType.startsWith("image/")) {
            return AttachmentType.IMAGE;
        }
        if (mimeType.startsWith("audio/")) {
            return AttachmentType.AUDIO;
        }
        return AttachmentType.OTHER;
    }

    private static String detectMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static String normalizeMimeType(String mimeType, String fallback) {
        if (mimeType == null || mimeType.isBlank()) {
            return fallback;
        }
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/octet-stream".equals(normalized) && !"application/octet-stream".equals(fallback)
                ? fallback
                : normalized;
    }

    private static String fallbackAudioMimeType(String fileName) {
        String detected = detectMimeType(fileName);
        return detected.startsWith("audio/") ? detected : "audio/mpeg";
    }

    private static String safeFileName(String fileName, String fallback) {
        String candidate = fileName == null || fileName.isBlank() ? fallback : fileName.trim();
        candidate = candidate.replace('\\', '_').replace('/', '_');
        candidate = candidate
                .codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .trim();
        if (candidate.isBlank()) {
            candidate = fallback;
        }
        return candidate.length() > MAX_FILE_NAME_LENGTH ? candidate.substring(0, MAX_FILE_NAME_LENGTH) : candidate;
    }

    private static long knownSize(Long size) {
        return size != null && size >= 0 ? size : -1L;
    }

    private static String validFileId(String fileId) {
        return fileId != null && TELEGRAM_FILE_ID.matcher(fileId).matches() ? fileId : null;
    }

    private static String requireValidFileId(String fileId) {
        String valid = validFileId(fileId);
        if (valid == null) {
            throw new IllegalArgumentException("fileId must be a Telegram-safe opaque identifier");
        }
        return valid;
    }
}
