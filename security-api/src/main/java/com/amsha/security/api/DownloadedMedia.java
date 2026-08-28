package com.amsha.security.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.amsha.channel.api.AttachmentType;

/**
 * Container holding safely validated and downloaded media content.
 *
 * <p>Enforces source XOR (exactly one of {@code inlineData} or {@code tempFilePath}),
 * non-negative {@code sizeBytes}, and valid size matching for inline data.
 *
 * <p>Implements {@link AutoCloseable} to provide deterministic cleanup of any
 * temporary files created during streamed downloads in {@code finally} blocks.
 */
@SuppressWarnings("ArrayRecordComponent")
public record DownloadedMedia(
        String mediaId,
        AttachmentType type,
        String mimeType,
        String fileName,
        long sizeBytes,
        byte[] inlineData,
        Path tempFilePath)
        implements AutoCloseable {

    public DownloadedMedia {
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0 (actual: " + sizeBytes + ")");
        }

        if ((inlineData == null && tempFilePath == null) || (inlineData != null && tempFilePath != null)) {
            throw new IllegalArgumentException(
                    "DownloadedMedia must have exactly one source representation (either inlineData or tempFilePath)");
        }

        if (inlineData != null && inlineData.length != sizeBytes) {
            throw new IllegalArgumentException(
                    "inlineData length (" + inlineData.length + ") does not match sizeBytes (" + sizeBytes + ")");
        }

        inlineData = inlineData != null ? inlineData.clone() : null;
    }

    public static DownloadedMedia ofInline(
            String mediaId, AttachmentType type, String mimeType, String fileName, byte[] data) {
        long size = data != null ? data.length : 0L;
        return new DownloadedMedia(mediaId, type, mimeType, fileName, size, data, null);
    }

    public static DownloadedMedia ofTempFile(
            String mediaId, AttachmentType type, String mimeType, String fileName, long sizeBytes, Path tempFile) {
        return new DownloadedMedia(mediaId, type, mimeType, fileName, sizeBytes, null, tempFile);
    }

    @Override
    public byte[] inlineData() {
        return inlineData != null ? inlineData.clone() : null;
    }

    @Override
    public void close() {
        if (tempFilePath != null) {
            try {
                Files.deleteIfExists(tempFilePath);
            } catch (IOException _) {
                // Best effort cleanup during finally
            }
        }
    }

    @Override
    public String toString() {
        return "DownloadedMedia[id=" + mediaId + ", type=" + type + ", mimeType=" + mimeType
                + ", fileName=" + (fileName != null ? fileName : "none")
                + ", sizeBytes=" + sizeBytes + ", hasTempFile=" + (tempFilePath != null) + "]";
    }
}
