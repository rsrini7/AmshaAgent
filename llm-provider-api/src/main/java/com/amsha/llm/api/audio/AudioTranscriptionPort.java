package com.amsha.llm.api.audio;

/**
 * Provider execution port for converting bounded audio input into bounded text.
 *
 * <p>This is not a chat-model capability and has no persistence implementation. Implementations
 * must not retain request bytes after {@link #transcribe(AudioTranscriptionRequest)} returns.</p>
 */
public interface AudioTranscriptionPort {

    /**
     * Returns whether this adapter can attempt the declared audio payload.
     *
     * @param mimeType declared audio MIME type
     * @param declaredBytes declared payload size, or zero when unknown
     * @return true when the adapter is enabled and accepts the request shape
     */
    boolean supports(String mimeType, long declaredBytes);

    /**
     * Transcribes one already-governed audio payload.
     *
     * @param request bounded request whose bytes came through the media-governance seam
     * @return a payload-safe categorical result
     */
    AudioTranscriptionResult transcribe(AudioTranscriptionRequest request);
}
