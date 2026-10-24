package com.amsha.llm.providers.audio;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.amsha.common.config.ConfigKeys;
import com.amsha.common.http.HttpProtocol;
import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

/** Governed, opt-in Gnani Prisma REST transcription adapter. */
@ApplicationScoped
@Unremovable
@Startup
public class GnaniAudioTranscriptionProvider implements AudioTranscriptionPort {
    private static final Logger log = Logger.getLogger(GnaniAudioTranscriptionProvider.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONSERVATIVE_MAX_DURATION = Duration.ofSeconds(30);
    private static final long DEFAULT_MAX_BYTES = MediaBudgetLimits.DEFAULT_MAX_DOCUMENT_SIZE_BYTES;
    private static final int DEFAULT_MAX_TRANSCRIPT_CHARS = 12_000;
    private static final long MAX_RESPONSE_BYTES = 128L * 1024;
    private static final String UNKNOWN_ACTOR = "unknown";
    /** Metric tag value for a successful transcription; failures always carry a categorical reason. */
    private static final String NO_FAILURE_REASON = "none";

    private final GovernedTransportService governedTransport;
    private final ObjectMapper objectMapper;
    private final AudioDurationProbe durationProbe;
    private final boolean enabled;
    private final Duration configuredTimeout;
    private final Duration configuredMaxDuration;
    private final long maxBytes;
    private final int maxTranscriptChars;
    private final String apiKey;
    private final String defaultLanguage;

    @Inject
    Instance<MeterRegistry> meterRegistry;

    @Inject
    public GnaniAudioTranscriptionProvider(
            GovernedTransportService governedTransport,
            ObjectMapper objectMapper,
            @ConfigProperty(name = ConfigKeys.GNANI_STT_ENABLED_PROPERTY, defaultValue = "false") boolean enabled,
            @ConfigProperty(
                            name = ConfigKeys.GNANI_STT_DEFAULT_LANGUAGE_PROPERTY,
                            defaultValue = GnaniApiContract.DEFAULT_STT_LANGUAGE)
                    Optional<String> defaultLanguage,
            @ConfigProperty(name = ConfigKeys.GNANI_STT_MAX_DURATION_PROPERTY, defaultValue = "PT30S")
                    Duration configuredMaxDuration,
            @ConfigProperty(name = ConfigKeys.GNANI_API_KEY_PROPERTY) Optional<String> apiKey,
            @ConfigProperty(name = ConfigKeys.STT_TIMEOUT_PROPERTY, defaultValue = "PT60S") Duration configuredTimeout,
            @ConfigProperty(name = ConfigKeys.STT_MAX_BYTES_PROPERTY, defaultValue = "26214400") long maxBytes,
            @ConfigProperty(name = ConfigKeys.STT_MAX_TRANSCRIPT_CHARS_PROPERTY, defaultValue = "12000")
                    int maxTranscriptChars,
            @ConfigProperty(name = ConfigKeys.STT_FFMPEG_PATH_PROPERTY, defaultValue = "ffmpeg") String ffmpegPath) {
        this.governedTransport = governedTransport;
        this.objectMapper = objectMapper;
        this.durationProbe = new AudioDurationProbe(ffmpegPath);
        this.enabled = enabled;
        this.configuredMaxDuration = configuredMaxDuration;
        this.configuredTimeout = configuredTimeout;
        this.maxBytes = maxBytes;
        this.maxTranscriptChars = maxTranscriptChars;
        this.apiKey = apiKey.orElse("").trim();
        if (!this.apiKey.isBlank() && !GnaniApiContract.isValidApiKeyHeaderValue(this.apiKey)) {
            throw new IllegalArgumentException("AMSHA_GNANI_API_KEY must be a single-line printable ASCII value");
        }
        if (enabled && this.apiKey.isBlank()) {
            throw new IllegalStateException("AMSHA_GNANI_API_KEY is required when Gnani STT is enabled");
        }
        String requestedDefaultLanguage =
                defaultLanguage.filter(language -> !language.isBlank()).orElse(GnaniApiContract.DEFAULT_STT_LANGUAGE);
        this.defaultLanguage = GnaniApiContract.normalizeSttLanguage(requestedDefaultLanguage);
        if (enabled && this.defaultLanguage == null) {
            throw new IllegalStateException(ConfigKeys.GNANI_STT_DEFAULT_LANGUAGE_PROPERTY
                    + " must name a supported Gnani language when STT is enabled");
        }
    }

    @Override
    public boolean supports(String mimeType, long declaredBytes) {
        // Keep oversized requests resolvable so transcribe() can return terminal TOO_LARGE.
        return enabled && !apiKey.isBlank() && declaredBytes >= 0 && GnaniApiContract.isSupportedAudioMime(mimeType);
    }

    @Override
    public int priority() {
        return GnaniApiContract.STT_PRIORITY;
    }

    @Override
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }
        long started = System.nanoTime();
        AudioTranscriptionResult result = transcribeBounded(request, started);
        logIfFailed(result);
        record(result, elapsed(started));
        return result;
    }

    private AudioTranscriptionResult transcribeBounded(AudioTranscriptionRequest request, long started) {
        if (!enabled) {
            return failure(AudioTranscriptionResult.Status.UNSUPPORTED, "disabled", started);
        }
        if (apiKey.isBlank()) {
            return failure(AudioTranscriptionResult.Status.UNSUPPORTED, "credentials_missing", started);
        }
        if (!GnaniApiContract.isSupportedAudioMime(request.mimeType())) {
            return failure(AudioTranscriptionResult.Status.UNSUPPORTED, "unsupported_audio_format", started);
        }
        long maxBytes = effectiveMaxBytes();
        if (request.declaredBytes() > maxBytes) {
            return failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_too_large", started);
        }
        String language = resolveLanguage(request.languageHint());
        if (language == null) {
            return failure(AudioTranscriptionResult.Status.UNSUPPORTED, "language_required_or_unsupported", started);
        }
        Duration maxDuration = effectiveMaxDuration();
        if (request.declaredDuration() != null && request.declaredDuration().compareTo(maxDuration) > 0) {
            return failure(AudioTranscriptionResult.Status.TOO_LARGE, "declared_duration_exceeded", started);
        }
        return sendMeasured(request, language, maxDuration, maxBytes, started);
    }

    private AudioTranscriptionResult sendMeasured(
            AudioTranscriptionRequest request, String language, Duration maxDuration, long maxBytes, long started) {
        BoundedResponseSubscriber.BoundedResponseHandler responseHandler =
                BoundedResponseSubscriber.handler(MAX_RESPONSE_BYTES);
        try {
            byte[] input = readBounded(request, maxBytes);
            AudioDurationProbe.ProbeResult measured = durationProbe.measureAndNormalize(
                    input, request.mimeType(), maxDuration, effectiveTimeout(request.timeout()));
            if (measured.measuredDuration().compareTo(maxDuration) > 0) {
                return failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_duration_exceeded", started);
            }
            HttpRequest httpRequest = buildRequest(measured, language, request.timeout());
            HttpResponse<byte[]> response = governedTransport.send(
                    GnaniApiContract.STT_ENDPOINT,
                    OutboundPurpose.PROVIDER_API_CALL,
                    null,
                    actorUserId(),
                    false,
                    httpRequest,
                    responseHandler);
            return parseResponse(response, language, started);
        } catch (TimeoutException e) {
            return failure(AudioTranscriptionResult.Status.TIMEOUT, "duration_measure_timeout", started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(AudioTranscriptionResult.Status.TIMEOUT, "duration_measure_interrupted", started);
        } catch (OutboundHttpException e) {
            if (responseHandler.exceeded()) {
                // An oversized *response* is not the caller's audio being too large: the measured
                // duration and byte cap already passed. Reporting TOO_LARGE here would tell the user
                // their clip exceeds a limit it did not exceed, so this stays FAILED like the
                // category arm below.
                return failure(AudioTranscriptionResult.Status.FAILED, "provider_response_too_large", started);
            }
            // Keep the category fallback for transports that report overflow without the handler flag.
            return mapGovernedFailure(e, started);
        } catch (AudioTooLargeException e) {
            return failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_too_large", started);
        } catch (IOException e) {
            log.debugf("[STT] Gnani audio processing failed: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "processing_error", started);
        } catch (IllegalArgumentException _) {
            log.debug("[STT] Gnani request configuration is invalid");
            return failure(AudioTranscriptionResult.Status.FAILED, "invalid_request_configuration", started);
        } catch (RuntimeException e) {
            log.debugf("[STT] Gnani transcription failed: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "provider_error", started);
        }
    }

    /**
     * Maps a governed-transport failure onto a categorical result.
     *
     * <p>Each arm keeps the cause distinct rather than collapsing everything into {@code provider_error}:
     * a policy denial, a DNS failure, and a transport I/O failure all happen before Gnani sees the
     * request, so reporting them as provider faults would send an operator looking in the wrong place.
     */
    private AudioTranscriptionResult mapGovernedFailure(OutboundHttpException e, long started) {
        return switch (e.category()) {
            case TIMEOUT -> failure(AudioTranscriptionResult.Status.TIMEOUT, "provider_timeout", started);
            // A provider-side overflow is not the caller's audio being too large, so this stays FAILED.
            case OVERSIZED_PAYLOAD ->
                failure(AudioTranscriptionResult.Status.FAILED, "provider_response_too_large", started);
            case HITL_REQUIRED -> failure(AudioTranscriptionResult.Status.FAILED, "hitl_required", started);
            case POLICY_DENIED, DNS_REBINDING, REDIRECT_DENIED, REDIRECT_LIMIT, TLS_TRUST ->
                failure(AudioTranscriptionResult.Status.FAILED, "policy_denied", started);
            case DNS_FAILURE -> failure(AudioTranscriptionResult.Status.FAILED, "transport_dns_failure", started);
            case IO -> failure(AudioTranscriptionResult.Status.FAILED, "transport_io", started);
            default -> failure(AudioTranscriptionResult.Status.FAILED, "provider_error", started);
        };
    }

    private byte[] readBounded(AudioTranscriptionRequest request, long maxBytes) throws IOException {
        byte[] inlineData = request.inlineData();
        if (inlineData != null) {
            if (inlineData.length > maxBytes) {
                throw new AudioTooLargeException("inline audio exceeds configured byte bound");
            }
            return inlineData;
        }
        if (request.tempFilePath() == null) {
            throw new IOException("audio source is missing");
        }
        long fileSize = Files.size(request.tempFilePath());
        if (fileSize > maxBytes || fileSize > Integer.MAX_VALUE) {
            throw new AudioTooLargeException("temporary audio exceeds configured byte bound");
        }
        byte[] bytes = Files.readAllBytes(request.tempFilePath());
        if (request.declaredBytes() > 0 && bytes.length != request.declaredBytes()) {
            throw new IOException("temporary audio size does not match declared size");
        }
        return bytes;
    }

    private HttpRequest buildRequest(
            AudioDurationProbe.ProbeResult measured, String language, Duration requestedTimeout) throws IOException {
        String boundary = "AmshaGnaniBoundary" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, measured, language);
        return HttpRequest.newBuilder(URI.create(GnaniApiContract.STT_ENDPOINT))
                .timeout(effectiveTimeout(requestedTimeout))
                .header(HttpProtocol.HEADER_CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .header(HttpProtocol.HEADER_ACCEPT, HttpProtocol.MEDIA_TYPE_JSON)
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/rsrini7/AmshaAgent)")
                .header(GnaniApiContract.API_KEY_HEADER, apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private static byte[] multipartBody(String boundary, AudioDurationProbe.ProbeResult measured, String language)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(measured.audioBytes().length + 512);
        writeFilePart(output, boundary, measured);
        writeField(output, boundary, "language_code", language);
        writeField(output, boundary, "format", "verbatim");
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static void writeFilePart(
            ByteArrayOutputStream output, String boundary, AudioDurationProbe.ProbeResult measured) throws IOException {
        output.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"audio_file\"; filename=\"audio.wav\"\r\n"
                        + "Content-Type: " + measured.mimeType() + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(measured.audioBytes());
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeField(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                        + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private AudioTranscriptionResult parseResponse(HttpResponse<byte[]> response, String language, long started)
            throws IOException {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response == null ? 0 : response.statusCode();
            String reason = status == 400
                    ? "provider_invalid_request"
                    : status == 429 ? "provider_rate_limited" : "provider_http_error";
            return failure(AudioTranscriptionResult.Status.FAILED, reason, started);
        }
        byte[] responseBytes = response.body();
        if (responseBytes == null || responseBytes.length == 0) {
            return failure(AudioTranscriptionResult.Status.FAILED, "empty_provider_response", started);
        }
        JsonNode root = objectMapper.readTree(responseBytes);
        String transcript = root.path("transcript").asText("").strip();
        if (transcript.isBlank()) {
            return failure(AudioTranscriptionResult.Status.FAILED, "empty_transcript", started);
        }
        int maxTranscriptChars = effectiveMaxTranscriptChars();
        if (transcript.length() > maxTranscriptChars) {
            transcript = transcript.substring(0, maxTranscriptChars).strip();
        }
        return AudioTranscriptionResult.transcribed(transcript, language, elapsed(started));
    }

    private String resolveLanguage(String requestLanguage) {
        if (requestLanguage != null && !requestLanguage.isBlank()) {
            return GnaniApiContract.normalizeSttLanguage(requestLanguage);
        }
        return defaultLanguage;
    }

    private Duration effectiveTimeout(Duration requested) {
        Duration configured = configuredTimeout == null || configuredTimeout.isNegative() || configuredTimeout.isZero()
                ? DEFAULT_TIMEOUT
                : configuredTimeout;
        return requested != null
                        && !requested.isNegative()
                        && !requested.isZero()
                        && requested.compareTo(configured) < 0
                ? requested
                : configured;
    }

    private Duration effectiveMaxDuration() {
        if (configuredMaxDuration == null || configuredMaxDuration.isNegative() || configuredMaxDuration.isZero()) {
            return CONSERVATIVE_MAX_DURATION;
        }
        return configuredMaxDuration.compareTo(CONSERVATIVE_MAX_DURATION) > 0
                ? CONSERVATIVE_MAX_DURATION
                : configuredMaxDuration;
    }

    private long effectiveMaxBytes() {
        return maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
    }

    private int effectiveMaxTranscriptChars() {
        return maxTranscriptChars > 0 ? maxTranscriptChars : DEFAULT_MAX_TRANSCRIPT_CHARS;
    }

    private String actorUserId() {
        String userId = WorkspaceContext.getUserIdOrUnknown();
        return UNKNOWN_ACTOR.equals(userId) ? null : userId;
    }

    private AudioTranscriptionResult failure(AudioTranscriptionResult.Status status, String reason, long started) {
        return AudioTranscriptionResult.failure(status, reason, elapsed(started));
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private void record(AudioTranscriptionResult result, Duration duration) {
        if (result == null || meterRegistry == null || !meterRegistry.isResolvable()) {
            return;
        }
        try {
            MeterRegistry registry = meterRegistry.get();
            String status = result.status().name().toLowerCase(Locale.ROOT);
            String reason = reasonTag(result);
            registry.counter("amsha.stt.transcription.total", "provider", "gnani", "status", status, "reason", reason)
                    .increment();
            registry.timer("amsha.stt.transcription.duration", "provider", "gnani", "status", status, "reason", reason)
                    .record(duration);
        } catch (RuntimeException e) {
            log.debugf("[STT] Gnani metrics unavailable: %s", LogUtil.rootMessage(e));
        }
    }

    /**
     * Returns the metric tag for the outcome.
     *
     * <p>{@link AudioTranscriptionResult} rejects a non-transcribed result without a categorical
     * {@code [a-z0-9_]} reason, so this tag is bounded by construction and never carries transcript,
     * audio, or user text; a successful transcription simply has no reason to report.
     */
    private static String reasonTag(AudioTranscriptionResult result) {
        return result.failureReason() == null ? NO_FAILURE_REASON : result.failureReason();
    }

    /**
     * Surfaces the categorical failure reason.
     *
     * <p>Deliberately independent of the meter registry: an unresolvable registry must not also
     * silence the reason, which is the only diagnostic an operator has when transcription fails.
     * Emits provider, status, and the categorical reason only — never the transcript, audio, or payload.
     */
    private static void logIfFailed(AudioTranscriptionResult result) {
        if (result.status() == AudioTranscriptionResult.Status.TRANSCRIBED) {
            return;
        }
        log.warnf(
                "[STT] Gnani transcription %s: %s",
                result.status().name().toLowerCase(Locale.ROOT), result.failureReason());
    }

    private static final class AudioTooLargeException extends IOException {
        AudioTooLargeException(String message) {
            super(message);
        }
    }
}
