package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Branch-level coverage for {@link LocalOnnxAudioTranscriptionAdapter}: the payload size guards,
 * the deadline re-checks, the lazy runner initialisation, and the inference failure matrix.
 *
 * <p>These paths are deliberately kept out of the two behavioural suites so that the timing-sensitive
 * and reflection-driven cases stay isolated from the ordinary transcriptions.
 */
class LocalOnnxAudioTranscriptionAdapterBranchTest {

    private static final long MAX_AUDIO_BYTES = 26_214_400L;

    private static byte[] wav(int samples) {
        ByteBuffer bb = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[] {'R', 'I', 'F', 'F'});
        bb.putInt(36 + samples * 2);
        bb.put(new byte[] {'W', 'A', 'V', 'E'});
        bb.put(new byte[] {'f', 'm', 't', ' '});
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) 1);
        bb.putInt(16000);
        bb.putInt(32000);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put(new byte[] {'d', 'a', 't', 'a'});
        bb.putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            bb.putShort((short) 100);
        }
        return bb.array();
    }

    private static LocalOnnxAudioTranscriptionAdapter simpleAdapter(long maxAudioBytes) {
        return new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                maxAudioBytes);
    }

    private static LocalOnnxAudioTranscriptionAdapter injectedAdapter(
            OnnxModelBundleRegistry registry, OnnxRuntimeManager runtime) {
        return new LocalOnnxAudioTranscriptionAdapter(
                registry,
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(ObjectMapper.class),
                true,
                true,
                "whisper-tiny-en-v1",
                Duration.ofSeconds(30),
                MAX_AUDIO_BYTES,
                12000);
    }

    private static AudioTranscriptionRequest inlineRequest(byte[] payload) {
        return AudioTranscriptionRequest.ofInline("req", "audio/wav", payload, "en", Duration.ofSeconds(10));
    }

    private static AudioTranscriptionResult executeInference(
            LocalOnnxAudioTranscriptionAdapter adapter,
            OrtEnvironment env,
            LeaseBackedWhisperRunner runner,
            float[] pcm,
            String languageHint,
            Instant deadline)
            throws Exception {
        Method m = LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod(
                "executeInference",
                OrtEnvironment.class,
                LeaseBackedWhisperRunner.class,
                float[].class,
                String.class,
                Instant.class,
                long.class);
        m.setAccessible(true);
        return (AudioTranscriptionResult)
                m.invoke(adapter, env, runner, pcm, languageHint, deadline, System.nanoTime());
    }

    private static boolean isTerminationOrTimeout(Throwable cause) throws Exception {
        Method m =
                LocalOnnxAudioTranscriptionAdapter.class.getDeclaredMethod("isTerminationOrTimeout", Throwable.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, cause);
    }

    @Test
    void sessionLeaseManagerExposesTheInjectedManager() {
        OnnxSessionLeaseManager leaseManager = mock(OnnxSessionLeaseManager.class);
        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                leaseManager,
                mock(OnnxInferenceScheduler.class),
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));
        assertSame(leaseManager, adapter.sessionLeaseManager());
    }

    @Test
    void transcribeRejectsAnInlinePayloadLargerThanTheConfiguredMaximum() {
        AudioTranscriptionRequest request = mock(AudioTranscriptionRequest.class);
        when(request.mimeType()).thenReturn("audio/wav");
        when(request.declaredBytes()).thenReturn(100L);
        when(request.inlineData()).thenReturn(new byte[200]);
        when(request.timeout()).thenReturn(Duration.ofSeconds(10));

        // declaredBytes fits the budget, but the delivered inline array does not.
        AudioTranscriptionResult result = simpleAdapter(150L).transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("payload_read_failed", result.failureReason());
    }

    @Test
    void transcribeRejectsATempFileWhoseActualSizeExceedsTheMaximum(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("audio.wav");
        Files.write(file, new byte[200]);

        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofTempFile("req", "audio/wav", 10L, file, "en", Duration.ofSeconds(10));
        AudioTranscriptionResult result = simpleAdapter(50L).transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("payload_read_failed", result.failureReason());
    }

    @Test
    void transcribeTimesOutWhenPayloadRetrievalOutlivesTheBudget() {
        byte[] payload = wav(1600);
        AudioTranscriptionRequest request = mock(AudioTranscriptionRequest.class);
        when(request.mimeType()).thenReturn("audio/wav");
        when(request.declaredBytes()).thenReturn((long) payload.length);
        when(request.timeout()).thenReturn(Duration.ofMillis(40));
        when(request.inlineData()).thenAnswer(invocation -> {
            Thread.sleep(120);
            return payload;
        });

        AudioTranscriptionResult result = simpleAdapter(MAX_AUDIO_BYTES).transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, result.status());
        assertEquals("timeout", result.failureReason());
    }

    @Test
    void transcribeReturnsEmptyPayloadWhenTheRequestCarriesNoBytes() {
        AudioTranscriptionRequest request = mock(AudioTranscriptionRequest.class);
        when(request.mimeType()).thenReturn("audio/wav");
        when(request.declaredBytes()).thenReturn(100L);
        when(request.timeout()).thenReturn(Duration.ofSeconds(10));
        // Neither inlineData nor tempFilePath is present, so the resolver yields nothing.
        when(request.inlineData()).thenReturn(null);
        when(request.tempFilePath()).thenReturn(null);

        AudioTranscriptionResult result = simpleAdapter(MAX_AUDIO_BYTES).transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("empty_payload", result.failureReason());
    }

    @Test
    void transcribeFailsClosedWhenTheVerifiedBundleCannotBeLoaded() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        when(registry.loadVerified("whisper-tiny-en-v1")).thenThrow(new IOException("bundle absent"));
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        AudioTranscriptionResult result = injectedAdapter(registry, runtime).transcribe(inlineRequest(wav(1600)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("model_init_failed", result.failureReason());
    }

    @Test
    void transcribeFailsClosedWhenTheBundleDeclaresNoAudioContract() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        when(registry.loadVerified("whisper-tiny-en-v1")).thenReturn(bundle);
        when(bundle.manifest()).thenReturn(manifest);
        when(manifest.audioTranscription()).thenReturn(null);
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        AudioTranscriptionResult result = injectedAdapter(registry, runtime).transcribe(inlineRequest(wav(1600)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("model_init_failed", result.failureReason());
    }

    @Test
    void transcribeFailsClosedWhenTheVerifiedBundleHasNoDecoderPath() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        when(registry.loadVerified("whisper-tiny-en-v1")).thenReturn(bundle);
        when(bundle.manifest()).thenReturn(manifest);
        when(manifest.audioTranscription()).thenReturn(mock(OnnxAudioTranscriptionBundleSpec.class));
        when(bundle.decoderPath()).thenReturn(null);
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        AudioTranscriptionResult result = injectedAdapter(registry, runtime).transcribe(inlineRequest(wav(1600)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("model_init_failed", result.failureReason());
    }

    @Test
    void transcribeReportsAMissingOnnxEnvironmentAfterLazilyBuildingTheRunner() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        OnnxAudioTranscriptionBundleSpec spec = mock(OnnxAudioTranscriptionBundleSpec.class);
        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        when(registry.loadVerified("whisper-tiny-en-v1")).thenReturn(bundle);
        when(bundle.manifest()).thenReturn(manifest);
        when(manifest.audioTranscription()).thenReturn(spec);
        when(bundle.decoderPath()).thenReturn(Path.of("decoder.onnx"));
        when(spec.createDecoderBundle(any(), any())).thenReturn(mock(VerifiedOnnxModelBundle.class));
        when(spec.parseWhisperConfig(any(), any()))
                .thenReturn(new OnnxAudioTranscriptionBundleSpec.WhisperConfigData(null, Set.of(), Set.of(), 50256));
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);
        when(runtime.environment()).thenReturn(Optional.empty());

        AudioTranscriptionResult result = injectedAdapter(registry, runtime).transcribe(inlineRequest(wav(1600)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("onnx_env_unavailable", result.failureReason());
    }

    @Test
    void transcribeTimesOutWhenTheDeadlineExpiresWhileTheRunnerIsBeingBuilt() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        OnnxAudioTranscriptionBundleSpec spec = mock(OnnxAudioTranscriptionBundleSpec.class);
        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        when(bundle.manifest()).thenReturn(manifest);
        when(manifest.audioTranscription()).thenReturn(spec);
        when(bundle.decoderPath()).thenReturn(Path.of("decoder.onnx"));
        when(spec.createDecoderBundle(any(), any())).thenReturn(mock(VerifiedOnnxModelBundle.class));
        when(spec.parseWhisperConfig(any(), any()))
                .thenReturn(new OnnxAudioTranscriptionBundleSpec.WhisperConfigData(null, Set.of(), Set.of(), 50256));
        // The lazy bundle load outlives the caller's budget, so the re-check after the environment
        // lookup is what notices the expiry rather than the earlier checks.
        when(registry.loadVerified("whisper-tiny-en-v1")).thenAnswer(invocation -> {
            Thread.sleep(120);
            return bundle;
        });
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);
        when(runtime.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));

        LocalOnnxAudioTranscriptionAdapter adapter = injectedAdapter(registry, runtime);
        AudioTranscriptionRequest request =
                AudioTranscriptionRequest.ofInline("req", "audio/wav", wav(1600), "en", Duration.ofMillis(40));
        AudioTranscriptionResult result = adapter.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, result.status());
        assertEquals("timeout", result.failureReason());
    }

    @Test
    void resolveRunnerReturnsTheRunnerPublishedByAConcurrentCaller() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        OnnxAudioTranscriptionBundleSpec spec = mock(OnnxAudioTranscriptionBundleSpec.class);
        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        when(bundle.manifest()).thenReturn(manifest);
        when(manifest.audioTranscription()).thenReturn(spec);
        when(bundle.decoderPath()).thenReturn(Path.of("decoder.onnx"));
        when(spec.createDecoderBundle(any(), any())).thenReturn(mock(VerifiedOnnxModelBundle.class));
        when(spec.parseWhisperConfig(any(), any()))
                .thenReturn(new OnnxAudioTranscriptionBundleSpec.WhisperConfigData(null, Set.of(), Set.of(), 50256));

        CountDownLatch insideLoad = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        when(registry.loadVerified("whisper-tiny-en-v1")).thenAnswer(invocation -> {
            insideLoad.countDown();
            if (!allowReturn.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("loadVerified latch timed out");
            }
            return bundle;
        });

        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);
        when(runtime.environment()).thenReturn(Optional.empty());
        LocalOnnxAudioTranscriptionAdapter adapter = injectedAdapter(registry, runtime);
        AudioTranscriptionRequest request = inlineRequest(wav(1600));

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = new Thread(
                () -> {
                    try {
                        adapter.transcribe(request);
                    } catch (Throwable t) {
                        firstFailure.set(t);
                    }
                },
                "stt-first");
        first.start();
        assertTrue(insideLoad.await(10, TimeUnit.SECONDS), "first caller should enter the runner init lock");

        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread second = new Thread(
                () -> {
                    try {
                        adapter.transcribe(request);
                    } catch (Throwable t) {
                        secondFailure.set(t);
                    }
                },
                "stt-second");
        second.start();

        // Deterministic ordering: wait until the second caller is parked on the init monitor, so it
        // must observe the already-published runner when it finally acquires the lock.
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < limit) {
            LockSupport.parkNanos(200_000L);
        }
        assertEquals(Thread.State.BLOCKED, second.getState(), "second caller should wait on the runner init lock");

        allowReturn.countDown();
        first.join(10_000);
        second.join(10_000);

        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        verify(registry, times(1)).loadVerified("whisper-tiny-en-v1");
    }

    @Test
    void executeInferenceRejectsAnAlreadyExpiredDeadline() throws Exception {
        LocalOnnxAudioTranscriptionAdapter adapter = simpleAdapter(MAX_AUDIO_BYTES);
        AudioTranscriptionResult result = executeInference(
                adapter,
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().minusSeconds(5));

        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, result.status());
        assertEquals("timeout", result.failureReason());
    }

    @Test
    void executeInferenceKeepsASurrogatePairIntactWhenTruncating() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any()))
                .thenAnswer(inv -> CompletableFuture.completedFuture("abcdefghi\uD83D\uDE00"));

        LocalOnnxAudioTranscriptionAdapter adapter = new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class),
                Duration.ofSeconds(30),
                MAX_AUDIO_BYTES,
                10);

        AudioTranscriptionResult result = executeInference(
                adapter,
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10));

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("abcdefghi", result.transcript());
    }

    @Test
    void executeInferenceMapsATerminationCauseToTimeout() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        CompletableFuture<String> future = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) throws ExecutionException {
                throw new ExecutionException(new TimeoutException("worker aborted"));
            }
        };
        when(scheduler.submit(any(), any())).thenAnswer(inv -> future);

        AudioTranscriptionResult result = executeInference(
                simpleAdapterWith(scheduler),
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10));

        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, result.status());
        assertEquals("timeout", result.failureReason());
    }

    @Test
    void executeInferenceMapsANonTerminationExecutionFailureToFailed() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        CompletableFuture<String> future = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) throws ExecutionException {
                throw new ExecutionException(new IllegalStateException("decoder exploded"));
            }
        };
        when(scheduler.submit(any(), any())).thenAnswer(inv -> future);

        AudioTranscriptionResult result = executeInference(
                simpleAdapterWith(scheduler),
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("inference_failed", result.failureReason());
    }

    @Test
    void executeInferenceMapsAnUnexpectedRuntimeFailureToFailed() throws Exception {
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        when(scheduler.submit(any(), any())).thenThrow(new RuntimeException("unexpected"));

        AudioTranscriptionResult result = executeInference(
                simpleAdapterWith(scheduler),
                mock(OrtEnvironment.class),
                mock(LeaseBackedWhisperRunner.class),
                new float[10],
                "en",
                Instant.now().plusSeconds(10));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("inference_failed", result.failureReason());
    }

    @Test
    void isTerminationOrTimeoutRecognisesTheOrtFailMarker() throws Exception {
        assertTrue(isTerminationOrTimeout(
                new OrtException(OrtException.OrtErrorCode.ORT_FAIL, "ORT_FAIL: decoder aborted")));
    }

    private static LocalOnnxAudioTranscriptionAdapter simpleAdapterWith(OnnxInferenceScheduler scheduler) {
        return new LocalOnnxAudioTranscriptionAdapter(
                mock(OnnxSessionLeaseManager.class),
                scheduler,
                mock(LeaseBackedWhisperRunner.class),
                mock(OrtEnvironment.class));
    }
}
