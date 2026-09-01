package com.amsha.llm.api.audio;

import java.time.Duration;
import java.util.Locale;

/** Payload-safe result of a provider speech synthesis attempt. */
public record SpeechSynthesisResult(
        Status status, byte[] audio, String mimeType, Duration wallClock, String failureReason) {

    private static final String WAV_MIME = "audio/wav";

    public enum Status {
        SYNTHESIZED,
        UNSUPPORTED,
        TOO_LARGE,
        TIMEOUT,
        FAILED
    }

    public SpeechSynthesisResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (wallClock == null || wallClock.isNegative()) {
            throw new IllegalArgumentException("wallClock must be non-negative");
        }
        audio = audio == null ? null : audio.clone();
        if (status == Status.SYNTHESIZED) {
            if (audio == null || audio.length == 0) {
                throw new IllegalArgumentException("audio must be present when synthesized");
            }
            if (mimeType == null || !WAV_MIME.equals(mimeType.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("mimeType must be supported audio/wav when synthesized");
            }
            mimeType = WAV_MIME;
            failureReason = null;
        } else {
            audio = null;
            mimeType = null;
            // A non-synthesized result must still carry a categorical failure reason so metrics and
            // logs never fall back to a blank or free-text tag. The provider always supplies one.
            if (failureReason == null || failureReason.isBlank()) {
                throw new IllegalArgumentException("failureReason must be present for non-synthesized results");
            }
            if (!failureReason.matches("[a-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("failureReason must be a categorical tag");
            }
        }
    }

    /** Returns a defensive copy of the synthesized audio, or null when not synthesized. */
    @Override
    public byte[] audio() {
        return audio == null ? null : audio.clone();
    }

    public static SpeechSynthesisResult synthesized(byte[] audio, String mimeType, Duration wallClock) {
        return new SpeechSynthesisResult(Status.SYNTHESIZED, audio, mimeType, wallClock, null);
    }

    public static SpeechSynthesisResult failure(Status status, String reason, Duration wallClock) {
        if (status == Status.SYNTHESIZED) {
            throw new IllegalArgumentException("failure status cannot be SYNTHESIZED");
        }
        return new SpeechSynthesisResult(status, null, null, wallClock, reason);
    }
}
