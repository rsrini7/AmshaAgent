package com.amsha.llm.api.audio;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Immutable, bounded input passed to an {@link AudioTranscriptionPort}. */
public record AudioTranscriptionRequest(
        String requestId,
        String mimeType,
        long declaredBytes,
        byte[] inlineData,
        Path tempFilePath,
        String languageHint,
        Duration timeout,
        Duration declaredDuration) {

    public AudioTranscriptionRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        if (declaredBytes < 0) {
            throw new IllegalArgumentException("declaredBytes must be >= 0");
        }
        if ((inlineData == null) == (tempFilePath == null)) {
            throw new IllegalArgumentException("exactly one of inlineData or tempFilePath must be provided");
        }
        if (inlineData != null && inlineData.length != declaredBytes) {
            throw new IllegalArgumentException("inlineData length must equal declaredBytes");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (declaredDuration != null && declaredDuration.isNegative()) {
            throw new IllegalArgumentException("declaredDuration must be non-negative");
        }
        languageHint = languageHint == null || languageHint.isBlank() ? null : languageHint.trim();
        inlineData = inlineData == null ? null : inlineData.clone();
    }

    /** Creates a request without a sender-declared duration hint. */
    public AudioTranscriptionRequest(
            String requestId,
            String mimeType,
            long declaredBytes,
            byte[] inlineData,
            Path tempFilePath,
            String languageHint,
            Duration timeout) {
        this(requestId, mimeType, declaredBytes, inlineData, tempFilePath, languageHint, timeout, null);
    }

    @Override
    public byte[] inlineData() {
        return inlineData == null ? null : inlineData.clone();
    }

    /** Creates an inline request for already-bounded bytes. */
    public static AudioTranscriptionRequest ofInline(
            String requestId, String mimeType, byte[] data, String languageHint, Duration timeout) {
        return ofInline(requestId, mimeType, data, languageHint, timeout, null);
    }

    /** Creates an inline request with an optional sender-declared duration hint. */
    public static AudioTranscriptionRequest ofInline(
            String requestId,
            String mimeType,
            byte[] data,
            String languageHint,
            Duration timeout,
            Duration declaredDuration) {
        Objects.requireNonNull(data, "data must not be null");
        return new AudioTranscriptionRequest(
                requestId, mimeType, data.length, data, null, languageHint, timeout, declaredDuration);
    }

    /** Creates a request backed by a governed temporary file. */
    public static AudioTranscriptionRequest ofTempFile(
            String requestId,
            String mimeType,
            long declaredBytes,
            Path tempFilePath,
            String languageHint,
            Duration timeout) {
        return ofTempFile(requestId, mimeType, declaredBytes, tempFilePath, languageHint, timeout, null);
    }

    /** Creates a governed temporary-file request with an optional duration hint. */
    public static AudioTranscriptionRequest ofTempFile(
            String requestId,
            String mimeType,
            long declaredBytes,
            Path tempFilePath,
            String languageHint,
            Duration timeout,
            Duration declaredDuration) {
        return new AudioTranscriptionRequest(
                requestId,
                mimeType,
                declaredBytes,
                null,
                Objects.requireNonNull(tempFilePath),
                languageHint,
                timeout,
                declaredDuration);
    }
}
