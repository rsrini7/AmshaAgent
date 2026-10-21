package com.amsha.llm.providers.audio;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import com.amsha.channel.api.MediaTypes;
import com.amsha.common.config.ConfigKeys;
import com.amsha.common.http.HttpProtocol;
import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.llm.api.audio.SpeechSynthesisPort;
import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

/** Governed, opt-in Gnani Timbre v3.0 synthesis adapter. */
@ApplicationScoped
@Unremovable
@Startup
public class GnaniSpeechSynthesisProvider implements SpeechSynthesisPort {
    public static final String PROVIDER_ID = ConfigKeys.GNANI_PROVIDER_ID;

    private static final Logger log = Logger.getLogger(GnaniSpeechSynthesisProvider.class);
    private static final String WAV_FORMAT = "wav";
    private static final String WAV_MIME = MediaTypes.WAV;
    private static final String UNKNOWN_ACTOR = "unknown";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_MAX_INPUT_CHARS = 1200;
    private static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;
    /** Metric tag value for a successful synthesis; failures always carry a categorical reason. */
    private static final String NO_FAILURE_REASON = "none";

    private final GovernedTransportService governedTransport;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String fallbackVoice;
    private final Duration configuredTimeout;
    private final int maxInputChars;
    private final long maxBytes;
    private final Semaphore concurrent;

    @Inject
    Instance<MeterRegistry> meterRegistry;

