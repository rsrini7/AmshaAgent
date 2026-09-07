package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtEnvironment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Vertx;

class LocalOnnxAudioTranscriptionAdapterTest {

    private static byte[] create16kMonoWav(int numSamples, short sampleVal) {
        ByteBuffer bb = ByteBuffer.allocate(44 + numSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[] {'R', 'I', 'F', 'F'});
        bb.putInt(36 + numSamples * 2);
        bb.put(new byte[] {'W', 'A', 'V', 'E'});
        bb.put(new byte[] {'f', 'm', 't', ' '});
        bb.putInt(16);
        bb.putShort((short) 1); // PCM
        bb.putShort((short) 1); // mono
        bb.putInt(16000); // 16kHz
        bb.putInt(32000); // byte rate
        bb.putShort((short) 2); // block align
        bb.putShort((short) 16); // bits per sample
        bb.put(new byte[] {'d', 'a', 't', 'a'});
        bb.putInt(numSamples * 2);
        for (int i = 0; i < numSamples; i++) {
            bb.putShort(sampleVal);
        }
        return bb.array();
    }

    @Test
    void isAvailableReflectsFlagsAndRuntime() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxAudioTranscriptionAdapter adapterAvailable = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                true,
                true,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                26214400L,
                12000);
        assertTrue(adapterAvailable.isAvailable());

        LocalOnnxAudioTranscriptionAdapter adapterDisabled = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                false,
                true,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                26214400L,
                12000);
        assertFalse(adapterDisabled.isAvailable());

        LocalOnnxAudioTranscriptionAdapter adapterOnnxDisabled = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                true,
                false,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                26214400L,
                12000);
        assertFalse(adapterOnnxDisabled.isAvailable());

        when(runtime.isReady()).thenReturn(false);
        assertFalse(adapterAvailable.isAvailable());
    }

    @Test
    void priorityReturns100() {
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));
        assertEquals(100, adapter.priority());
    }

    @Test
    void supportsValidatesMimeAndSizeAndAvailability() {
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
                12000);

        assertTrue(adapter.supports("audio/wav", 500));
        assertTrue(adapter.supports("audio/x-wav", 500));
        assertTrue(adapter.supports("audio/wave", 500));
        assertTrue(adapter.supports("audio/pcm", 500));
        assertTrue(adapter.supports("audio/ogg", 500));
        assertTrue(adapter.supports("audio/opus", 500));
        assertTrue(adapter.supports("audio/mpeg", 500));
        assertTrue(adapter.supports("audio/mp3", 500));

        assertFalse(adapter.supports("image/png", 500));
        assertFalse(adapter.supports("text/plain", 500));
        assertFalse(adapter.supports(null, 500));
        assertFalse(adapter.supports("audio/wav", -1));
        assertFalse(adapter.supports("audio/wav", 1001)); // exceeds 1000L

        // When disabled
        LocalOnnxAudioTranscriptionAdapter disabledAdapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                false,
                true,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                1000L,
                12000);
        assertFalse(disabledAdapter.supports("audio/wav", 500));
    }

    @Test
    void disabledAdapterReturnsUnsupportedResult() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                false, // disabled
                true,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                26214400L,
                12000);

        byte[] wav = create16kMonoWav(16000, (short) 100);
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-1", "audio/wav", wav, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        assertEquals("local_stt_disabled_or_unavailable", result.failureReason());
        assertNull(result.transcript());
    }

    @Test
    void rejectsQuarkusEventLoopThread() throws Exception {
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
                26214400L,
                12000);

        Vertx vertx = Vertx.vertx();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            byte[] wav = create16kMonoWav(16000, (short) 100);
            AudioTranscriptionRequest request =
                    AudioTranscriptionRequest.ofInline("req-loop", "audio/wav", wav, "en", Duration.ofSeconds(10));

            vertx.runOnContext(_ -> {
                try {
                    adapter.transcribe(request);
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    done.complete(null);
                }
            });
            done.get(5, TimeUnit.SECONDS);
            assertNotNull(thrown.get());
            assertEquals(IllegalStateException.class, thrown.get().getClass());
            assertTrue(thrown.get().getMessage().contains("must not block a Quarkus event-loop thread"));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void transcribeEnforcesAudioDurationLimit() {
        byte[] largeAudio = create16kMonoWav(16000 * 35, (short) 100); // 35 seconds
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-over", "audio/wav", largeAudio, "en", Duration.ofSeconds(10));

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                26214400L);

        AudioTranscriptionResult result = adapter.transcribe(request);
        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, result.status());
        assertEquals("audio_duration_exceeded", result.failureReason());
    }

    @Test
    void transcribeEnforcesMaxTranscriptCharsTruncation() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any())).thenAnswer(invocation -> {
            Callable<String> task = invocation.getArgument(1);
            return CompletableFuture.completedFuture(task.call());
        });

        LeaseBackedWhisperRunner runner = mock(LeaseBackedWhisperRunner.class);
        when(runner.transcribe(any(), any(), any(), any())).thenReturn("0123456789ABCDEF");

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                runner,
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                26214400L,
                10); // max 10 chars

        byte[] wav = create16kMonoWav(16000, (short) 100);
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-trunc", "audio/wav", wav, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("0123456789", result.transcript()); // truncated to 10 chars
    }

    @Test
    void transcribeHandlesSchedulingOverload() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any()))
                .thenThrow(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.OVERLOAD_REJECTED, "Queue is full"));

        LeaseBackedWhisperRunner runner = mock(LeaseBackedWhisperRunner.class);
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class), scheduler, runner, mock(OrtEnvironment.class));

        byte[] wav = create16kMonoWav(16000, (short) 100);
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-overload", "audio/wav", wav, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("overload_rejected", result.failureReason());
    }

    @Test
    void parseAudioPcmConvertsWavAndRawPcm() {
        byte[] wav = create16kMonoWav(100, (short) 16384);
        float[] pcmFromWav = LocalOnnxAudioTranscriptionAdapter.parseAudioPcm(wav);
        assertEquals(100, pcmFromWav.length);
        assertEquals(0.5f, pcmFromWav[0], 0.01f);

        ByteBuffer raw = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) raw.putShort((short) 16384);
        float[] pcmFromRaw = LocalOnnxAudioTranscriptionAdapter.parseAudioPcm(raw.array());
        assertEquals(100, pcmFromRaw.length);
        assertEquals(0.5f, pcmFromRaw[0], 0.01f);
    }

    @Test
    void parseAudioPcmRejectsInvalidFormat() {
        ByteBuffer bb = ByteBuffer.allocate(44 + 200).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[] {'R', 'I', 'F', 'F'});
        bb.putInt(36 + 200);
        bb.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) 2); // stereo (rejected)
        bb.putInt(16000);
        bb.putInt(64000);
        bb.putShort((short) 4);
        bb.putShort((short) 16);
        bb.put(new byte[] {'d', 'a', 't', 'a'});
        bb.putInt(200);
        assertThrows(
                IllegalArgumentException.class, () -> LocalOnnxAudioTranscriptionAdapter.parseAudioPcm(bb.array()));

        assertThrows(
                IllegalArgumentException.class, () -> LocalOnnxAudioTranscriptionAdapter.parseAudioPcm(new byte[3]));
    }

    @Test
    void transcribeRejectsEmptyPayload() {
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));

        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-empty", "audio/wav", new byte[0], "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("empty_payload", result.failureReason());
    }

    @Test
    void transcribeEnforcesTimeoutBudgetWithoutExceedingDeadline() {
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class),
                Duration.ofNanos(1),
                26214400L);

        byte[] wav = create16kMonoWav(16000, (short) 100);
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-timeout", "audio/wav", wav, "en", Duration.ofNanos(1));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, result.status());
        assertEquals("timeout", result.failureReason());
    }

    @Test
    void transcribeHandlesNullTranscriptGracefully() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any())).thenAnswer(invocation -> {
            Callable<String> task = invocation.getArgument(1);
            return CompletableFuture.completedFuture(task.call());
        });

        LeaseBackedWhisperRunner runner = mock(LeaseBackedWhisperRunner.class);
        when(runner.transcribe(any(), any(), any(), any())).thenReturn(null);

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class), scheduler, runner, mock(OrtEnvironment.class));

        byte[] wav = create16kMonoWav(16000, (short) 100);
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req-null", "audio/wav", wav, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("empty_transcript", result.failureReason());
    }

    @Test
    void transcribeReadsTempFileWithinBudget(@TempDir Path tempDir) throws Exception {
        Path tempAudio = tempDir.resolve("audio.wav");
        byte[] wav = create16kMonoWav(1600, (short) 100);
        Files.write(tempAudio, wav);

        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any())).thenAnswer(invocation -> {
            Callable<String> task = invocation.getArgument(1);
            return CompletableFuture.completedFuture(task.call());
        });

        LeaseBackedWhisperRunner runner = mock(LeaseBackedWhisperRunner.class);
        when(runner.transcribe(any(), any(), any(), any())).thenReturn("test transcript");

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                runner,
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                100_000L);

        AudioTranscriptionRequest request = AudioTranscriptionRequest.ofTempFile(
                "req-temp", "audio/wav", wav.length, tempAudio, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("test transcript", result.transcript());
    }
}
