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
                        "att-1", "audio/wav", 1, new byte[] {1}, Path.of("/tmp/media"), null, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioTranscriptionRequest("att-1", "audio/wav", 0, null, null, null, Duration.ofSeconds(1)));
    }

    @Test
    void nonTranscribedResultNeverCarriesTranscript() {
        AudioTranscriptionResult result = AudioTranscriptionResult.failure(
                AudioTranscriptionResult.Status.FAILED, "provider_error", Duration.ofMillis(4));

        assertNull(result.transcript());
        assertEquals("provider_error", result.failureReason());
    }
}
