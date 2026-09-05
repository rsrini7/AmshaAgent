package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;

import org.junit.jupiter.api.Test;

class CompositeAudioTranscriptionPortTest {

    private record TestTranscriptionPort(int priority, boolean supports, String transcriptResult, String failureReason)
            implements AudioTranscriptionPort {
        @Override
        public boolean supports(String mimeType, long declaredBytes) {
            return supports;
        }

        @Override
        public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
            if (transcriptResult != null) {
                return AudioTranscriptionResult.transcribed(transcriptResult, "en", Duration.ZERO);
            }
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED,
                    failureReason != null ? failureReason : "test_failure",
                    Duration.ZERO);
        }
    }

    @Test
    void selectsHighestPrioritySupportingDelegate() {
        // High priority local port (priority 100), but doesn't support the format
        TestTranscriptionPort localDisabled = new TestTranscriptionPort(100, false, "local_transcript", null);
        // Lower priority OMLX port (priority 50), supports the format
        TestTranscriptionPort omlxFallback = new TestTranscriptionPort(50, true, "omlx_transcript", null);

        CompositeAudioTranscriptionPort composite =
                new CompositeAudioTranscriptionPort(List.of(omlxFallback, localDisabled));

        assertTrue(composite.supports("audio/ogg", 1024));
        assertEquals(100, composite.priority());

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-1", "audio/ogg", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("omlx_transcript", result.transcript());
    }

    @Test
    void usesHighPriorityWhenSupported() {
        // High priority local port (priority 100) supports WAV
        TestTranscriptionPort localWav = new TestTranscriptionPort(100, true, "local_transcript", null);
        // Lower priority OMLX port (priority 50) also supports WAV
        TestTranscriptionPort omlxWav = new TestTranscriptionPort(50, true, "omlx_transcript", null);

        CompositeAudioTranscriptionPort composite = new CompositeAudioTranscriptionPort(List.of(omlxWav, localWav));

        assertTrue(composite.supports("audio/wav", 1024));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-2", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("local_transcript", result.transcript());
    }

    @Test
    void returnsUnsupportedWhenNoDelegateSupports() {
        TestTranscriptionPort local = new TestTranscriptionPort(100, false, "local", null);
        TestTranscriptionPort omlx = new TestTranscriptionPort(50, false, "omlx", null);

        CompositeAudioTranscriptionPort composite = new CompositeAudioTranscriptionPort(List.of(local, omlx));

        assertFalse(composite.supports("video/mp4", 1024));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-3", "video/mp4", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        assertEquals("no_supporting_transcription_provider", result.failureReason());
        assertNull(result.transcript());
    }

    @Test
    void cascadesWhenHighPriorityThrowsRuntimeException() {
        AudioTranscriptionPort throwingPort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                throw new IllegalStateException("Event-loop thread violation simulation");
            }

            @Override
            public int priority() {
                return 100;
            }
        };
        TestTranscriptionPort fallback = new TestTranscriptionPort(50, true, "fallback_transcript", null);

        // Default: allowFallbackOnFailure = false -> returns failure immediately to prevent audio egress
        CompositeAudioTranscriptionPort composite =
                new CompositeAudioTranscriptionPort(List.of(throwingPort, fallback));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-throw", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("delegate_threw", result.failureReason());

        // When explicitly allowed: cascades to fallback
        CompositeAudioTranscriptionPort compositeAllowed =
                new CompositeAudioTranscriptionPort(List.of(throwingPort, fallback), true);
        AudioTranscriptionResult resultAllowed = compositeAllowed.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, resultAllowed.status());
        assertEquals("fallback_transcript", resultAllowed.transcript());
    }

    @Test
    void returnsFailedImmediatelyWhenFallbackDisallowed() {
        TestTranscriptionPort failingPort = new TestTranscriptionPort(100, true, null, "model_init_failed");
        TestTranscriptionPort fallback = new TestTranscriptionPort(50, true, "fallback_transcript", null);

        // Default: allowFallbackOnFailure = false -> returns local failure immediately
        CompositeAudioTranscriptionPort composite = new CompositeAudioTranscriptionPort(List.of(failingPort, fallback));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-fail", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("model_init_failed", result.failureReason());

        // When explicitly allowed: cascades to fallback
        CompositeAudioTranscriptionPort compositeAllowed =
                new CompositeAudioTranscriptionPort(List.of(failingPort, fallback), true);
        AudioTranscriptionResult resultAllowed = compositeAllowed.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, resultAllowed.status());
        assertEquals("fallback_transcript", resultAllowed.transcript());
    }

    @Test
    void cascadesWhenHighPriorityReturnsUnsupportedEvenWithFallbackDisallowed() {
        AudioTranscriptionPort unsupportedPort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true; // declares support
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                return AudioTranscriptionResult.failure(
                        AudioTranscriptionResult.Status.UNSUPPORTED,
                        "local_stt_disabled_or_unavailable",
                        Duration.ZERO);
            }

            @Override
            public int priority() {
                return 100;
            }
        };
        TestTranscriptionPort fallback = new TestTranscriptionPort(50, true, "fallback_transcript", null);

        // Default: allowFallbackOnFailure = false -> UNSUPPORTED delegates still cascade
        CompositeAudioTranscriptionPort composite =
                new CompositeAudioTranscriptionPort(List.of(unsupportedPort, fallback), false);

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-unsupported", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("fallback_transcript", result.transcript());
    }

    @Test
    void returnsTooLargeImmediatelyWithoutCascading() {
        AudioTranscriptionPort tooLargePort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                return AudioTranscriptionResult.failure(
                        AudioTranscriptionResult.Status.TOO_LARGE, "audio_duration_exceeded", Duration.ZERO);
            }

            @Override
            public int priority() {
                return 100;
            }
        };
        TestTranscriptionPort fallback = new TestTranscriptionPort(50, true, "should_not_reach", null);

        CompositeAudioTranscriptionPort composite =
                new CompositeAudioTranscriptionPort(List.of(tooLargePort, fallback));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-large", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, result.status());
        assertEquals("audio_duration_exceeded", result.failureReason());
    }

    @Test
    void deterministicTieBreakingByClassName() {
        class AdapterA implements AudioTranscriptionPort {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                return AudioTranscriptionResult.transcribed("transcript_A", "en", Duration.ZERO);
            }

            @Override
            public int priority() {
                return 50;
            }
        }

        class AdapterB implements AudioTranscriptionPort {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                return AudioTranscriptionResult.transcribed("transcript_B", "en", Duration.ZERO);
            }

            @Override
            public int priority() {
                return 50;
            }
        }

        AdapterA a = new AdapterA();
        AdapterB b = new AdapterB();
        CompositeAudioTranscriptionPort composite = new CompositeAudioTranscriptionPort(List.of(b, a));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-tie", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        // Class name AdapterA sorts before AdapterB alphabetically
        assertEquals("transcript_A", result.transcript());
    }

    @Test
    void emptyDelegatesReturnsZeroPriorityAndRejects() {
        CompositeAudioTranscriptionPort composite = new CompositeAudioTranscriptionPort(List.of());
        assertEquals(0, composite.priority());
        assertFalse(composite.supports("audio/wav", 100));

        byte[] dummy = new byte[] {1, 2, 3};
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-4", "audio/wav", dummy, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = composite.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        assertEquals("no_supporting_transcription_provider", result.failureReason());
    }
}