    @Inject
    public GnaniSpeechSynthesisProvider(
            GovernedTransportService governedTransport,
            ObjectMapper objectMapper,
            @ConfigProperty(name = ConfigKeys.TTS_ENABLED_PROPERTY, defaultValue = "false") boolean enabled,
            @ConfigProperty(name = ConfigKeys.GNANI_API_KEY_PROPERTY) Optional<String> apiKey,
            @ConfigProperty(name = ConfigKeys.TTS_PROVIDER_PROPERTY, defaultValue = ConfigKeys.DEFAULT_TTS_PROVIDER)
                    String selectedProvider,
            @ConfigProperty(
                            name = ConfigKeys.GNANI_TTS_MODEL_PROPERTY,
                            defaultValue = GnaniApiContract.DEFAULT_TTS_MODEL)
                    String model,
            @ConfigProperty(
                            name = ConfigKeys.GNANI_TTS_VOICE_PROPERTY,
                            defaultValue = GnaniApiContract.DEFAULT_TTS_VOICE)
                    String fallbackVoice,
            @ConfigProperty(name = ConfigKeys.TTS_TIMEOUT_PROPERTY, defaultValue = "PT120S") Duration configuredTimeout,
            @ConfigProperty(name = ConfigKeys.TTS_MAX_INPUT_CHARS_PROPERTY, defaultValue = "1200") int maxInputChars,
            @ConfigProperty(name = ConfigKeys.TTS_MAX_BYTES_PROPERTY, defaultValue = "8388608") long maxBytes,
            @ConfigProperty(name = ConfigKeys.TTS_MAX_CONCURRENT_PROPERTY, defaultValue = "1") int maxConcurrent) {
        this.governedTransport = governedTransport;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey.orElse("").trim();
        if (!this.apiKey.isBlank() && !GnaniApiContract.isValidApiKeyHeaderValue(this.apiKey)) {
            throw new IllegalArgumentException("AMSHA_GNANI_API_KEY must be a single-line printable ASCII value");
        }
        if (enabled && PROVIDER_ID.equalsIgnoreCase(selectedProvider.trim()) && this.apiKey.isBlank()) {
            throw new IllegalStateException("AMSHA_GNANI_API_KEY is required when Gnani TTS is enabled and selected");
        }
        this.model = model == null ? "" : model.trim();
        this.fallbackVoice = fallbackVoice == null ? "" : fallbackVoice.trim();
        this.configuredTimeout = configuredTimeout;
        this.maxInputChars = maxInputChars;
        this.maxBytes = maxBytes;
        this.concurrent = new Semaphore(Math.max(1, maxConcurrent));
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(SpeechSynthesisRequest request) {
        return request != null
                && enabled
                && !apiKey.isBlank()
                && supportsModel()
                && !request.text().isBlank()
                && request.text().length() <= effectiveMaxInputChars()
                && isSupportedFormat(request.responseFormat())
                && resolveVoice(request.voice()) != null
                && GnaniApiContract.TTS_LANGUAGES.contains(detectLanguage(request.text()));
    }

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }
        long started = System.nanoTime();
        SpeechSynthesisResult result = synthesizeBounded(request, started);
        logIfFailed(result);
        record(result, elapsed(started));
        return result;
    }

    private SpeechSynthesisResult synthesizeBounded(SpeechSynthesisRequest request, long started) {
        if (!enabled) {
            return failure(SpeechSynthesisResult.Status.UNSUPPORTED, "disabled", started);
        }
        if (apiKey.isBlank()) {
            return failure(SpeechSynthesisResult.Status.UNSUPPORTED, "credentials_missing", started);
        }
        if (!supportsModel()) {
            return failure(SpeechSynthesisResult.Status.UNSUPPORTED, "unsupported_model", started);
        }
        if (!isSupportedFormat(request.responseFormat())) {
            return failure(SpeechSynthesisResult.Status.UNSUPPORTED, "unsupported_format", started);
        }
        if (request.text().length() > effectiveMaxInputChars()) {
            return failure(SpeechSynthesisResult.Status.TOO_LARGE, "text_too_large", started);
        }
        String voice = resolveVoice(request.voice());
        if (voice == null) {
            return failure(SpeechSynthesisResult.Status.UNSUPPORTED, "unsupported_voice", started);
        }
        if (!concurrent.tryAcquire()) {
            return failure(SpeechSynthesisResult.Status.FAILED, "concurrency_limit", started);
        }
        try {
            return sendRequest(request, voice, started);
        } finally {
            concurrent.release();
        }
    }

    private SpeechSynthesisResult sendRequest(SpeechSynthesisRequest request, String voice, long started) {
        BoundedResponseSubscriber.BoundedResponseHandler responseHandler =
                BoundedResponseSubscriber.handler(effectiveMaxBytes());
        try {
            byte[] body = encodeRequest(request.text(), voice, detectLanguage(request.text()));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(GnaniApiContract.TTS_ENDPOINT))
                    .timeout(effectiveTimeout(request.timeout()))
                    .header(HttpProtocol.HEADER_CONTENT_TYPE, HttpProtocol.MEDIA_TYPE_JSON)
                    .header(HttpProtocol.HEADER_ACCEPT, WAV_MIME)
                    .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/rsrini7/amsha)")
                    .header(GnaniApiContract.API_KEY_HEADER, apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = governedTransport.send(
                    GnaniApiContract.TTS_ENDPOINT,
                    OutboundPurpose.PROVIDER_API_CALL,
                    null,
                    actorUserId(),
                    false,
                    httpRequest,
                    responseHandler);
            return parseResponse(response, started);
        } catch (BoundedResponseSubscriber.ResponseTooLargeException e) {
            // Defense in depth for transports that surface the subscriber exception directly.
            return failure(SpeechSynthesisResult.Status.TOO_LARGE, "response_too_large", started);
        } catch (OutboundHttpException e) {
            if (responseHandler.exceeded()) {
                return failure(SpeechSynthesisResult.Status.TOO_LARGE, "provider_response_too_large", started);
            }
            return mapGovernedFailure(e, started);
        } catch (IOException e) {
            log.debugf("[TTS] Gnani request/response processing failed: %s", LogUtil.rootMessage(e));
            return failure(SpeechSynthesisResult.Status.FAILED, "processing_error", started);
        } catch (IllegalArgumentException _) {
            log.debug("[TTS] Gnani request configuration is invalid");
            return failure(SpeechSynthesisResult.Status.FAILED, "invalid_request_configuration", started);
        } catch (RuntimeException e) {
            log.debugf("[TTS] Gnani synthesis failed: %s", LogUtil.rootMessage(e));
            return failure(SpeechSynthesisResult.Status.FAILED, "provider_error", started);
        }
    }

    private byte[] encodeRequest(String text, String voice, String language) throws IOException {
        Map<String, Object> audioConfig = Map.of(
                "sample_rate", 48_000,
                "num_channels", 1,
                "sample_width", 2,
                "encoding", "linear_pcm",
                "container", WAV_FORMAT);
        Map<String, Object> payload = Map.of(
                "text", text,
                "voice", voice,
                "model", model,
                "language", language,
                "speed", 1.0,
                "audio_config", audioConfig);
        return objectMapper.writeValueAsBytes(payload);
    }

    private SpeechSynthesisResult parseResponse(HttpResponse<byte[]> response, long started) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response == null ? 0 : response.statusCode();
            String reason = status == 400
                    ? "provider_invalid_request"
                    : status == 429 ? "provider_rate_limited" : "provider_http_error";
            return failure(SpeechSynthesisResult.Status.FAILED, reason, started);
        }
        String contentType = response.headers()
                .firstValue(HttpProtocol.HEADER_CONTENT_TYPE)
                .orElse("")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!MediaTypes.WAV.equals(contentType)) {
            return failure(SpeechSynthesisResult.Status.FAILED, "unexpected_content_type", started);
        }
        byte[] audio = response.body();
        if (audio == null || audio.length == 0) {
            return failure(SpeechSynthesisResult.Status.FAILED, "empty_response", started);
        }
        if (audio.length > effectiveMaxBytes()) {
            return failure(SpeechSynthesisResult.Status.TOO_LARGE, "response_too_large", started);
        }
        if (AudioDurationProbe.readWavDuration(audio) == null) {
            return failure(SpeechSynthesisResult.Status.FAILED, "malformed_response", started);
        }
        return SpeechSynthesisResult.synthesized(audio, MediaTypes.WAV, elapsed(started));
    }

    private boolean supportsModel() {
        return GnaniApiContract.DEFAULT_TTS_MODEL.equalsIgnoreCase(model);
    }

    private String resolveVoice(String requestedVoice) {
        String providerVoice = canonicalVoice(requestedVoice);
        if (providerVoice != null) {
            return providerVoice;
        }
        String normalized =
                requestedVoice == null ? "default" : requestedVoice.trim().toLowerCase(Locale.ROOT);
        String mapped = GnaniApiContract.GENERIC_VOICE_MAP.get(normalized);
        String candidate = canonicalVoice(mapped == null ? fallbackVoice : mapped);
        return candidate;
    }

    private static String canonicalVoice(String candidate) {
        if (candidate == null) {
            return null;
        }
        return GnaniApiContract.TTS_V3_VOICES.stream()
                .filter(voice -> voice.equalsIgnoreCase(candidate.trim()))
                .findFirst()
                .orElse(null);
    }

    static String detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return "auto";
        }
        Map<Character.UnicodeScript, Integer> counts = new EnumMap<>(Character.UnicodeScript.class);
        text.codePoints()
                .filter(Character::isLetter)
                .forEach(codePoint -> counts.merge(Character.UnicodeScript.of(codePoint), 1, Integer::sum));
        if (counts.containsKey(Character.UnicodeScript.LATIN)
                && counts.containsKey(Character.UnicodeScript.DEVANAGARI)) {
            return "hi-en";
        }
        Character.UnicodeScript selectedScript = null;
        int selectedCount = 0;
        boolean tied = false;
        for (Map.Entry<Character.UnicodeScript, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > selectedCount) {
                selectedScript = entry.getKey();
                selectedCount = entry.getValue();
                tied = false;
            } else if (entry.getValue() == selectedCount) {
                tied = true;
            }
        }
        if (selectedScript == null || tied) {
            return "auto";
        }
        return GnaniApiContract.SCRIPT_LANGUAGES.getOrDefault(selectedScript, "auto");
    }

    private SpeechSynthesisResult mapGovernedFailure(OutboundHttpException e, long started) {
        return switch (e.category()) {
            case TIMEOUT -> failure(SpeechSynthesisResult.Status.TIMEOUT, "provider_timeout", started);
            // Preserve explicit overflow mapping for transports that report this category directly.
            case OVERSIZED_PAYLOAD -> failure(SpeechSynthesisResult.Status.TOO_LARGE, "response_too_large", started);
            case HITL_REQUIRED -> failure(SpeechSynthesisResult.Status.FAILED, "hitl_required", started);
            case POLICY_DENIED, DNS_REBINDING, REDIRECT_DENIED, REDIRECT_LIMIT, TLS_TRUST ->
                failure(SpeechSynthesisResult.Status.FAILED, "policy_denied", started);
            // DNS and I/O failures never reached Gnani. Folding them into provider_error would report a
            // local network fault as a provider fault, which is the distinction an operator needs most.
            case DNS_FAILURE -> failure(SpeechSynthesisResult.Status.FAILED, "transport_dns_failure", started);
            case IO -> failure(SpeechSynthesisResult.Status.FAILED, "transport_io", started);
            default -> failure(SpeechSynthesisResult.Status.FAILED, "provider_error", started);
        };
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

    private int effectiveMaxInputChars() {
        return maxInputChars > 0 ? maxInputChars : DEFAULT_MAX_INPUT_CHARS;
    }

    private long effectiveMaxBytes() {
        return maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
    }

    private static boolean isSupportedFormat(String format) {
        return format != null && WAV_FORMAT.equalsIgnoreCase(format.trim());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private String actorUserId() {
        String userId = WorkspaceContext.getUserIdOrUnknown();
        return UNKNOWN_ACTOR.equals(userId) ? null : userId;
    }

    private SpeechSynthesisResult failure(SpeechSynthesisResult.Status status, String reason, long started) {
        return SpeechSynthesisResult.failure(status, reason, elapsed(started));
    }

    private void record(SpeechSynthesisResult result, Duration duration) {
        if (result == null || meterRegistry == null || !meterRegistry.isResolvable()) {
            return;
        }
        try {
            MeterRegistry registry = meterRegistry.get();
            String status = result.status().name().toLowerCase(Locale.ROOT);
            String reason = reasonTag(result);
            registry.counter("amsha.tts.synthesis.total", "provider", PROVIDER_ID, "status", status, "reason", reason)
                    .increment();
            registry.timer("amsha.tts.synthesis.duration", "provider", PROVIDER_ID, "status", status, "reason", reason)
                    .record(duration);
        } catch (RuntimeException e) {
            log.debugf("[TTS] Gnani metrics unavailable: %s", LogUtil.rootMessage(e));
        }
    }

    /**
     * Returns the metric tag for the outcome.
     *
     * <p>{@link SpeechSynthesisResult} rejects a non-synthesized result without a categorical
     * {@code [a-z0-9_]} reason, so this tag is bounded by construction and never carries payload or
     * user text; a successful synthesis simply has no reason to report.
     */
    private static String reasonTag(SpeechSynthesisResult result) {
        return result.failureReason() == null ? NO_FAILURE_REASON : result.failureReason();
    }

    /**
     * Surfaces the categorical failure reason.
     *
     * <p>Deliberately independent of the meter registry: an unresolvable registry must not also
     * silence the reason, which is the only diagnostic an operator has when synthesis fails. Emits
     * provider, status, and the categorical reason only — never the text, audio, or payload.
     */
    private static void logIfFailed(SpeechSynthesisResult result) {
        if (result.status() == SpeechSynthesisResult.Status.SYNTHESIZED) {
            return;
        }
        log.warnf(
                "[TTS] Gnani synthesis %s: %s",
                result.status().name().toLowerCase(Locale.ROOT), result.failureReason());
    }
}
