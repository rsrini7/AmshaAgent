package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GnaniAudioTranscriptionProviderTest {
    private static final String TEST_KEY = "configured";
    private final GovernedTransportService transport = mock(GovernedTransportService.class);
    private GnaniAudioTranscriptionProvider provider;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        provider = newProvider(Optional.of("hi-IN"));
    }

    @Test
    void usesDocumentedMultipartFieldsAndConfiguredLanguage() throws Exception {
        stubResponse(200, "{\"transcript\":\"नमस्ते\"}");

        AudioTranscriptionResult result = provider.transcribe(request("audio/wav", wav(1), null, null));

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        assertEquals("hi-IN", result.language());
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport)
                .send(
                        eq(GnaniApiContract.STT_ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        requestCaptor.capture(),
                        any(HttpResponse.BodyHandler.class));
        HttpRequest sent = requestCaptor.getValue();
        assertEquals(
                TEST_KEY,
                sent.headers().firstValue(GnaniApiContract.API_KEY_HEADER).orElseThrow());
        String multipart = new String(readBody(sent), StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("name=\"audio_file\"; filename=\"audio.wav\""));
        assertTrue(multipart.contains("name=\"language_code\"\r\n\r\nhi-IN"));
        assertTrue(multipart.contains("name=\"format\"\r\n\r\nverbatim"));
    }

    @Test
    void mapsEveryUpstream400ToFailedWithoutParsingItsBody() {
        stubResponse(400, "ignored error response");

        AudioTranscriptionResult result = provider.transcribe(request("audio/wav", wav(1), null, null));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("provider_invalid_request", result.failureReason());
    }

    @Test
    void measuredDurationAboveThirtySecondsIsTerminalAndNeverSent() {
        AudioTranscriptionResult result = provider.transcribe(request("audio/wav", wav(31), null, null));

        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, result.status());
        assertEquals("audio_duration_exceeded", result.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void shortDeclaredDurationDoesNotAuthorizeOversizedAudio() {
        AudioTranscriptionResult result =
                provider.transcribe(request("audio/wav", wav(31), null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, result.status());
        assertEquals("audio_duration_exceeded", result.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void temporaryAudioWithUnknownDeclaredByteCountCanBeMeasured() throws Exception {
        Path audioFile = tempDir.resolve("audio.wav");
        Files.write(audioFile, wav(1));
        stubResponse(200, "{\"transcript\":\"hello\"}");

        AudioTranscriptionRequest request = AudioTranscriptionRequest.ofTempFile(
                "audio-request", "audio/wav", 0, audioFile, null, Duration.ofSeconds(10));
        AudioTranscriptionResult result = provider.transcribe(request);

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
    }

    @Test
    void declaredLongDurationCanBeRejectedBeforeAudioMeasurement() {
        AudioTranscriptionResult result =
                provider.transcribe(request("audio/wav", wav(1), null, Duration.ofSeconds(31)));

        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, result.status());
        assertEquals("declared_duration_exceeded", result.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void missingConfiguredLanguageDefaultsToIndianEnglish() throws Exception {
        GnaniAudioTranscriptionProvider fallbackProvider = newProvider(Optional.empty());
        stubResponse(200, "{\"transcript\":\"hello\"}");

        AudioTranscriptionResult result = fallbackProvider.transcribe(request("audio/wav", wav(1), null, null));

        assertEquals(AudioTranscriptionResult.Status.TRANSCRIBED, result.status());
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport)
                .send(
                        eq(GnaniApiContract.STT_ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        requestCaptor.capture(),
                        any(HttpResponse.BodyHandler.class));
        String multipart = new String(readBody(requestCaptor.getValue()), StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("name=\"language_code\"\r\n\r\nen-IN"));
    }

    @Test
    void unsupportedConfiguredLanguageIsRejectedAndUnsupportedRequestLanguageNeverFallsBack() {
        assertThrows(IllegalStateException.class, () -> newProvider(Optional.of("fr-FR")));
        AudioTranscriptionResult unsupported = provider.transcribe(request("audio/wav", wav(1), "fr-FR", null));

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, unsupported.status());
        verifyNoProviderCall();
    }

    @Test
    void disabledAdapterAndUnsupportedMimeNeverReachGovernedTransport() {
        GnaniAudioTranscriptionProvider disabled = new GnaniAudioTranscriptionProvider(
                transport,
                new ObjectMapper(),
                false,
                Optional.of("hi-IN"),
                Duration.ofSeconds(30),
                Optional.of(TEST_KEY),
                Duration.ofSeconds(10),
                1_000_000,
                100,
                "missing-ffmpeg");

        assertFalse(disabled.supports("audio/wav", 20));
        assertTrue(provider.supports("audio/wav", 20));
        assertFalse(provider.supports("image/png", 20));
        assertEquals(40, provider.priority());
        verifyNoProviderCall();
    }

    @Test
    void governedFailureCategoriesMapToCategoricalStatuses() {
        stubSendThrows(OutboundHttpException.timeout("provider timed out"));
        AudioTranscriptionResult timedOut = provider.transcribe(request("audio/wav", wav(1), null, null));
        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, timedOut.status());
        assertEquals("provider_timeout", timedOut.failureReason());

        // A provider-side payload overflow is not the caller's audio being too large, so this arm
        // reports FAILED; the handler arm below reports FAILED for the same condition.
        stubSendThrows(OutboundHttpException.oversized("response too big"));
        AudioTranscriptionResult oversized = provider.transcribe(request("audio/wav", wav(1), null, null));
        assertEquals(AudioTranscriptionResult.Status.FAILED, oversized.status());
        assertEquals("provider_response_too_large", oversized.failureReason());

        stubSendThrows(OutboundHttpException.policy("egress denied"));
        AudioTranscriptionResult denied = provider.transcribe(request("audio/wav", wav(1), null, null));
        assertEquals(AudioTranscriptionResult.Status.FAILED, denied.status());
        assertEquals("policy_denied", denied.failureReason());

        // A DNS or transport I/O failure never reached Gnani, so it must not read as a provider fault.
        stubSendThrows(OutboundHttpException.dns("dns resolution failed"));
        AudioTranscriptionResult dnsFailure = provider.transcribe(request("audio/wav", wav(1), null, null));
        assertEquals(AudioTranscriptionResult.Status.FAILED, dnsFailure.status());
        assertEquals("transport_dns_failure", dnsFailure.failureReason());

        stubSendThrows(OutboundHttpException.io("connection reset"));
        AudioTranscriptionResult ioFailure = provider.transcribe(request("audio/wav", wav(1), null, null));
        assertEquals(AudioTranscriptionResult.Status.FAILED, ioFailure.status());
        assertEquals("transport_io", ioFailure.failureReason());
    }

    @Test
    void responseOverflowReportedByTheHandlerMapsToFailed() {
        stubSendThrowsAfterOverflow();

        AudioTranscriptionResult result = provider.transcribe(request("audio/wav", wav(1), null, null));

        // Both overflow arms agree: the clip was measured and accepted, so this is a provider fault,
        // not a caller-visible size limit.
        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("provider_response_too_large", result.failureReason());
    }

    @Test
    void recordsProviderTaggedTranscriptionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubResponse(200, "{\"transcript\":\"hello\"}");
        provider.transcribe(request("audio/wav", wav(1), null, null));

        stubSendThrows(OutboundHttpException.timeout("provider timed out"));
        provider.transcribe(request("audio/wav", wav(1), null, null));

        assertEquals(
                1.0,
                registry.get("amsha.stt.transcription.total")
                        .tag("provider", "gnani")
                        .tag("status", "transcribed")
                        .counter()
                        .count(),
                0.0);
        assertEquals(
                1.0,
                registry.get("amsha.stt.transcription.total")
                        .tag("provider", "gnani")
                        .tag("status", "timeout")
                        .counter()
                        .count(),
                0.0);
        // The timer is tagged by status, so each outcome is its own series.
        assertEquals(
                1.0,
                registry.find("amsha.stt.transcription.duration")
                        .tag("provider", "gnani")
                        .tag("status", "transcribed")
                        .timers()
                        .size());
    }

    @Test
    void tagsFailureMetricsWithTheCategoricalReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubSendThrows(OutboundHttpException.timeout("provider timed out"));

        AudioTranscriptionResult result = provider.transcribe(request("audio/wav", wav(1), null, null));

        assertEquals(AudioTranscriptionResult.Status.TIMEOUT, result.status());
        assertEquals("provider_timeout", result.failureReason());
        // Without the reason tag this lookup finds no series and fails, so the tag is pinned, not
        // merely present: status alone cannot distinguish a blocked transport from a provider error.
        assertEquals(
                1.0,
                registry.get("amsha.stt.transcription.total")
                        .tag("provider", "gnani")
                        .tag("status", "timeout")
                        .tag("reason", "provider_timeout")
                        .counter()
                        .count(),
                0.0);
    }

    @Test
    void tagsSuccessfulTranscriptionWithTheAbsentReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubResponse(200, "{\"transcript\":\"hello\"}");

        provider.transcribe(request("audio/wav", wav(1), null, null));

        assertEquals(
                1.0,
                registry.get("amsha.stt.transcription.total")
                        .tag("provider", "gnani")
                        .tag("status", "transcribed")
                        .tag("reason", "none")
                        .counter()
                        .count(),
                0.0);
    }

    @Test
    void tempFileSizeIsRecheckedAfterTheDeclaredBytePreCheck() throws Exception {
        GnaniAudioTranscriptionProvider bounded = new GnaniAudioTranscriptionProvider(
                transport,
                new ObjectMapper(),
                true,
                Optional.of("hi-IN"),
                Duration.ofSeconds(30),
                Optional.of(TEST_KEY),
                Duration.ofSeconds(10),
                1_000,
                100,
                "missing-ffmpeg");
        Path unknownSize = tempDir.resolve("unknown-size.wav");
        Files.write(unknownSize, new byte[2_000]);
        Path understatedSize = tempDir.resolve("understated-size.wav");
        Files.write(understatedSize, new byte[2_000]);

        AudioTranscriptionResult unknown = bounded.transcribe(AudioTranscriptionRequest.ofTempFile(
                "audio-unknown", "audio/wav", 0, unknownSize, null, Duration.ofSeconds(5)));
        AudioTranscriptionResult understated = bounded.transcribe(AudioTranscriptionRequest.ofTempFile(
                "audio-understated", "audio/wav", 500, understatedSize, null, Duration.ofSeconds(5)));

        // Both requests pass the declared-byte pre-check and are stopped by the stat before the read.
        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, unknown.status());
        assertEquals("audio_too_large", unknown.failureReason());
        assertEquals(AudioTranscriptionResult.Status.TOO_LARGE, understated.status());
        assertEquals("audio_too_large", understated.failureReason());
        verifyNoProviderCall();
    }

    @Test
    void enabledWithoutACredentialFailsAtConstruction() {
        // This guard is why the runtime credentials_missing branch cannot be reached when enabled.
        assertThrows(
                IllegalStateException.class,
                () -> new GnaniAudioTranscriptionProvider(
                        transport,
                        new ObjectMapper(),
                        true,
                        Optional.of("hi-IN"),
                        Duration.ofSeconds(30),
                        Optional.empty(),
                        Duration.ofSeconds(10),
                        1_000_000,
                        100,
                        "missing-ffmpeg"));
    }

    private GnaniAudioTranscriptionProvider newProvider(Optional<String> defaultLanguage) {
        return new GnaniAudioTranscriptionProvider(
                transport,
                new ObjectMapper(),
                true,
                defaultLanguage,
                Duration.ofSeconds(30),
                Optional.of(TEST_KEY),
                Duration.ofSeconds(10),
                1_000_000,
                100,
                "missing-ffmpeg");
    }

    private void stubResponse(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(body.getBytes(StandardCharsets.UTF_8)).when(response).body();
        doReturn(response)
                .when(transport)
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
                .when(transport)
                .send(
                        any(String.class),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    /** Drives the bounded handler past its limit before the transport reports the failure. */
    private void stubSendThrowsAfterOverflow() {
        doAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(6);
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(null);
                    subscriber.onSubscribe(mock(Flow.Subscription.class));
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[200_000])));
                    throw OutboundHttpException.oversized("response too large");
                })
                .when(transport)
                .send(
                        any(String.class),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    private static <T> Instance<T> resolvable(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private void verifyNoProviderCall() {
        verify(transport, never())
                .send(
                        any(String.class),
                        any(OutboundPurpose.class),
                        any(),
                        any(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    private static AudioTranscriptionRequest request(
            String mimeType, byte[] data, String language, Duration declaredDuration) {
        if (declaredDuration == null) {
            return AudioTranscriptionRequest.ofInline("audio-request", mimeType, data, language, Duration.ofSeconds(5));
        }
        return AudioTranscriptionRequest.ofInline(
                "audio-request", mimeType, data, language, Duration.ofSeconds(5), declaredDuration);
    }

    private static byte[] wav(int seconds) {
        return AudioDurationProbeTest.wavWithDuration(Duration.ofSeconds(seconds));
    }

    private static byte[] readBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
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
            public void onError(Throwable error) {
                completed.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                completed.complete(output.toByteArray());
            }
        });
        return completed.get(5, TimeUnit.SECONDS);
    }
}
