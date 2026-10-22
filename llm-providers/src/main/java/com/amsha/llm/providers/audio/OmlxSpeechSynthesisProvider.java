package com.amsha.llm.providers.audio;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import com.amsha.channel.api.MediaTypes;
import com.amsha.common.config.ConfigKeys;
import com.amsha.common.http.HttpProtocol;
import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.llm.api.ModelDefaults;
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

/**
 * Governed OMLX Qwen3-TTS adapter for text-to-speech synthesis.
 *
 * <p>Speech synthesis is strictly output-only. This adapter never reasons, never transcribes, and is
 * never registered as an {@code AUDIO} chat profile. V1 supports only the directly verified WAV
 * response format; any other configured format is reported as {@code UNSUPPORTED} rather than being
 * attempted optimistically.
 *
 * <p>All egress is governed: SSRF, TLS, redirect, timeout, and HITL policy are enforced by
 * {@link GovernedTransportService}, and HITL is never bypassed via {@code preApproved=true}.
 */
@ApplicationScoped
@Unremovable
public class OmlxSpeechSynthesisProvider implements SpeechSynthesisPort {
    public static final String PROVIDER_ID = ConfigKeys.DEFAULT_TTS_PROVIDER;

    private static final Logger log = Logger.getLogger(OmlxSpeechSynthesisProvider.class);
    private static final String DEFAULT_MODEL = "Qwen3-TTS-12Hz-1.7B-Base-bf16";
    private static final String SUPPORTED_FORMAT = "wav";
    private static final String WAV_MIME = MediaTypes.WAV;
    private static final int DEFAULT_MAX_INPUT_CHARS = 1200;
    private static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;
    private static final String DEFAULT_TIMEOUT = "PT120S";
    private static final int DEFAULT_MAX_CONCURRENT = 1;
    private static final String UNKNOWN_ACTOR = "unknown";
    private static final int WAV_HEADER_MIN = 12;

    private final GovernedTransportService governedTransport;
    private final ObjectMapper objectMapper;
    private final Semaphore concurrent;

    private final boolean enabled;
    private final String model;
    private final String voice;
    private final String responseFormat;
    private final Duration configuredTimeout;
    private final int maxInputChars;
    private final long maxBytes;
    private final int maxConcurrent;
    private final String apiKey;
    private final String baseUrl;

    @Inject
    Instance<MeterRegistry> meterRegistry;

