package com.amsha.llm.api.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SpeechSynthesisContractTest {

    @Test
    void requestRejectsBlankOrUnboundedInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisRequest.of(" ", "hello", null, "wav", Duration.ofSeconds(10)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisRequest.of("r1", "", null, "wav", Duration.ofSeconds(10)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisRequest.of("r1", "hello", null, "WAV!", Duration.ofSeconds(10)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisRequest.of("r1", "hello", null, "wav", Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisRequest.of("r1", "hello", null, "wav", Duration.ofSeconds(-1)));
    }

    @Test
    void requestTrimsVoiceAndAllowsNull() {
        SpeechSynthesisRequest withVoice =
                SpeechSynthesisRequest.of("r1", "hello", "  speaker-1  ", "wav", Duration.ofSeconds(10));
        assertEquals("speaker-1", withVoice.voice());

        SpeechSynthesisRequest noVoice = SpeechSynthesisRequest.of("r2", "hello", null, "wav", Duration.ofSeconds(10));
        assertNull(noVoice.voice());

        SpeechSynthesisRequest blankVoice =
                SpeechSynthesisRequest.of("r3", "hello", "   ", "wav", Duration.ofSeconds(10));
        assertNull(blankVoice.voice());
    }

    @Test
    void synthesizedResultDefensivelyCopiesAudioAndRequiresMeta() {
        byte[] source = {1, 2, 3, 4};
        SpeechSynthesisResult result = SpeechSynthesisResult.synthesized(source, "audio/wav", Duration.ofMillis(7));

        source[0] = 9; // mutate caller's array after construction
        assertArrayEquals(new byte[] {1, 2, 3, 4}, result.audio());
        assertEquals("audio/wav", result.mimeType());
        assertEquals(SpeechSynthesisResult.Status.SYNTHESIZED, result.status());
        assertNull(result.failureReason());
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisResult.synthesized(source, "text/plain", Duration.ofMillis(7)));
    }

    @Test
    void nonSynthesizedResultNeverCarriesAudioAndEnforcesCategoricalReason() {
        SpeechSynthesisResult failed = SpeechSynthesisResult.failure(
                SpeechSynthesisResult.Status.FAILED, "provider_error", Duration.ofMillis(4));

        assertNull(failed.audio());
        assertNull(failed.mimeType());
        assertEquals("provider_error", failed.failureReason());

        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisResult.failure(
                        SpeechSynthesisResult.Status.UNSUPPORTED, "not a valid reason!", Duration.ofMillis(1)));
        // A missing failure reason is rejected: non-synthesized results must stay categorical.
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisResult.failure(SpeechSynthesisResult.Status.TIMEOUT, null, Duration.ofMillis(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisResult.failure(SpeechSynthesisResult.Status.FAILED, "   ", Duration.ofMillis(1)));
        // synthesized() rejects empty audio (magic-byte validation lives in the provider, not the record)
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechSynthesisResult.synthesized(new byte[0], "audio/wav", Duration.ofMillis(1)));
    }
}
