package com.amsha.llm.providers.audio;

import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Unremovable;

/** Governed OMLX Whisper adapter for audio-to-text transcription. */
@ApplicationScoped
@Unremovable
public class OmlxAudioTranscriptionProvider implements AudioTranscriptionPort {

    private static final Logger log = Logger.getLogger(OmlxAudioTranscriptionProvider.class);
    private static final String DEFAULT_MODEL = "whisper-large-v3-turbo";
    private static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;
    private static final int DEFAULT_MAX_TRANSCRIPT_CHARS = 12_000;
    private static final String DEFAULT_TIMEOUT = "PT60S";

    private final GovernedTransportService governedTransport;
    private final ObjectMapper objectMapper;
    private final OggOpusToWavConverter converter;

    @ConfigProperty(name = "amsha.stt.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "amsha.stt.model", defaultValue = DEFAULT_MODEL)
    String model;

    @ConfigProperty(name = "amsha.stt.timeout", defaultValue = DEFAULT_TIMEOUT)
    Duration configuredTimeout;

    @ConfigProperty(name = "amsha.stt.max-bytes", defaultValue = "26214400")
    long maxBytes;

    @ConfigProperty(name = "amsha.stt.max-transcript-chars", defaultValue = "12000")
    int maxTranscriptChars;

    @ConfigProperty(name = "AMSHA_OMLX_API_KEY", defaultValue = "not-needed")
    String apiKey;

    @ConfigProperty(name = "AMSHA_OMLX_BASE_URL", defaultValue = ModelDefaults.DEFAULT_OMLX_BASE_URL)
    String baseUrl;

    String ffmpegPath;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    public OmlxAudioTranscriptionProvider(
            GovernedTransportService governedTransport,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.stt.ffmpeg-path", defaultValue = "ffmpeg") String ffmpegPath) {
        this.governedTransport = governedTransport;
        this.objectMapper = objectMapper;
        this.ffmpegPath = ffmpegPath;
        this.converter = new OggOpusToWavConverter(ffmpegPath);
    }

    OmlxAudioTranscriptionProvider(
            GovernedTransportService governedTransport,
            ObjectMapper objectMapper,
            boolean enabled,
            String model,
            Duration configuredTimeout,
            long maxBytes,
            int maxTranscriptChars,
            String apiKey,
            String baseUrl,
            String ffmpegPath) {
        this.governedTransport = governedTransport;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.model = model;
        this.configuredTimeout = configuredTimeout;
        this.maxBytes = maxBytes;
        this.maxTranscriptChars = maxTranscriptChars;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.ffmpegPath = ffmpegPath;
        this.converter = new OggOpusToWavConverter(ffmpegPath);
    }

    @Override
    public boolean supports(String mimeType, long declaredBytes) {
        return enabled && isAudioMime(mimeType) && declaredBytes >= 0 && declaredBytes <= effectiveMaxBytes();
    }

    @Override
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }
        long started = System.nanoTime();
        AudioTranscriptionResult result;
        if (!enabled) {
            result = failure(AudioTranscriptionResult.Status.UNSUPPORTED, "disabled", started);
        } else if (!isAudioMime(request.mimeType())) {
            result = failure(AudioTranscriptionResult.Status.UNSUPPORTED, "unsupported_mime", started);
        } else if (request.declaredBytes() > effectiveMaxBytes()) {
            result = failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_too_large", started);
        } else {
            result = transcribeBounded(request, started);
        }
        record(result, elapsed(started));
        return result;
    }

    private AudioTranscriptionResult transcribeBounded(AudioTranscriptionRequest request, long started) {
        try {
            byte[] audio = readBounded(request);
            if (audio.length > effectiveMaxBytes()) {
                return failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_too_large", started);
            }
            OggOpusToWavConverter.ConvertedAudio converted = converter.convertIfNeeded(
                    audio, request.mimeType(), effectiveMaxBytes(), effectiveTimeout(request.timeout()));
            if (converted.bytes() != null && converted.bytes().length > effectiveMaxBytes()) {
                return failure(AudioTranscriptionResult.Status.TOO_LARGE, "audio_too_large", started);
            }
            Duration timeout = effectiveTimeout(request.timeout());
            HttpRequest httpRequest = buildRequest(converted.bytes(), converted.mimeType(), request, timeout);
            HttpResponse<String> response = governedTransport.send(
                    transcriptionEndpoint(),
                    OutboundPurpose.PROVIDER_API_CALL,
                    null,
                    null,
                    false,
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response, started);
        } catch (AudioConversionException e) {
            log.debugf("[STT] Audio conversion failed: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "audio_convert_error", started);
        } catch (OutboundHttpException e) {
            AudioTranscriptionResult.Status status = e.category() == OutboundHttpException.Category.TIMEOUT
                    ? AudioTranscriptionResult.Status.TIMEOUT
                    : AudioTranscriptionResult.Status.FAILED;
            return failure(
                    status,
                    status == AudioTranscriptionResult.Status.TIMEOUT ? "provider_timeout" : "provider_error",
                    started);
        } catch (IOException e) {
            log.debugf("[STT] Audio transcription input/response processing failed: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "processing_error", started);
        } catch (RuntimeException e) {
            log.debugf("[STT] Audio transcription failed: %s", LogUtil.rootMessage(e));
            return failure(AudioTranscriptionResult.Status.FAILED, "provider_error", started);
        }
    }

    private byte[] readBounded(AudioTranscriptionRequest request) throws IOException {
        byte[] inline = request.inlineData();
        if (inline != null) {
            return inline;
        }
        if (request.tempFilePath() == null) {
            throw new IOException("audio source is missing");
        }
        long fileSize = Files.size(request.tempFilePath());
        if (fileSize > effectiveMaxBytes() || fileSize > Integer.MAX_VALUE) {
            throw new IOException("audio source exceeds configured bound");
        }
        return Files.readAllBytes(request.tempFilePath());
    }

    private HttpRequest buildRequest(byte[] audio, String mimeType, AudioTranscriptionRequest request, Duration timeout)
            throws IOException {
        String boundary = "AmshaBoundary" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, audio, mimeType, request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(transcriptionEndpoint()))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/rsrini7/amsha)")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null && !apiKey.isBlank() && !"not-needed".equalsIgnoreCase(apiKey.trim())) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return builder.build();
    }

    private byte[] multipartBody(String boundary, byte[] audio, String mimeType, AudioTranscriptionRequest request)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(audio.length + 512);
        writeField(output, boundary, "model", model);
        if (request.languageHint() != null) {
            writeField(output, boundary, "language", request.languageHint());
        }
        writeBytes(output, boundary, mimeType, audio);
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(
                ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream output, String boundary, String mimeType, byte[] audio)
            throws IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio\""
                        + "\r\nContent-Type: " + mimeType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(audio);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private AudioTranscriptionResult parseResponse(HttpResponse<String> response, long started) throws IOException {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            return failure(AudioTranscriptionResult.Status.FAILED, "provider_http_error", started);
        }
        JsonNode root = objectMapper.readTree(response.body());
        String transcript = root.path("text").asText("").strip();
        if (transcript.isBlank()) {
            return failure(AudioTranscriptionResult.Status.FAILED, "empty_transcript", started);
        }
        if (transcript.length() > effectiveMaxTranscriptChars()) {
            transcript = transcript.substring(0, effectiveMaxTranscriptChars()).strip();
        }
        String language = root.path("language").asText(null);
        return new AudioTranscriptionResult(
                AudioTranscriptionResult.Status.TRANSCRIBED, transcript, language, elapsed(started), null);
    }

    private String transcriptionEndpoint() {
        String root = baseUrl == null || baseUrl.isBlank() ? ModelDefaults.DEFAULT_OMLX_BASE_URL : baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root.endsWith("/v1") ? root + "/audio/transcriptions" : root + "/v1/audio/transcriptions";
    }

    private Duration effectiveTimeout(Duration requested) {
        Duration configured = configuredTimeout == null || configuredTimeout.isNegative() || configuredTimeout.isZero()
                ? Duration.ofSeconds(60)
                : configuredTimeout;
        return requested.compareTo(configured) < 0 ? requested : configured;
    }

    private long effectiveMaxBytes() {
        return maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
    }

    private int effectiveMaxTranscriptChars() {
        return maxTranscriptChars > 0 ? maxTranscriptChars : DEFAULT_MAX_TRANSCRIPT_CHARS;
    }

    private AudioTranscriptionResult failure(AudioTranscriptionResult.Status status, String reason, long started) {
        return AudioTranscriptionResult.failure(status, reason, elapsed(started));
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private static boolean isAudioMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/ogg", "audio/mp4", "audio/webm" -> true;
            default -> false;
        };
    }

    private void record(AudioTranscriptionResult result, Duration duration) {
        if (meterRegistry == null || result == null) {
            return;
        }
        String status = result.status().name().toLowerCase(Locale.ROOT);
        try {
            meterRegistry
                    .counter("amsha.stt.transcription.total", "status", status)
                    .increment();
            meterRegistry
                    .timer("amsha.stt.transcription.duration", "status", status)
                    .record(duration);
        } catch (RuntimeException e) {
            log.debugf("[STT] Metrics unavailable: %s", LogUtil.rootMessage(e));
        }
    }
}
