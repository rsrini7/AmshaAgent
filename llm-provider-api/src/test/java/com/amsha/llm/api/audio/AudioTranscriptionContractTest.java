package com.amsha.llm.api.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AudioTranscriptionContractTest {

    @Test
    void inlineRequestDefensivelyCopiesBytes() {
        byte[] source = {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("att-1", "audio/wav", source, "en", Duration.ofSeconds(10));

        source[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, request.inlineData());
        assertEquals(3, request.declaredBytes());
    }

    @Test
    void requestRejectsAmbiguousOrUnboundedSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioTranscriptionRequest(
                        "att-1",
                        "audio/wav",
                        1,
                        new byte[] {1},
                        Path.of("/tmp/media"),
                        null,
                        Duration.ofSeconds(1),
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioTranscriptionRequest(
                        "att-1", "audio/wav", 0, null, null, null, Duration.ofSeconds(1), null));
    }

    @Test
    void declaredDurationIsOptionalAndCannotBeNegative() {
        AudioTranscriptionRequest request = AudioTranscriptionRequest.ofInline(
                "att-duration", "audio/ogg", new byte[] {1}, null, Duration.ofSeconds(10), Duration.ofSeconds(12));

        assertEquals(Duration.ofSeconds(12), request.declaredDuration());
        assertEquals(Duration.ofSeconds(10), request.timeout());
        assertThrows(
                IllegalArgumentException.class,
                () -> AudioTranscriptionRequest.ofInline(
                        "att-duration",
                        "audio/ogg",
                        new byte[] {1},
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(-1)));
    }

    @Test
    void transcriptionResultAcceptsBcp47TagsAndRejectsMalformedTags() {
        AudioTranscriptionResult result = AudioTranscriptionResult.transcribed("नमस्ते", "hi-IN", Duration.ZERO);

        assertEquals("hi-IN", result.language());
        assertThrows(
                IllegalArgumentException.class,
                () -> AudioTranscriptionResult.transcribed("नमस्ते", "hi_IN", Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> AudioTranscriptionResult.transcribed("नमस्ते", "a-", Duration.ZERO));
    }

    @Test
    void nonTranscribedResultNeverCarriesTranscript() {
        AudioTranscriptionResult result = AudioTranscriptionResult.failure(
                AudioTranscriptionResult.Status.FAILED, "provider_error", Duration.ofMillis(4));

        assertNull(result.transcript());
        assertEquals("provider_error", result.failureReason());
    }
}
