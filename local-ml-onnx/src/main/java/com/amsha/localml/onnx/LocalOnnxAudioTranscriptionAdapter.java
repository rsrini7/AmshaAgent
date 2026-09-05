package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;

/**
 * Local ONNX audio transcription adapter implementing {@link AudioTranscriptionPort}.
 * Dispatches bounded requests through {@link OnnxInferenceScheduler} and runs inference
 * via {@link LeaseBackedWhisperRunner} with strict deadline and lease cancellation lifecycle.
 * <p>
 * <b>Note</b>: This class exists as unmanaged implementation scaffolding in {@code src/main/java};
 * it is NOT CDI-wired (&#64;ApplicationScoped omitted), configured, or exposed at runtime pending
 * explicit human authorization.
 */
public class LocalOnnxAudioTranscriptionAdapter implements AudioTranscriptionPort {

    private static final long DEFAULT_MAX_AUDIO_BYTES = 25 * 1024 * 1024L;
    private static final int MAX_DURATION_SECONDS = 30;
    private static final int SAMPLE_RATE = 16000;

    private final OnnxSessionLeaseManager leaseManager;
    private final OnnxInferenceScheduler scheduler;
    private final LeaseBackedWhisperRunner runner;
    private final OrtEnvironment environment;
    private final Duration defaultTimeout;
    private final long maxAudioBytes;

    public LocalOnnxAudioTranscriptionAdapter(
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            LeaseBackedWhisperRunner runner,
            OrtEnvironment environment,
            Duration defaultTimeout,
            long maxAudioBytes) {
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(30);
        this.maxAudioBytes = maxAudioBytes > 0 ? maxAudioBytes : DEFAULT_MAX_AUDIO_BYTES;
    }

    public LocalOnnxAudioTranscriptionAdapter(
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            LeaseBackedWhisperRunner runner,
            OrtEnvironment environment) {
        this(leaseManager, scheduler, runner, environment, Duration.ofSeconds(30), DEFAULT_MAX_AUDIO_BYTES);
    }

    OnnxSessionLeaseManager sessionLeaseManager() {
        return leaseManager;
    }

    @Override
    public boolean supports(String mimeType, long declaredBytes) {
        if (mimeType == null || !isSupportedAudioMime(mimeType)) {
            return false;
        }
        return declaredBytes >= 0 && declaredBytes <= maxAudioBytes;
    }

    @Override
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long started = System.nanoTime();

        if (!supports(request.mimeType(), request.declaredBytes())) {
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.UNSUPPORTED,
                    "unsupported_audio",
                    Duration.ofNanos(System.nanoTime() - started));
        }

        byte[] audioBytes;
        try {
            audioBytes = resolveAudioBytes(request);
        } catch (IOException e) {
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED,
                    "payload_read_failed",
                    Duration.ofNanos(System.nanoTime() - started));
        }

        if (audioBytes == null || audioBytes.length == 0) {
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED,
                    "empty_payload",
                    Duration.ofNanos(System.nanoTime() - started));
        }

        float[] pcm;
        try {
            pcm = parseAudioPcm(audioBytes);
        } catch (IllegalArgumentException e) {
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED,
                    "invalid_audio_format",
                    Duration.ofNanos(System.nanoTime() - started));
        }

        if (pcm.length > SAMPLE_RATE * MAX_DURATION_SECONDS) {
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.TOO_LARGE,
                    "audio_duration_exceeded",
                    Duration.ofNanos(System.nanoTime() - started));
        }

        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        Instant deadline = Instant.now().plus(timeout);
        CompletableFuture<String> future = null;

        try {
            future = scheduler.submit(deadline, () -> runner.transcribe(environment, pcm, deadline));
            String transcript = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return AudioTranscriptionResult.transcribed(
                    transcript, "en", Duration.ofNanos(System.nanoTime() - started));
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.TIMEOUT, "timeout", Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException | CancellationException e) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED, "cancelled", Duration.ofNanos(System.nanoTime() - started));
        } catch (ExecutionException e) {
            if (future != null) future.cancel(true);
            Throwable cause = e.getCause();
            if (isTerminationOrTimeout(cause)) {
                return AudioTranscriptionResult.failure(
                        AudioTranscriptionResult.Status.TIMEOUT,
                        "timeout",
                        Duration.ofNanos(System.nanoTime() - started));
            }
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED,
                    "inference_failed",
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (OnnxInferenceSchedulingException e) {
            return AudioTranscriptionResult.failure(
                    AudioTranscriptionResult.Status.FAILED,
                    "overload_rejected",
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private static byte[] resolveAudioBytes(AudioTranscriptionRequest request) throws IOException {
        if (request.inlineData() != null) {
            return request.inlineData();
        }
        if (request.tempFilePath() != null) {
            return Files.readAllBytes(request.tempFilePath());
        }
        return new byte[0];
    }

    private static boolean isTerminationOrTimeout(Throwable cause) {
        if (cause == null) return false;
        if (cause instanceof TimeoutException) return true;
        if (cause instanceof OrtException) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("terminate") || msg.contains("ORT_FAIL"))) {
                return true;
            }
        }
        return isTerminationOrTimeout(cause.getCause());
    }

    private static boolean isSupportedAudioMime(String mime) {
        return "audio/wav".equalsIgnoreCase(mime)
                || "audio/x-wav".equalsIgnoreCase(mime)
                || "audio/wave".equalsIgnoreCase(mime)
                || "audio/pcm".equalsIgnoreCase(mime);
    }

    /** Parses WAV or raw 16-bit PCM to normalized [-1.0, 1.0] float array. */
    public static float[] parseAudioPcm(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 44
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E') {
            offset = 44;
        }
        int remaining = bytes.length - offset;
        if (remaining % 2 != 0 || remaining <= 0) {
            throw new IllegalArgumentException("Audio byte count is invalid for 16-bit PCM");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int numSamples = remaining / 2;
        float[] pcm = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            short sample = bb.getShort(offset + i * 2);
            pcm[i] = sample / 32768.0f;
        }
        return pcm;
    }
}
