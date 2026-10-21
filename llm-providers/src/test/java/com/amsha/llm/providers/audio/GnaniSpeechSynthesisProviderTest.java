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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GnaniSpeechSynthesisProviderTest {
    private static final String TEST_KEY = "configured";
    private final GovernedTransportService transport = mock(GovernedTransportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private GnaniSpeechSynthesisProvider provider;

    @BeforeEach
    void setUp() {
        provider = newProvider(Optional.of(TEST_KEY), "timbre-v3.0", "Shivani");
    }

    @Test
    void mapsGenericVoiceAndDetectsTamilBeforeGovernedWavRequest() throws Exception {
        stubResponse(200, "audio/wav", wavBytes());
        SpeechSynthesisRequest request = request("வணக்கம்", "vivian");

        assertTrue(provider.supports(request));
        SpeechSynthesisResult result = provider.synthesize(request);

        assertEquals(SpeechSynthesisResult.Status.SYNTHESIZED, result.status());
        assertEquals(GnaniSpeechSynthesisProvider.PROVIDER_ID, provider.providerId());
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport)
                .send(
                        eq(GnaniApiContract.TTS_ENDPOINT),
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
        JsonNode payload = objectMapper.readTree(readBody(sent));
        assertEquals("timbre-v3.0", payload.path("model").asText());
        assertEquals("Lakshmi", payload.path("voice").asText());
        assertEquals("ta-IN", payload.path("language").asText());
        assertEquals("wav", payload.path("audio_config").path("container").asText());
    }

    @Test
    void preservesProviderSpecificVoiceNames() throws Exception {
        stubResponse(200, "audio/wav", wavBytes());

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "riyaa"));

        assertEquals(SpeechSynthesisResult.Status.SYNTHESIZED, result.status());
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport)
                .send(
                        eq(GnaniApiContract.TTS_ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        requestCaptor.capture(),
                        any(HttpResponse.BodyHandler.class));
        JsonNode payload = objectMapper.readTree(readBody(requestCaptor.getValue()));
        assertEquals("Riyaa", payload.path("voice").asText());
    }

    @Test
    void detectsHindiDevanagariLanguage() throws Exception {
        stubResponse(200, "audio/wav", wavBytes());

        SpeechSynthesisResult result = provider.synthesize(request("नमस्ते", "default"));

        assertEquals(SpeechSynthesisResult.Status.SYNTHESIZED, result.status());
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport)
                .send(
                        eq(GnaniApiContract.TTS_ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        requestCaptor.capture(),
                        any(HttpResponse.BodyHandler.class));
        JsonNode payload = objectMapper.readTree(readBody(requestCaptor.getValue()));
        assertEquals("hi-IN", payload.path("language").asText());
    }

    @Test
    void invalidModelOrMissingCredentialPreventsEgress() {
        GnaniSpeechSynthesisProvider unsupportedModel = newProvider(Optional.of(TEST_KEY), "timbre-v2.5", "Kaveri");
        GnaniSpeechSynthesisProvider normalizedModel = newProvider(Optional.of(TEST_KEY), " TIMBRE-V3.0 ", "Shivani");
        assertFalse(unsupportedModel.supports(request("Hello", "default")));
        assertTrue(normalizedModel.supports(request("Hello", "default")));
        assertEquals(
                SpeechSynthesisResult.Status.UNSUPPORTED,
                unsupportedModel.synthesize(request("Hello", "default")).status());
        assertThrows(IllegalStateException.class, () -> newProvider(Optional.empty(), "timbre-v3.0", "Shivani"));
        verifyNoProviderCall();
    }

    @Test
    void nonWavContentAndUpstream400PreserveSafeFailure() {
        stubResponse(400, "application/json", "ignored error response".getBytes(StandardCharsets.UTF_8));

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "default"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("provider_invalid_request", result.failureReason());
    }

    @Test
    void wrongSuccessfulContentTypeIsRejected() {
        stubResponse(200, "application/octet-stream", wavBytes());

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "default"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("unexpected_content_type", result.failureReason());
    }

    @Test
    void headerOnlyWavResponseIsRejected() {
        stubResponse(200, "audio/wav", Arrays.copyOf(wavBytes(), 12));

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "default"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("malformed_response", result.failureReason());
    }

    @Test
    void governedFailureCategoriesMapToCategoricalStatuses() {
        stubSendThrows(OutboundHttpException.timeout("provider timed out"));
        SpeechSynthesisResult timedOut = provider.synthesize(request("Hello", "default"));
        assertEquals(SpeechSynthesisResult.Status.TIMEOUT, timedOut.status());
        assertEquals("provider_timeout", timedOut.failureReason());

        stubSendThrows(OutboundHttpException.oversized("response too big"));
        SpeechSynthesisResult oversized = provider.synthesize(request("Hello", "default"));
        assertEquals(SpeechSynthesisResult.Status.TOO_LARGE, oversized.status());
        assertEquals("response_too_large", oversized.failureReason());

        stubSendThrows(OutboundHttpException.policy("egress denied"));
        SpeechSynthesisResult denied = provider.synthesize(request("Hello", "default"));
        assertEquals(SpeechSynthesisResult.Status.FAILED, denied.status());
        assertEquals("policy_denied", denied.failureReason());

        stubSendThrows(OutboundHttpException.hitl("approval required"));
        SpeechSynthesisResult gated = provider.synthesize(request("Hello", "default"));
        assertEquals(SpeechSynthesisResult.Status.FAILED, gated.status());
        assertEquals("hitl_required", gated.failureReason());

        // A DNS or transport I/O failure never reached Gnani, so it must not read as a provider fault.
        stubSendThrows(OutboundHttpException.dns("dns resolution failed"));
        SpeechSynthesisResult dnsFailure = provider.synthesize(request("Hello", "default"));
        assertEquals(SpeechSynthesisResult.Status.FAILED, dnsFailure.status());
        assertEquals("transport_dns_failure", dnsFailure.failureReason());

        stubSendThrows(OutboundHttpException.io("connection reset"));
        SpeechSynthesisResult ioFailure = provider.synthesize(request("Hello", "default"));
        assertEquals(SpeechSynthesisResult.Status.FAILED, ioFailure.status());
        assertEquals("transport_io", ioFailure.failureReason());
    }

    @Test
    void responseOverflowReportedByTheHandlerMapsToTooLarge() {
        stubSendThrowsAfterOverflow();

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "default"));

        assertEquals(SpeechSynthesisResult.Status.TOO_LARGE, result.status());
        assertEquals("provider_response_too_large", result.failureReason());
    }

    @Test
    void recordsProviderTaggedSynthesisMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubResponse(200, "audio/wav", wavBytes());

        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                provider.synthesize(request("Hello", "default")).status());

        assertEquals(
                1.0,
                registry.get("amsha.tts.synthesis.total")
                        .tag("provider", GnaniSpeechSynthesisProvider.PROVIDER_ID)
                        .tag("status", "synthesized")
                        .counter()
                        .count(),
                0.0);
        // The timer is tagged by status, so each outcome is its own series.
        assertEquals(
                1.0,
                registry.find("amsha.tts.synthesis.duration")
                        .tag("provider", GnaniSpeechSynthesisProvider.PROVIDER_ID)
                        .tag("status", "synthesized")
                        .timers()
                        .size());
    }

    @Test
    void tagsFailureMetricsWithTheCategoricalReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubResponse(400, "application/json", new byte[0]);

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "default"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("provider_invalid_request", result.failureReason());
        // Without the reason tag this lookup finds no series and fails, so the tag is pinned, not
        // merely present: status=failed alone is what left the live run undiagnosable.
        assertEquals(
                1.0,
                registry.get("amsha.tts.synthesis.total")
                        .tag("provider", GnaniSpeechSynthesisProvider.PROVIDER_ID)
                        .tag("status", "failed")
                        .tag("reason", "provider_invalid_request")
                        .counter()
                        .count(),
                0.0);
    }

    @Test
    void tagsSuccessfulSynthesisWithTheAbsentReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubResponse(200, "audio/wav", wavBytes());

        provider.synthesize(request("Hello", "default"));

        assertEquals(
                1.0,
                registry.get("amsha.tts.synthesis.total")
                        .tag("provider", GnaniSpeechSynthesisProvider.PROVIDER_ID)
                        .tag("status", "synthesized")
                        .tag("reason", "none")
                        .counter()
                        .count(),
                0.0);
    }

    @Test
    void failureReasonIsSurfacedWithoutAMeterRegistry() {
        provider.meterRegistry = unresolvable();
        stubResponse(400, "application/json", new byte[0]);

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "default"));

        // The reason must not depend on the registry resolving: it is the only operator diagnostic.
        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("provider_invalid_request", result.failureReason());
    }

    @Test
    void synthesizesStreamedWavWhoseChunkSizesWereNeverPatched() {
        stubResponse(200, "audio/wav", streamedWavBytes());

        SpeechSynthesisResult result = provider.synthesize(request("Hello", "Shivani"));

        // Gnani leaves the RIFF size and the data size at 0xFFFFFFFF on every response. Treating that layout
        // as malformed discarded valid audio, so this fixture must reach SYNTHESIZED.
        assertEquals(SpeechSynthesisResult.Status.SYNTHESIZED, result.status());
    }

    private GnaniSpeechSynthesisProvider newProvider(Optional<String> key, String model, String fallbackVoice) {
        return new GnaniSpeechSynthesisProvider(
                transport,
                objectMapper,
                true,
                key,
                GnaniSpeechSynthesisProvider.PROVIDER_ID,
                model,
                fallbackVoice,
                Duration.ofSeconds(10),
                100,
                1024,
                1);
    }

    private void stubResponse(int status, String contentType, byte[] body) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (_, _) -> true);
        doReturn(status).when(response).statusCode();
        doReturn(headers).when(response).headers();
        doReturn(body).when(response).body();
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
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[4096])));
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

    private static <T> Instance<T> unresolvable() {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
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

    private static SpeechSynthesisRequest request(String text, String voice) {
        return SpeechSynthesisRequest.of("tts-request", text, voice, "wav", Duration.ofSeconds(10));
    }

    private static byte[] wavBytes() {
        byte[] bytes = new byte[46];
        ByteBuffer wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(bytes.length - 8);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(16_000);
        wav.putInt(32_000);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(2);
        wav.putShort((short) 0);
        return bytes;
    }

    /** Mirrors Gnani's real layout: 48 kHz mono PCM with the RIFF and data sizes left unpatched. */
    private static byte[] streamedWavBytes() {
        byte[] bytes = new byte[44 + 960];
        ByteBuffer wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(0xFFFF_FFFF);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(48_000);
        wav.putInt(96_000);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(0xFFFF_FFFF);
        return bytes;
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
