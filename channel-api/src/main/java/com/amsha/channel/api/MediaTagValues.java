package com.amsha.channel.api;

import java.util.Locale;
import java.util.Set;

/**
 * Shared utility for extracting bounded, payload-free categorical metric tag values
 * from media attachments and related operational statuses.
 *
 * <p>Enforces strict allowlists on categorical values. Any unrecognized, malicious,
 * oversized, or payload-bearing strings map safely to {@code "unknown"}.
 */
public final class MediaTagValues {

    public static final String UNKNOWN = "unknown";
    public static final String OTHER = "other";

    private static final Set<String> ALLOWED_SOURCES = Set.of("inline", "uri", "telegram", "unsupported", "unknown");

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "attached",
            "fallback",
            "success",
            "error",
            "policy_denied",
            "hitl_required",
            "timeout",
            "redirect_denied",
            "security_violation",
            "oversized",
            "unsupported_mime",
            "magic_mismatch",
            "state_error",
            "unknown");

    private static final Set<String> ALLOWED_REASONS = Set.of(
            "unsupported_modality",
            "oversized",
            "cumulative_exceeded",
            "count_exceeded",
            "unavailable",
            "extraction_error",
            "transport_unsupported",
            "unsupported_type",
            "unknown");

    private MediaTagValues() {}

    public static String resolveSource(MediaAttachment attachment) {
        if (attachment == null) {
            return UNKNOWN;
        }
        if (attachment.inlineData() != null) {
            return "inline";
        }
        if (attachment.sourceUri() != null) {
            String scheme = attachment.sourceUri().getScheme();
            if ("telegram".equalsIgnoreCase(scheme)) {
                return "telegram";
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return "uri";
            }
            return "unsupported";
        }
        return UNKNOWN;
    }

    public static String resolveSource(String source) {
        if (source == null || source.isBlank()) {
            return UNKNOWN;
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_SOURCES.contains(normalized) ? normalized : UNKNOWN;
    }

    public static String resolveType(AttachmentType type) {
        if (type == null) {
            return OTHER;
        }
        return type.name().toLowerCase(Locale.ROOT);
    }

    public static String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_STATUSES.contains(normalized) ? normalized : UNKNOWN;
    }

    public static String resolveReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN;
        }
        String normalized = reason.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_REASONS.contains(normalized) ? normalized : UNKNOWN;
    }
}
