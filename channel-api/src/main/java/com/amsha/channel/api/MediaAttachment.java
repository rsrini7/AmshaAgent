package com.amsha.channel.api;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Channel-neutral immutable representation of a media attachment (image, audio, document).
 *
 * <p>Enforces source exclusivity: exactly one of {@code sourceUri} or {@code inlineData}
 * must be populated. Defensive copying is applied to raw bytes and metadata maps.
 *
 * <p>{@code sizeBytes} is advisory; the streamed byte count during retrieval is authoritative.
 * Inline data has a hard transport ceiling of {@link #MAX_INLINE_DATA_BYTES}; Slab B adds
 * configurable per-type and per-turn limits before any governed download or extraction.
 * Embedded credentials, user-info, bearer token query parameters, and raw bot token paths in {@code sourceUri} are forbidden.
 *
 * <p>Metadata is opaque, non-secret descriptive data supplied by the channel adapter. Callers
 * must not put credentials or media payloads in metadata, and logging/telemetry code must not
 * serialize it without an explicit redaction policy.
 */
@SuppressWarnings("ArrayRecordComponent")
public record MediaAttachment(
        String attachmentId,
        AttachmentType type,
        String mimeType,
        String fileName,
        long sizeBytes,
        URI sourceUri,
        byte[] inlineData,
        Map<String, String> metadata) {

    /** Hard upper bound for inline data carried by this channel-neutral contract. */
    public static final long MAX_INLINE_DATA_BYTES = 50L * 1024L * 1024L;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "telegram", "attachment");
    private static final Set<String> FORBIDDEN_QUERY_PARAMETER_NAMES = Set.of(
            "token",
            "access_token",
            "refresh_token",
            "id_token",
            "auth_token",
            "redirect_token",
            "key",
            "secret",
            "auth",
            "password",
            "bearer",
            "apikey",
            "api_key",
            "authorization");
    private static final Set<String> FORBIDDEN_METADATA_KEY_NAMES =
            Set.of("token", "api_key", "apikey", "secret", "password", "bearer", "authorization", "auth_token");
    private static final Set<String> FORBIDDEN_METADATA_KEY_FRAGMENTS = Set.of("token", "secret", "password");

    public MediaAttachment {
        Objects.requireNonNull(attachmentId, "attachmentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");

        if (sizeBytes < -1) {
            throw new IllegalArgumentException("sizeBytes must be >= -1 (where -1 indicates unknown length)");
        }

        if ((sourceUri == null && inlineData == null) || (sourceUri != null && inlineData != null)) {
            throw new IllegalArgumentException(
                    "MediaAttachment must have exactly one source representation (either sourceUri or inlineData)");
        }
        if (inlineData != null && inlineData.length > MAX_INLINE_DATA_BYTES) {
            throw new IllegalArgumentException(
                    "inlineData exceeds the channel contract limit of " + MAX_INLINE_DATA_BYTES + " bytes");
        }

        if (sourceUri != null) {
            validateSourceUri(sourceUri);
        }

        if (metadata != null) {
            validateMetadata(metadata);
            metadata = Map.copyOf(metadata);
        } else {
            metadata = Map.of();
        }

        inlineData = inlineData != null ? inlineData.clone() : null;
    }

    private static void validateSourceUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Unsupported sourceUri scheme: " + scheme + " (allowed: " + ALLOWED_SCHEMES + ")");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("sourceUri must not contain user-info or embedded credentials");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            String lowerPath = rawPath.toLowerCase(Locale.ROOT);
            if (lowerPath.contains("/token/") || lowerPath.contains("/bearer/") || lowerPath.contains("/secret/")) {
                throw new IllegalArgumentException("sourceUri path must not contain embedded credential path segments");
            }
            if (lowerPath.contains("/bot") && lowerPath.matches("(?i).*/bot[0-9]+:[a-z0-9_-]+.*")) {
                throw new IllegalArgumentException(
                        "sourceUri must not contain Telegram bot token path; use opaque telegram://file/<file_id> scheme instead");
            }
        }
        validateQueryParameters(uri);
    }

    private static void validateQueryParameters(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return;
        }
        for (String pair : rawQuery.split("&", -1)) {
            String rawName = pair;
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex >= 0) {
                rawName = pair.substring(0, equalsIndex);
            }
            if (rawName.isEmpty()) {
                continue;
            }
            String parameterName;
            try {
                parameterName =
                        URLDecoder.decode(rawName, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("sourceUri query contains malformed parameter encoding", e);
            }
            if (FORBIDDEN_QUERY_PARAMETER_NAMES.contains(parameterName)) {
                throw new IllegalArgumentException(
                        "sourceUri query must not contain sensitive credential parameter: " + parameterName);
            }
        }
    }

    private static void validateMetadata(Map<String, String> meta) {
        for (var entry : meta.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key must not be null");
            Objects.requireNonNull(entry.getValue(), "metadata value must not be null");
            String lowerKey = key.toLowerCase(Locale.ROOT);
            String forbiddenName = findExactMatch(lowerKey, FORBIDDEN_METADATA_KEY_NAMES);
            String forbiddenFragment = findContainingMatch(lowerKey, FORBIDDEN_METADATA_KEY_FRAGMENTS);
            String forbidden = forbiddenName != null ? forbiddenName : forbiddenFragment;
            if (forbidden != null) {
                throw new IllegalArgumentException(
                        "metadata key must not contain sensitive credential keyword: " + forbidden);
            }
        }
    }

    private static String findExactMatch(String value, Set<String> candidates) {
        return candidates.contains(value) ? value : null;
    }

    private static String findContainingMatch(String value, Set<String> candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static MediaAttachment ofUri(
            String attachmentId,
            AttachmentType type,
            String mimeType,
            String fileName,
            long sizeBytes,
            URI sourceUri,
            Map<String, String> metadata) {
        return new MediaAttachment(attachmentId, type, mimeType, fileName, sizeBytes, sourceUri, null, metadata);
    }

    public static MediaAttachment ofInline(
            String attachmentId,
            AttachmentType type,
            String mimeType,
            String fileName,
            byte[] inlineData,
            Map<String, String> metadata) {
        long size = inlineData != null ? inlineData.length : 0L;
        return new MediaAttachment(attachmentId, type, mimeType, fileName, size, null, inlineData, metadata);
    }

    @Override
    public byte[] inlineData() {
        return inlineData != null ? inlineData.clone() : null;
    }

    @Override
    public String toString() {
        return "MediaAttachment[id=" + attachmentId + ", type=" + type + ", mimeType=" + mimeType
                + ", fileName=" + (fileName != null ? fileName : "none")
                + ", size=" + sizeBytes + ", source=" + (sourceUri != null ? "uri" : "inline") + "]";
    }
}
