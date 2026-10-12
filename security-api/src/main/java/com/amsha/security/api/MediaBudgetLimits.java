package com.amsha.security.api;

import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaTypes;

/**
 * Standard configuration constants and MIME validation limits for governed media ingestion.
 */
public final class MediaBudgetLimits {

    private MediaBudgetLimits() {}

    /** Default maximum allowed size for image attachments (10 MB). */
    public static final long DEFAULT_MAX_IMAGE_SIZE_BYTES = 10L * 1024L * 1024L;

    /** Default maximum allowed size for audio / PDF document attachments (25 MB). */
    public static final long DEFAULT_MAX_DOCUMENT_SIZE_BYTES = 25L * 1024L * 1024L;

    /** Default maximum total payload across all attachments in a single turn (50 MB). */
    public static final long DEFAULT_MAX_TOTAL_PAYLOAD_BYTES = 50L * 1024L * 1024L;

    /** Default connect timeout in seconds. */
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    /** Default read timeout in seconds. */
    public static final int DEFAULT_READ_TIMEOUT_SECONDS = 30;

    /** Maximum allowed HTTP redirect hops during media download. */
    public static final int MAX_REDIRECT_HOPS = 5;

    /** Strict allowlist of permitted MIME types for media downloads. */
    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            MediaTypes.PNG,
            MediaTypes.JPEG,
            MediaTypes.WEBP,
            MediaTypes.GIF,
            MediaTypes.PDF,
            MediaTypes.MPEG,
            MediaTypes.WAV,
            MediaTypes.OGG,
            MediaTypes.WEBM);

    /**
     * Resolves the maximum permitted byte size for the specified attachment type.
     */
    public static long resolveLimitForType(AttachmentType type, long customMaxImageBytes, long customMaxDocBytes) {
        if (type == AttachmentType.IMAGE) {
            return customMaxImageBytes > 0 ? customMaxImageBytes : DEFAULT_MAX_IMAGE_SIZE_BYTES;
        }
        if (type == AttachmentType.AUDIO || type == AttachmentType.DOCUMENT_PDF) {
            return customMaxDocBytes > 0 ? customMaxDocBytes : DEFAULT_MAX_DOCUMENT_SIZE_BYTES;
        }
        return customMaxDocBytes > 0 ? customMaxDocBytes : DEFAULT_MAX_DOCUMENT_SIZE_BYTES;
    }
}
