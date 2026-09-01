package com.amsha.llm.providers.audio;

/**
 * Raised when OGG/Opus to WAV transcoding cannot complete: ffmpeg missing, killed by timeout,
 * or exited non-zero. Callers treat this as a safe, categorical failure — the audio is left
 * untouched and the downstream path degrades to its text fallback rather than sending an
 * undecodable payload to the STT endpoint.
 */
class AudioConversionException extends Exception {

    AudioConversionException(String message) {
        super(message);
    }

    AudioConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
