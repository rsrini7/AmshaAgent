package com.amsha.llm.api.audio;

/**
 * Provider execution port for converting bounded text into bounded speech audio.
 *
 * <p>This is not a chat-model capability and has no persistence implementation. Implementations
 * must not retain request text or response bytes after {@link #synthesize(SpeechSynthesisRequest)}
 * returns.
 *
 * <p>Speech synthesis is output-only. A synthesis model such as Qwen3-TTS is never a reasoning
 * model, never a transcription model, and is never registered in the modality routing matrix or
 * seeded as an {@code AUDIO} chat profile. See the P8 speech output decision.
 */
public interface SpeechSynthesisPort {
    /** Stable provider identifier used by explicit TTS selection. */
    String providerId();

    /**
     * Returns whether this adapter can attempt the declared request.
     *
     * <p>This method is pure: it performs no network I/O, no configuration lookup at call time
     * beyond already-injected state, and has no side effects.
     *
     * @param request bounded request to validate; implementations must not perform I/O
     * @return true when the adapter is enabled and accepts the request shape
     */
    boolean supports(SpeechSynthesisRequest request);

    /**
     * Synthesizes one bounded speakable text into bounded audio.
     *
     * @param request bounded request whose text was already projected to speakable prose
     * @return a payload-safe categorical result, never an exception for expected failures
     */
    SpeechSynthesisResult synthesize(SpeechSynthesisRequest request);
}
