package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalOnnxAudioTranscriptionAdapterDeepTest {

    @Test
    void transcribeWhenDisabledReturnsUnsupported() {
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                mock(OnnxRuntimeManager.class),
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                false,
                false,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                1000L,
                1000);

        AudioTranscriptionRequest request = AudioTranscriptionRequest.ofInline(
                "req-dis", "audio/wav", new byte[] {1, 2, 3}, "en", Duration.ofSeconds(5));
        AudioTranscriptionResult result = adapter.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        assertEquals("local_stt_disabled_or_unavailable", result.failureReason());
    }

    @Test
    void transcribeWhenUnsupportedMimeReturnsUnsupported() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                true,
                true,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                1000L,
                1000);

        AudioTranscriptionRequest request = AudioTranscriptionRequest.ofInline(
                "req-bad", "image/png", new byte[] {1, 2, 3}, "en", Duration.ofSeconds(5));
        AudioTranscriptionResult result = adapter.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        assertEquals("unsupported_audio", result.failureReason());
    }

    @Test
    void transcribeWhenEmptyPayloadReturnsFailed() {
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));

        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-empty", "audio/wav", new byte[0], "en", Duration.ofSeconds(5));
        AudioTranscriptionResult result = adapter.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("empty_payload", result.failureReason());
    }

    @Test
    void transcribePayloadFromFileExceedingLimitThrows(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("audio.wav");
        Files.write(file, new byte[200]);

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                50L);

        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofTempFile("req-file", "audio/wav", 200L, file, "en", Duration.ofSeconds(5));
        AudioTranscriptionResult result = adapter.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
    }

    @Test
    void transcribePayloadFromFileWithinLimitFailsOnCorruptContent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("audio.wav");
        Files.write(file, new byte[20]);

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                500L);

        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofTempFile("req-ok", "audio/wav", 20L, file, "en", Duration.ofSeconds(5));
        AudioTranscriptionResult result = adapter.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
    }

    @Test
    void executeInferenceTruncatesLongTranscript() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        LeaseBackedWhisperRunner runner = mock(LeaseBackedWhisperRunner.class);
        OrtEnvironment env = mock(OrtEnvironment.class);

        String longTranscript = "abcdefghijklmnopqrstuvwxyz1234567890";
        when(scheduler.submit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(longTranscript));

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                runner,
                env,
                Duration.ofSeconds(30),
                1000L,
                10,
                "ffmpeg");

        var method = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                "executeInference",
                OrtEnvironment.class,
                LeaseBackedWhisperRunner.class,
                float[].class,
                String.class,
                Instant.class,
                long.class);
        method.setAccessible(true);

        AudioTranscriptionResult res = (AudioTranscriptionResult) method.invoke(
                adapter, env, runner, new float[10], "en", Instant.now().plusSeconds(10), System.nanoTime());
        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, res.status());
        assertEquals(10, res.transcript().length());
        assertEquals("abcdefghij", res.transcript());
    }

    @Test
    void executeInferenceHandlesEmptyTranscript() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(null));

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));

        var method = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                "executeInference",
                OrtEnvironment.class,
                LeaseBackedWhisperRunner.class,
                float[].class,
                String.class,
                Instant.class,
                long.class);
        method.setAccessible(true);

        AudioTranscriptionResult res = (AudioTranscriptionResult) method.invoke(
                adapter,
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10),
                System.nanoTime());
        assertEquals(AudioTranscriptionResult.Status.FAILED, res.status());
        assertEquals("empty_transcript", res.failureReason());
    }

    @Test
    void executeInferenceHandlesInterruptedException() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        CompletableFuture<String> future = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException();
            }
        };
        when(scheduler.submit(any(), any())).thenAnswer(inv -> future);

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));

        var method = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                "executeInference",
                OrtEnvironment.class,
                LeaseBackedWhisperRunner.class,
                float[].class,
                String.class,
                Instant.class,
                long.class);
        method.setAccessible(true);

        AudioTranscriptionResult res = (AudioTranscriptionResult) method.invoke(
                adapter,
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10),
                System.nanoTime());
        assertEquals(AudioTranscriptionResult.Status.FAILED, res.status());
        assertEquals("interrupted", res.failureReason());
        Thread.interrupted(); // clear interrupted status
    }

    @Test
    void executeInferenceHandlesCancellationException() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        CompletableFuture<String> future = new CompletableFuture<>();
        future.cancel(true);
        when(scheduler.submit(any(), any())).thenAnswer(inv -> future);

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));

        var method = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                "executeInference",
                OrtEnvironment.class,
                LeaseBackedWhisperRunner.class,
                float[].class,
                String.class,
                Instant.class,
                long.class);
        method.setAccessible(true);

        AudioTranscriptionResult res = (AudioTranscriptionResult) method.invoke(
                adapter,
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10),
                System.nanoTime());
        assertEquals(AudioTranscriptionResult.Status.FAILED, res.status());
        assertEquals("cancelled", res.failureReason());
    }

    @Test
    void executeInferenceHandlesSchedulingOverload() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any()))
                .thenThrow(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.OVERLOAD_REJECTED, "queue full"));

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));

        var method = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                "executeInference",
                OrtEnvironment.class,
                LeaseBackedWhisperRunner.class,
                float[].class,
                String.class,
                Instant.class,
                long.class);
        method.setAccessible(true);

        AudioTranscriptionResult res = (AudioTranscriptionResult) method.invoke(
                adapter,
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10),
                System.nanoTime());
        assertEquals(AudioTranscriptionResult.Status.FAILED, res.status());
        assertEquals("overload_rejected", res.failureReason());
    }

    @Test
    void isTerminationOrTimeoutHelper() {
        assertTrue(invokeIsTerminationOrTimeout(new TimeoutException()));
        assertTrue(invokeIsTerminationOrTimeout(
                new OrtException(OrtException.OrtErrorCode.ORT_FAIL, "Session was terminated")));
        assertTrue(invokeIsTerminationOrTimeout(new RuntimeException("wrap", new TimeoutException())));
        assertFalse(invokeIsTerminationOrTimeout(new RuntimeException("general error")));
        assertFalse(invokeIsTerminationOrTimeout(null));
    }

    private static boolean invokeIsTerminationOrTimeout(Throwable t) {
        try {
            var m = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                    "isTerminationOrTimeout", Throwable.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