    @Inject
    public OmlxSpeechSynthesisProvider(
            GovernedTransportService governedTransport,
            ObjectMapper objectMapper,
            @ConfigProperty(name = ConfigKeys.TTS_ENABLED_PROPERTY, defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "amsha.tts.model", defaultValue = DEFAULT_MODEL) String model,
            // Optional: when unset, effectiveVoice() falls back to the per-request voice, then to the
            // model default. Injected as Optional<String> so a config source that omits amsha.tts.voice
            // (e.g. a build produced before the tts block existed) never crashes startup — an empty
            // @ConfigProperty defaultValue is silently dropped by SmallRye and would throw instead.
            @ConfigProperty(name = "amsha.tts.voice") Optional<String> voice,
            @ConfigProperty(name = "amsha.tts.response-format", defaultValue = SUPPORTED_FORMAT) String responseFormat,
            @ConfigProperty(name = ConfigKeys.TTS_TIMEOUT_PROPERTY, defaultValue = DEFAULT_TIMEOUT)
                    Duration configuredTimeout,
            @ConfigProperty(name = ConfigKeys.TTS_MAX_INPUT_CHARS_PROPERTY, defaultValue = "1200") int maxInputChars,
            @ConfigProperty(name = ConfigKeys.TTS_MAX_BYTES_PROPERTY, defaultValue = "8388608") long maxBytes,
            @ConfigProperty(name = ConfigKeys.TTS_MAX_CONCURRENT_PROPERTY, defaultValue = "1") int maxConcurrent,
            @ConfigProperty(name = ConfigKeys.OMLX_API_KEY, defaultValue = "not-needed") String apiKey,
            @ConfigProperty(name = ConfigKeys.OMLX_BASE_URL, defaultValue = ModelDefaults.DEFAULT_OMLX_BASE_URL)
                    String baseUrl) {
        this.governedTransport = governedTransport;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.model = model;
        this.voice = voice.orElse("");
        this.responseFormat = responseFormat;
        this.configuredTimeout = configuredTimeout;
        this.maxInputChars = maxInputChars;
        this.maxBytes = maxBytes;
        this.maxConcurrent = maxConcurrent;
        this.concurrent = new Semaphore(Math.max(1, maxConcurrent));
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(SpeechSynthesisRequest request) {
        return request != null
                && enabled
                && !request.text().isBlank()
                && request.text().length() <= effectiveMaxInputChars()
                && isSupportedFormat(request.responseFormat());
    }

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }
        long started = System.nanoTime();
        SpeechSynthesisResult result;
        if (!enabled) {
            result = failure(SpeechSynthesisResult.Status.UNSUPPORTED, "disabled", started);
        } else if (!isSupportedFormat(request.responseFormat())) {
            result = failure(SpeechSynthesisResult.Status.UNSUPPORTED, "unsupported_format", started);
        } else if (request.text().length() > effectiveMaxInputChars()) {
            result = failure(SpeechSynthesisResult.Status.TOO_LARGE, "text_too_large", started);
        } else if (!concurrent.tryAcquire()) {
            result = failure(SpeechSynthesisResult.Status.FAILED, "concurrency_limit", started);
        } else {
            try {
                result = synthesizeBounded(request, started);
            } finally {
                concurrent.release();
            }
        }
        record(result, elapsed(started));
        return result;
    }

    private SpeechSynthesisResult synthesizeBounded(SpeechSynthesisRequest request, long started) {
        BoundedResponseSubscriber.BoundedResponseHandler responseHandler =
                BoundedResponseSubscriber.handler(effectiveMaxBytes());
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<byte[]> response = governedTransport.send(
                    speechEndpoint(),
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
                return failure(SpeechSynthesisResult.Status.TOO_LARGE, "response_too_large", started);
            }
            return mapGovernedFailure(e, started);
        } catch (IOException e) {
            log.debugf("[TTS] Synthesis request/response processing failed: %s", LogUtil.rootMessage(e));
            return failure(SpeechSynthesisResult.Status.FAILED, "processing_error", started);
        } catch (RuntimeException e) {
            log.debugf("[TTS] Synthesis failed: %s", LogUtil.rootMessage(e));
            return failure(SpeechSynthesisResult.Status.FAILED, "provider_error", started);
        }
    }

    private SpeechSynthesisResult mapGovernedFailure(OutboundHttpException e, long started) {
        return switch (e.category()) {
            case TIMEOUT -> failure(SpeechSynthesisResult.Status.TIMEOUT, "provider_timeout", started);
            case HITL_REQUIRED -> failure(SpeechSynthesisResult.Status.FAILED, "hitl_required", started);
            // Preserve explicit overflow mapping for transports that report this category directly.
            case OVERSIZED_PAYLOAD -> failure(SpeechSynthesisResult.Status.TOO_LARGE, "response_too_large", started);
            case POLICY_DENIED, DNS_REBINDING, REDIRECT_DENIED, REDIRECT_LIMIT, TLS_TRUST ->
                failure(SpeechSynthesisResult.Status.FAILED, "policy_denied", started);
            default -> failure(SpeechSynthesisResult.Status.FAILED, "provider_error", started);
        };
    }

    private HttpRequest buildRequest(SpeechSynthesisRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", effectiveModel());
        payload.put("input", request.text());
        payload.put("response_format", request.responseFormat());
        String voiceToUse = effectiveVoice(request.voice());
        if (voiceToUse != null) {
            payload.put("voice", voiceToUse);
        }
        byte[] body = objectMapper.writeValueAsBytes(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(speechEndpoint()))
                .timeout(effectiveTimeout(request.timeout()))
                .header(HttpProtocol.HEADER_CONTENT_TYPE, HttpProtocol.MEDIA_TYPE_JSON)
                .header(HttpProtocol.HEADER_ACCEPT, "audio/*")
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/rsrini7/amsha)")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        String key = apiKey == null ? null : apiKey.trim();
        if (key != null && !key.isBlank() && !"not-needed".equalsIgnoreCase(key)) {
            builder.header(HttpProtocol.HEADER_AUTHORIZATION, HttpProtocol.AUTH_SCHEME_BEARER + key);
        }
        return builder.build();
    }

    private SpeechSynthesisResult parseResponse(HttpResponse<byte[]> response, long started) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            return failure(SpeechSynthesisResult.Status.FAILED, "provider_http_error", started);
        }
        byte[] audio = response.body();
        if (audio == null || audio.length == 0) {
            return failure(SpeechSynthesisResult.Status.FAILED, "empty_response", started);
        }
        if (audio.length > effectiveMaxBytes()) {
            return failure(SpeechSynthesisResult.Status.TOO_LARGE, "response_too_large", started);
        }
        if (!hasWavHeader(audio)) {
            return failure(SpeechSynthesisResult.Status.FAILED, "malformed_response", started);
        }
        return SpeechSynthesisResult.synthesized(audio, WAV_MIME, elapsed(started));
    }

    private static boolean hasWavHeader(byte[] audio) {
        return audio.length >= WAV_HEADER_MIN
                && audio[0] == 'R'
                && audio[1] == 'I'
                && audio[2] == 'F'
                && audio[3] == 'F'
                && audio[8] == 'W'
                && audio[9] == 'A'
                && audio[10] == 'V'
                && audio[11] == 'E';
    }

    private String speechEndpoint() {
        String root = baseUrl == null || baseUrl.isBlank() ? ModelDefaults.DEFAULT_OMLX_BASE_URL : baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root.endsWith("/v1") ? root + "/audio/speech" : root + "/v1/audio/speech";
    }

    /** Resolves the acting user for governance and audit; null when not attributable. */
    private String actorUserId() {
        String userId = WorkspaceContext.getUserIdOrUnknown();
        return UNKNOWN_ACTOR.equals(userId) ? null : userId;
    }

    private Duration effectiveTimeout(Duration requested) {
        Duration configured = configuredTimeout == null || configuredTimeout.isNegative() || configuredTimeout.isZero()
                ? Duration.parse(DEFAULT_TIMEOUT)
                : configuredTimeout;
        return requested.compareTo(configured) < 0 ? requested : configured;
    }

    private int effectiveMaxInputChars() {
        return maxInputChars > 0 ? maxInputChars : DEFAULT_MAX_INPUT_CHARS;
    }

    private long effectiveMaxBytes() {
        return maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
    }

    private String effectiveModel() {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    /** Returns the voice to send, or null so the field is omitted entirely when blank. */
    private String effectiveVoice(String requestedVoice) {
        String candidate = requestedVoice != null && !requestedVoice.isBlank() ? requestedVoice : voice;
        return candidate == null || candidate.isBlank() ? null : candidate.trim();
    }

    private boolean isSupportedFormat() {
        return isSupportedFormat(responseFormat);
    }

    private static boolean isSupportedFormat(String format) {
        return format != null && SUPPORTED_FORMAT.equalsIgnoreCase(format.trim());
    }

    private SpeechSynthesisResult failure(SpeechSynthesisResult.Status status, String reason, long started) {
        return SpeechSynthesisResult.failure(status, reason, elapsed(started));
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private void record(SpeechSynthesisResult result, Duration duration) {
        if (result == null || meterRegistry == null || !meterRegistry.isResolvable()) {
            return;
        }
        String status = result.status().name().toLowerCase(Locale.ROOT);
        try {
            MeterRegistry registry = meterRegistry.get();
            registry.counter("amsha.tts.synthesis.total", "provider", PROVIDER_ID, "status", status)
                    .increment();
            registry.timer("amsha.tts.synthesis.duration", "provider", PROVIDER_ID, "status", status)
                    .record(duration);
        } catch (RuntimeException e) {
            log.debugf("[TTS] Metrics unavailable: %s", LogUtil.rootMessage(e));
        }
    }
}
