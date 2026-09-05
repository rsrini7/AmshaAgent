package com.amsha.localml.onnx;

/**
 * Raised when audio transcoding via ffmpeg fails, times out, or ffmpeg is unavailable.
 */
public class AudioTranscodingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AudioTranscodingException(String message) {
        super(message);
    }

    public AudioTranscodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
