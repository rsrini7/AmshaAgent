package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Flow;

import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OmlxAudioTranscriptionProviderTest {

    private static final String BASE_URL = "http://127.0.0.1:8000/v1";
    private static final String ENDPOINT = BASE_URL + "/audio/transcriptions";

    private GovernedTransportService governedTransport;
    private OmlxAudioTranscriptionProvider provider;

    @BeforeEach
    void setUp() {
        governedTransport = mock(GovernedTransportService.class);
        provider = new OmlxAudioTranscriptionProvider(
                governedTransport,
                new ObjectMapper(),
                true,
                "whisper-large-v3-turbo",
                Duration.ofSeconds(30),
                1024,
                12,
                "test-key",
                BASE_URL,
                "ffmpeg");
    }

    @Test
    void transcribesSuccessfulOmlxResponseAndBoundsText() {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn("{\"text\":\"hello from whisper\",\"language\":\"en\"}")
                .when(response)
                .body();
        doReturn(response)
                .when(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));

        AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofInline(
                "audio-1", "audio/wav", new byte[] {1, 2, 3}, "en", Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("hello from w", result.transcript());
        assertEquals("en", result.language());
    }

    @Test
    void multipartFilenameCarriesExtensionMatchingEffectiveMimeType() {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn("{\"text\":\"hello from whisper\",\"language\":\"en\"}")
                .when(response)
                .body();
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(response)
                .when(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));

        AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofInline(
                "audio-mp3", "audio/mpeg", new byte[] {'I', 'D', '3', 1}, null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        verify(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        requestCaptor.capture(),
                        any(HttpResponse.BodyHandler.class));
        String multipart = new String(readBody(requestCaptor.getValue()), StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("filename=\"audio.mp3\""));
    }

    @Test
    void unsupportedOrOversizedAudioDoesNotCallProvider() {
        assertFalse(provider.supports("image/png", 10));
        assertFalse(provider.supports("audio/wav", 1025));

        AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofInline(
                "audio-2", "image/png", new byte[] {1}, null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        verifyNoProviderCall();
    }

    @Test
    void upstreamFailureIsCategorical() {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(500).when(response).statusCode();
        doReturn("secret provider response must not escape").when(response).body();
        doReturn(response)
                .when(governedTransport)
                .send(
                        any(String.class),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));

        AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofInline(
                "audio-3", "audio/mpeg", new byte[] {1}, null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("provider_http_error", result.failureReason());
        assertTrue(result.transcript() == null);
    }

    @Test
    void oggOpusWithUnavailableFfmpegDegradesSafely() {
        OmlxAudioTranscriptionProvider broken =
                providerWith(true, Duration.ofSeconds(30), 1024, 12, BASE_URL, "/nonexistent/ffmpeg-bin");

        byte[] ogg = {'O', 'g', 'g', 'S', 1, 2, 3, 4};
        AudioTranscriptionResult result = broken.transcribe(
                AudioTranscriptionRequest.ofInline("audio-4", "audio/ogg", ogg, null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("audio_convert_error", result.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void priorityNullRequestAndDisabledProviderNeverReachTheTransport() {
        assertEquals(OmlxAudioTranscriptionProvider.DEFAULT_PRIORITY, provider.priority());
        assertThrows(NullPointerException.class, () -> provider.transcribe(null));

        OmlxAudioTranscriptionProvider disabled =
                providerWith(false, Duration.ofSeconds(30), 1024, 12, BASE_URL, "ffmpeg");
        AudioTranscriptionResult result = disabled.transcribe(inline("audio-disabled", "audio/wav", new byte[] {1}));

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
        assertEquals("disabled", result.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void supportsRejectsMissingUnsupportedAndNegativeDeclarations() {
        assertTrue(provider.supports("audio/wav", 1024));
        assertTrue(provider.supports("Audio/WAV; codecs=1", 0));
        assertFalse(provider.supports(null, 10));
        assertFalse(provider.supports("   ", 10));
        assertFalse(provider.supports("image/png", 10));
        assertFalse(provider.supports("audio/wav", -1));
        assertFalse(provider.supports("audio/wav", 1025));
    }

    @Test
    void declaredPayloadBeyondTheBoundIsTooLargeWithoutCallingTheProvider() {
        AudioTranscriptionResult result = provider.transcribe(inline("audio-big", "audio/wav", new byte[2048]));

        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, result.status());
        assertEquals("audio_too_large", result.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void tempFileBeyondTheBoundIsAProcessingError() throws Exception {
        Path audio = Files.createTempFile("amsha-stt-test-", ".wav");
        try {
            Files.write(audio, new byte[2048]);

            // declaredBytes is caller-supplied for a temp file, so it can pass the cheap pre-check
            // while the file on disk is over the bound — readBounded then refuses it.
            AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofTempFile(
                    "audio-file-big", "audio/wav", 16, audio, null, Duration.ofSeconds(5)));

            assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
            assertEquals("processing_error", result.failureReason());
        } finally {
            Files.deleteIfExists(audio);
        }
        verifyNoProviderCall();
    }

    @Test
    void tempFileWithinTheBoundIsReadAndTranscribed() throws Exception {
        Path audio = Files.createTempFile("amsha-stt-test-", ".wav");
        try {
            byte[] wav = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2};
            Files.write(audio, wav);
            stubOkResponse("{\"text\":\"from file\",\"language\":\"en\"}");

            AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofTempFile(
                    "audio-file-ok", "audio/wav", wav.length, audio, null, Duration.ofSeconds(5)));

            assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
            assertEquals("from file", result.transcript());
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void governedTransportFailuresMapToCategoricalStatuses() {
        stubSendThrows(OutboundHttpException.timeout("provider too slow"));
        AudioTranscriptionResult timeout = provider.transcribe(inline("audio-timeout", "audio/wav", new byte[] {1}));
        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, timeout.status());
        assertEquals("provider_timeout", timeout.failureReason());

        stubSendThrows(OutboundHttpException.io("connection refused"));
        AudioTranscriptionResult io = provider.transcribe(inline("audio-io", "audio/wav", new byte[] {1}));
        assertEquals(AudioTranscriptionResult.Status.FAILED, io.status());
        assertEquals("provider_error", io.failureReason());
    }

    @Test
    void unparseableProviderBodyIsAProcessingError() {
        stubOkResponse("{not json");

        AudioTranscriptionResult result = provider.transcribe(inline("audio-bad-json", "audio/wav", new byte[] {1}));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("processing_error", result.failureReason());
    }

    @Test
    void unexpectedRuntimeFailureIsAProviderError() {
        stubSendThrows(new IllegalStateException("boom"));

        AudioTranscriptionResult result = provider.transcribe(inline("audio-runtime", "audio/wav", new byte[] {1}));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("provider_error", result.failureReason());
    }

    @Test
    void blankTranscriptIsAnEmptyTranscriptFailure() {
        stubOkResponse("{\"text\":\"   \"}");

        AudioTranscriptionResult result = provider.transcribe(inline("audio-empty", "audio/wav", new byte[] {1}));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("empty_transcript", result.failureReason());
    }

    @Test
    void multipartFilenameFollowsTheEffectiveMimeType() {
        stubOkResponse("{\"text\":\"ok\"}");
        byte[] wavMagic = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2};

        // A WAV payload declared with an Opus MIME passes through untouched because the magic bytes
        // win, so the part filename still reflects the caller's MIME rather than a transcoded one.
        assertTrue(multipartFor("audio/ogg", wavMagic).contains("filename=\"audio.ogg\""));
        assertTrue(multipartFor("video/webm", wavMagic).contains("filename=\"audio.webm\""));
        assertTrue(multipartFor("audio/wave", wavMagic).contains("filename=\"audio.wav\""));
    }

    @Test
    void endpointTrimsTrailingSlashesAndAppendsV1WhenAbsent() {
        stubOkResponse("{\"text\":\"ok\"}");

        OmlxAudioTranscriptionProvider trailing =
                providerWith(true, Duration.ofSeconds(30), 1024, 12, BASE_URL + "///", "ffmpeg");
        assertEquals(
                AudioTranscriptionResult.Status.TRANSCRIBED,
                trailing.transcribe(inline("audio-slash", "audio/wav", new byte[] {1}))
                        .status());
        verifySentTo(ENDPOINT);

        OmlxAudioTranscriptionProvider bare =
                providerWith(true, Duration.ofSeconds(30), 1024, 12, "http://127.0.0.1:9999", "ffmpeg");
        assertEquals(
                AudioTranscriptionResult.Status.TRANSCRIBED,
                bare.transcribe(inline("audio-bare", "audio/wav", new byte[] {1}))
                        .status());
        verifySentTo("http://127.0.0.1:9999/v1/audio/transcriptions");
    }

    @Test
    void nullConfiguredTimeoutFallsBackToSixtySeconds() {
        stubOkResponse("{\"text\":\"ok\"}");
        OmlxAudioTranscriptionProvider noTimeout = providerWith(true, null, 1024, 12, BASE_URL, "ffmpeg");

        AudioTranscriptionResult result =
                noTimeout.transcribe(inline("audio-default-timeout", "audio/wav", new byte[] {1}));

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
    }

    @Test
    void metricsAreRecordedForEachOutcomeAndRegistryFailuresAreSwallowed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubOkResponse("{\"text\":\"ok\"}");

        assertEquals(
                AudioTranscriptionResult.Status.TRANSCRIBED,
                provider.transcribe(inline("audio-metric-1", "audio/wav", new byte[] {1}))
                        .status());
        assertEquals(
                AudioTranscriptionResult.Status.UNSUPPORTED,
                provider.transcribe(inline("audio-metric-2", "image/png", new byte[] {1}))
                        .status());

        assertEquals(
                1.0,
                registry.get("amsha.stt.transcription.total")
                        .tag("status", "transcribed")
                        .counter()
                        .count(),
                0.0);
        assertEquals(
                1.0,
                registry.get("amsha.stt.transcription.total")
                        .tag("status", "unsupported")
                        .counter()
                        .count(),
                0.0);
        assertNotNull(registry.find("amsha.stt.transcription.duration")
                .tag("status", "transcribed")
                .timer());

        // A registry that rejects the counter must not turn a successful transcription into a failure.
        MeterRegistry brokenRegistry = new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                throw new IllegalStateException("metrics unavailable");
            }
        };
        provider.meterRegistry = resolvable(brokenRegistry);
        assertEquals(
                AudioTranscriptionResult.Status.TRANSCRIBED,
                provider.transcribe(inline("audio-metric-3", "audio/wav", new byte[] {1}))
                        .status());
    }

    private OmlxAudioTranscriptionProvider providerWith(
            boolean enabled,
            Duration configuredTimeout,
            long maxBytes,
            int maxTranscriptChars,
            String baseUrl,
            String ffmpegPath) {
        return new OmlxAudioTranscriptionProvider(
                governedTransport,
                new ObjectMapper(),
                enabled,
                "whisper-large-v3-turbo",
                configuredTimeout,
                maxBytes,
                maxTranscriptChars,
                "test-key",
                baseUrl,
                ffmpegPath);
    }

    private static AudioTranscriptionRequest inline(String requestId, String mimeType, byte[] data) {
        return AudioTranscriptionRequest.ofInline(requestId, mimeType, data, null, Duration.ofSeconds(5));
    }

    private void stubOkResponse(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(body).when(response).body();
        doReturn(response)
                .when(governedTransport)
                .send(
                        any(String.class),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    private void stubSendThrows(RuntimeException failure) {
        doThrow(failure)
                .when(governedTransport)
                .send(
                        any(String.class),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    private void verifyNoProviderCall() {
        verify(governedTransport, never())
                .send(
                        any(String.class),
                        any(OutboundPurpose.class),
                        any(),
                        any(),
                        any(Boolean.class),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    private void verifySentTo(String endpoint) {
        verify(governedTransport)
                .send(
                        eq(endpoint),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    private String multipartFor(String mimeType, byte[] data) {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        provider.transcribe(inline("audio-part-" + mimeType, mimeType, data));
        // atLeastOnce + getValue() reads the request from the most recent call, so the helper can be
        // reused across several MIME types without the captures piling up.
        verify(governedTransport, atLeastOnce())
                .send(
                        any(String.class),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        captor.capture(),
                        any(HttpResponse.BodyHandler.class));
        return new String(readBody(captor.getValue()), StandardCharsets.ISO_8859_1);
    }

    private static <T> Instance<T> resolvable(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static byte[] readBody(HttpRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError("Failed to read HTTP request body", throwable);
            }

            @Override
            public void onComplete() {}
        });
        return output.toByteArray();
    }
}
