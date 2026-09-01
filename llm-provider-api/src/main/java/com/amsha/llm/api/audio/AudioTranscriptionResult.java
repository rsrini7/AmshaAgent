package com.amsha.llm.api.audio;

import java.time.Duration;

/** Payload-safe result of a provider transcription attempt. */
public record AudioTranscriptionResult(
        Status status, String transcript, String language, Duration wallClock, String failureReason) {

    public enum Status {
        TRANSCRIBED,
        UNSUPPORTED,
        TOO_LARGE,
        TIMEOUT,
        FAILED
    }

    public AudioTranscriptionResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (wallClock == null || wallClock.isNegative()) {
            throw new IllegalArgumentException("wallClock must be non-negative");
        }
        if (status == Status.TRANSCRIBED) {
            if (transcript == null || transcript.isBlank()) {
                throw new IllegalArgumentException("transcript must be present when transcribed");
            }
            failureReason = null;
        } else {
            transcript = null;
            if (failureReason != null && !failureReason.matches("[a-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("failureReason must be a categorical tag");
            }
        }
        if (language != null && !language.isBlank() && !language.matches("[A-Za-z]{2,16}")) {
            throw new IllegalArgumentException("language must be a bounded language tag");
        }
    }

    public static AudioTranscriptionResult transcribed(String transcript, String language, Duration wallClock) {
        return new AudioTranscriptionResult(Status.TRANSCRIBED, transcript, language, wallClock, null);
    }

    public static AudioTranscriptionResult failure(Status status, String reason, Duration wallClock) {
        if (status == Status.TRANSCRIBED) {
            throw new IllegalArgumentException("failure status cannot be TRANSCRIBED");
        }
        return new AudioTranscriptionResult(status, null, null, wallClock, reason);
    }
}
