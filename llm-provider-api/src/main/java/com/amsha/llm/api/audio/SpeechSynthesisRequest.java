package com.amsha.llm.api.audio;

import java.time.Duration;

/**
 * Immutable, bounded input passed to a {@link SpeechSynthesisPort}.
 *
 * <p>The text is expected to be already projected to speakable prose: reasoning traces, tool output,
 * raw transcripts, Markdown syntax, and internal metadata must never reach this record.
 *
 * <p>This transport-agnostic record validates identity and cross-field rules only. The text-size
 * bound is <em>not</em> enforced here; it is owned by the caller ({@code SpeakableTextProjector.truncate}
 * via {@code amsha.tts.max-input-chars}) and re-checked by the provider
 * ({@code OmlxSpeechSynthesisProvider}, which maps an over-limit request to {@code TOO_LARGE}). That
 * split keeps the contract portable while the authoritative bound lives with the configured provider.
 */
public record SpeechSynthesisRequest(
        String requestId, String text, String voice, String responseFormat, Duration timeout) {

    public SpeechSynthesisRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (responseFormat == null || !responseFormat.matches("[a-z0-9]{1,16}")) {
            throw new IllegalArgumentException("responseFormat must be a bounded format token");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        voice = voice == null || voice.isBlank() ? null : voice.trim();
    }

    /** Creates a synthesis request for already-projected speakable text. */
    public static SpeechSynthesisRequest of(
            String requestId, String text, String voice, String responseFormat, Duration timeout) {
        return new SpeechSynthesisRequest(requestId, text, voice, responseFormat, timeout);
    }
}
