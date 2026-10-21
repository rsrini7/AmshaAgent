package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.common.config.ConfigKeys;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.vertx.core.Context;

/**
 * Local ONNX audio transcription adapter implementing {@link AudioTranscriptionPort}.
 * Dispatches bounded requests through {@link OnnxInferenceScheduler} and runs inference
 * via {@link LeaseBackedWhisperRunner} with strict deadline and lease cancellation lifecycle.
 */
@Unremovable
@ApplicationScoped
public class LocalOnnxAudioTranscriptionAdapter implements AudioTranscriptionPort {

    public static final int DEFAULT_PRIORITY = 100;
    private static final Logger log = Logger.getLogger(LocalOnnxAudioTranscriptionAdapter.class);

    private static final long DEFAULT_MAX_AUDIO_BYTES = 25 * 1024 * 1024L;
    private static final int MAX_DURATION_SECONDS = 30;
    private static final int SAMPLE_RATE = 16000;
    private static final int DEFAULT_MAX_TRANSCRIPT_CHARS = 12000;

    private final OnnxModelBundleRegistry bundleRegistry;
    private final OnnxRuntimeManager runtimeManager;
    private final OnnxSessionLeaseManager leaseManager;
    private final OnnxInferenceScheduler scheduler;
    private final ObjectMapper objectMapper;
    private final OrtEnvironment environment;

    private final boolean enabled;
    private final boolean onnxEnabled;
    private final String bundleId;
    private final Duration defaultTimeout;
    private final long maxAudioBytes;
    private final int maxTranscriptChars;
    private final String ffmpegPath;

    private final Object initLock = new Object();
    private final AtomicReference<LeaseBackedWhisperRunner> runner = new AtomicReference<>();

    @Inject
    public LocalOnnxAudioTranscriptionAdapter(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.stt.local.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "amsha.onnx.enabled", defaultValue = "false") boolean onnxEnabled,
            @ConfigProperty(name = "amsha.stt.local.bundle-id", defaultValue = "whisper-tiny-en-v1") String bundleId,
            @ConfigProperty(name = "amsha.stt.local.timeout", defaultValue = "PT30S") Duration defaultTimeout,
            @ConfigProperty(name = "amsha.stt.local.max-audio-bytes", defaultValue = "26214400") long maxAudioBytes,
            @ConfigProperty(name = "amsha.stt.local.max-transcript-chars", defaultValue = "12000")
                    int maxTranscriptChars,
            @ConfigProperty(name = ConfigKeys.STT_FFMPEG_PATH_PROPERTY, defaultValue = "ffmpeg") String ffmpegPath) {
        this.bundleRegistry = Objects.requireNonNull(bundleRegistry, "bundleRegistry");
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.environment = null;
        this.enabled = enabled;
        this.onnxEnabled = onnxEnabled;
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(30);
        this.maxAudioBytes = maxAudioBytes > 0 ? maxAudioBytes : DEFAULT_MAX_AUDIO_BYTES;
        this.maxTranscriptChars = maxTranscriptChars > 0 ? maxTranscriptChars : DEFAULT_MAX_TRANSCRIPT_CHARS;
        this.ffmpegPath = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
    }

    LocalOnnxAudioTranscriptionAdapter(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            ObjectMapper objectMapper,
            boolean enabled,
            boolean onnxEnabled,
            String bundleId,
            Duration defaultTimeout,
            long maxAudioBytes,
            int maxTranscriptChars) {
        this(
                bundleRegistry,
                runtimeManager,
                leaseManager,
                scheduler,
                objectMapper,
                enabled,
                onnxEnabled,
                bundleId,
                defaultTimeout,
                maxAudioBytes,
                maxTranscriptChars,
                "ffmpeg");
    }

    public LocalOnnxAudioTranscriptionAdapter(
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            LeaseBackedWhisperRunner runner,
            OrtEnvironment environment,
            Duration defaultTimeout,
            long maxAudioBytes) {
        this(
                leaseManager,
                scheduler,
                runner,
                environment,
                defaultTimeout,
                maxAudioBytes,
                DEFAULT_MAX_TRANSCRIPT_CHARS,
                "ffmpeg");
    }

    LocalOnnxAudioTranscriptionAdapter(
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            LeaseBackedWhisperRunner runner,
            OrtEnvironment environment,
            Duration defaultTimeout,
            long maxAudioBytes,
            int maxTranscriptChars) {
        this(leaseManager, scheduler, runner, environment, defaultTimeout, maxAudioBytes, maxTranscriptChars, "ffmpeg");
    }

    LocalOnnxAudioTranscriptionAdapter(
            OnnxSessionLeaseManager leaseManager,
            OnnxInferenceScheduler scheduler,
            LeaseBackedWhisperRunner runner,
            OrtEnvironment environment,
            Duration defaultTimeout,
            long maxAudioBytes,
            int maxTranscriptChars,
            String ffmpegPath) {
        this.bundleRegistry = null;
        this.runtimeManager = null;
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.objectMapper = null;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.runner.set(Objects.requireNonNull(runner, "runner"));
        this.enabled = true;
        this.onnxEnabled = true;
        this.bundleId = "whisper-tiny-en-v1";
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(30);
        this.maxAudioBytes = maxAudioBytes > 0 ? maxAudioBytes : DEFAULT_MAX_AUDIO_BYTES;
        this.maxTranscriptChars = maxTranscriptChars > 0 ? maxTranscriptChars : DEFAULT_MAX_TRANSCRIPT_CHARS;
        this.ffmpegPath = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
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

    public boolean isAvailable() {
        return enabled && onnxEnabled && (runtimeManager == null || runtimeManager.isReady());
    }

    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public boolean supports(String mimeType, long declaredBytes) {
        if (!isAvailable()) {
            return false;
        }
        if (mimeType == null || !LocalAudioTranscoder.isSupportedAudioMime(mimeType)) {
            return false;
        }
        return declaredBytes >= 0 && declaredBytes <= maxAudioBytes;
    }

    @Override
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long started = System.nanoTime();

        if (!isAvailable()) {
            return failure(AudioTranscriptionResult.Status.UNSUPPORTED, "local_stt_disabled_or_unavailable", started);
        }
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException(
                    "Local ONNX audio transcription must not block a Quarkus event-loop thread");
        }
        if (!supports(request.mimeType(), request.declaredBytes())) {
            return failure(AudioTranscriptionResult.Status.UNSUPPORTED, "unsupported_audio", started);
        }

        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        Instant deadline = Instant.now().plus(timeout);

        byte[] audioBytes = null;
        float[] pcm = null;
        try {
            if (!Instant.now().isBefore(deadline)) {
                return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
            }
            try {
                audioBytes = resolveAudioBytes(request);
            } catch (Exception e) {
                log.debugf("Failed to read audio payload: %s", LogUtil.rootMessage(e));
                return failure(AudioTranscriptionResult.Status.FAILED, "payload_read_failed", started);
            }

            if (audioBytes == null || audioBytes.length == 0) {
                return failure(AudioTranscriptionResult.Status.FAILED, "empty_payload", started);
            }

            if (!Instant.now().isBefore(deadline)) {
                return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
            }

            try {
                Duration remaining = Duration.between(Instant.now(), deadline);
                pcm = LocalAudioTranscoder.toPcm16kMono(audioBytes, request.mimeType(), ffmpegPath, remaining);
            } catch (AudioTranscodingException e) {
                log.warnf("Local audio transcoding failed: %s", LogUtil.rootMessage(e));
                return failure(AudioTranscriptionResult.Status.FAILED, "transcode_failed", started);
            } catch (IllegalArgumentException e) {
                log.debugf("Invalid audio format: %s", LogUtil.rootMessage(e));
                return failure(AudioTranscriptionResult.Status.FAILED, "invalid_audio_format", started);
            }
            if (pcm.length > SAMPLE_RATE * MAX_DURATION_SECONDS) {
                return failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_duration_exceeded", started);
            }

            if (!Instant.now().isBefore(deadline)) {
                return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
            }

            LeaseBackedWhisperRunner whisperRunner;
            try {
                whisperRunner = resolveRunner();
            } catch (Exception e) {
                log.warnf("Failed to initialize Whisper model runner for %s: %s", bundleId, LogUtil.rootMessage(e));
                return failure(AudioTranscriptionResult.Status.FAILED, "model_init_failed", started);
            }

            OrtEnvironment env = resolveEnvironment();
            if (env == null) {
                return failure(AudioTranscriptionResult.Status.FAILED, "onnx_env_unavailable", started);
            }

            if (!Instant.now().isBefore(deadline)) {
                return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
            }

            return executeInference(env, whisperRunner, pcm, request.languageHint(), deadline, started);
        } finally {
            if (audioBytes != null) Arrays.fill(audioBytes, (byte) 0);
            if (pcm != null) Arrays.fill(pcm, 0.0f);
        }
    }

    private AudioTranscriptionResult executeInference(
            OrtEnvironment env,
            LeaseBackedWhisperRunner whisperRunner,
            float[] pcm,
            String languageHint,
            Instant deadline,
            long started) {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) {
            return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
        }
        CompletableFuture<String> future = null;
        try {
            future = scheduler.submit(deadline, () -> whisperRunner.transcribe(env, pcm, deadline, languageHint));
            String transcript = future.get(remainingMillis, TimeUnit.MILLISECONDS);
            if (transcript == null) {
                return failure(AudioTranscriptionResult.Status.FAILED, "empty_transcript", started);
            }
            if (transcript.length() > maxTranscriptChars) {
                int end = maxTranscriptChars;
                if (Character.isHighSurrogate(transcript.charAt(end - 1))) {
                    end--;
                }
                transcript = transcript.substring(0, end);
            }
            String lang = languageHint != null && !languageHint.isBlank() ? languageHint : "en";
            return AudioTranscriptionResult.transcribed(
                    transcript, lang, Duration.ofNanos(System.nanoTime() - started));
        } catch (TimeoutException _) {
            cancelFuture(future);
            return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
        } catch (InterruptedException _) {
            cancelFuture(future);
            Thread.currentThread().interrupt();
            return failure(AudioTranscriptionResult.Status.FAILED, "interrupted", started);
        } catch (CancellationException _) {
            cancelFuture(future);
            return failure(AudioTranscriptionResult.Status.FAILED, "cancelled", started);
        } catch (ExecutionException e) {
            cancelFuture(future);
            if (isTerminationOrTimeout(e.getCause())) {
                return failure(AudioTranscriptionResult.Status.TIMEOUT, "timeout", started);
            }
            log.warnf("Inference execution failed: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "inference_failed", started);
        } catch (OnnxInferenceSchedulingException _) {
            return failure(AudioTranscriptionResult.Status.FAILED, "overload_rejected", started);
        } catch (RuntimeException e) {
            cancelFuture(future);
            log.warnf("Unexpected inference failure: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "inference_failed", started);
        }
    }

    private static void cancelFuture(CompletableFuture<?> future) {
        if (future != null) future.cancel(true);
    }

    private static AudioTranscriptionResult failure(
            AudioTranscriptionResult.Status status, String reason, long started) {
        return AudioTranscriptionResult.failure(status, reason, Duration.ofNanos(System.nanoTime() - started));
    }

    private OrtEnvironment resolveEnvironment() {
        if (this.environment != null) {
            return this.environment;
        }
        if (this.runtimeManager != null) {
            return this.runtimeManager.environment().orElse(null);
        }
        return null;
    }

    private LeaseBackedWhisperRunner resolveRunner() {
        LeaseBackedWhisperRunner current = this.runner.get();
        if (current != null) {
            return current;
        }
        synchronized (initLock) {
            current = this.runner.get();
            if (current != null) {
                return current;
            }
            if (bundleRegistry == null) {
                throw new IllegalStateException("bundleRegistry is not available");
            }
            VerifiedOnnxModelBundle encoderBundle;
            try {
                encoderBundle = bundleRegistry.loadVerified(bundleId);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to load verified bundle '" + bundleId
                                + "'. Install it using: ./amsha models install whisper",
                        e);
            }
            OnnxAudioTranscriptionBundleSpec spec = encoderBundle.manifest().audioTranscription();
            if (spec == null) {
                throw new IllegalStateException(
                        "Model bundle " + bundleId + " does not declare an audio transcription contract");
            }
            Path decoderModel = encoderBundle.decoderPath();
            if (decoderModel == null) {
                throw new IllegalStateException("Verified bundle is missing decoder model path: " + bundleId);
            }
            VerifiedOnnxModelBundle decoderBundle = spec.createDecoderBundle(encoderBundle, decoderModel);
            OnnxAudioTranscriptionBundleSpec.WhisperConfigData cfg =
                    spec.parseWhisperConfig(encoderBundle.configPath(), objectMapper);
            LeaseBackedWhisperRunner created = new LeaseBackedWhisperRunner(
                    leaseManager,
                    encoderBundle,
                    decoderBundle,
                    spec,
                    cfg.forcedPrefix(),
                    cfg.suppressTokens(),
                    cfg.beginSuppressTokens(),
                    cfg.eosTokenId());
            this.runner.set(created);
            return created;
        }
    }

    private byte[] resolveAudioBytes(AudioTranscriptionRequest request) throws IOException {
        byte[] inline = request.inlineData();
        if (inline != null) {
            if (inline.length > maxAudioBytes) {
                throw new IllegalArgumentException("Payload size exceeds maximum permitted: " + inline.length);
            }
            return inline;
        }
        if (request.tempFilePath() != null) {
            Path path = request.tempFilePath();
            if (Files.size(path) > maxAudioBytes) {
                throw new IllegalArgumentException("Audio file size exceeds maximum permitted: " + Files.size(path));
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > maxAudioBytes) {
                throw new IllegalArgumentException("Audio file size exceeds maximum permitted: " + bytes.length);
            }
            return bytes;
        }
        return null;
    }

    private static boolean isTerminationOrTimeout(Throwable cause) {
        if (cause == null) return false;
        if (cause instanceof TimeoutException) return true;
        if (cause instanceof OrtException
                && cause.getMessage() != null
                && (cause.getMessage().contains("terminate")
                        || cause.getMessage().contains("ORT_FAIL"))) {
            return true;
        }
        return isTerminationOrTimeout(cause.getCause());
    }

    /** Parses WAV or raw 16-bit PCM to normalized [-1.0, 1.0] float array. */
    public static float[] parseAudioPcm(byte[] bytes) {
        return LocalAudioPcmParser.parseAudioPcm(bytes);
    }
}
