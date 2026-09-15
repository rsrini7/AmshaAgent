package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OmlxSpeechSynthesisProviderTest {

    private static final String BASE_URL = "http://127.0.0.1:8000/v1";
    private static final String ENDPOINT = BASE_URL + "/audio/speech";

    private GovernedTransportService governedTransport;
    private OmlxSpeechSynthesisProvider provider;

    @BeforeEach
    void setUp() {
        governedTransport = mock(GovernedTransportService.class);
        provider =
                provider(true, new ObjectMapper(), Optional.empty(), "not-needed", BASE_URL, Duration.ofSeconds(120));
    }

    @Test
    void supportsAcceptsWavWithinBoundsAndRejectsOtherwise() {
        assertTrue(provider.supports(request("hello", "wav")));
        assertFalse(provider.supports(request("a".repeat(1300), "wav"))); // over max-input-chars
        assertFalse(provider.supports(null));

        OmlxSpeechSynthesisProvider disabled = disabledProvider();
        assertFalse(disabled.supports(request("hello", "wav")));
    }

    @Test
    void synthesizesSuccessfulWavResponse() {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(wavBytes(16)).when(response).body();
        stubSend(response);

        SpeechSynthesisResult result = provider.synthesize(
                SpeechSynthesisRequest.of("r1", "hello world", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.SYNTHESIZED, result.status());
        assertEquals("audio/wav", result.mimeType());
        assertEquals(16, result.audio().length);
        assertNull(result.failureReason()); // synthesized carries no failure reason
    }

    @Test
    void disabledReturnsUnsupportedWithoutCallingProvider() {
        OmlxSpeechSynthesisProvider disabled = disabledProvider();

        SpeechSynthesisResult result =
                disabled.synthesize(SpeechSynthesisRequest.of("r2", "hi", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.UNSUPPORTED, result.status());
        assertEquals("disabled", result.failureReason());
        verify(governedTransport, never())
                .send(any(), any(), any(), any(), any(Boolean.class), any(HttpRequest.class), any());
    }

    @Test
    void unsupportedFormatReturnsUnsupported() {
        SpeechSynthesisResult result =
                provider.synthesize(SpeechSynthesisRequest.of("r3", "hi", null, "mp3", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.UNSUPPORTED, result.status());
        assertEquals("unsupported_format", result.failureReason());
        verify(governedTransport, never())
                .send(any(), any(), any(), any(), any(Boolean.class), any(HttpRequest.class), any());
    }

    @Test
    void oversizeTextReturnsTooLargeWithoutCallingProvider() {
        String longText = "a".repeat(1300);
        SpeechSynthesisResult result =
                provider.synthesize(SpeechSynthesisRequest.of("r4", longText, null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.TOO_LARGE, result.status());
        assertEquals("text_too_large", result.failureReason());
        verify(governedTransport, never())
                .send(any(), any(), any(), any(), any(Boolean.class), any(HttpRequest.class), any());
    }

    @Test
    void hitlRequiredMapsToFailure() {
        stubSendThrows(OutboundHttpException.hitl("approval required"));

        SpeechSynthesisResult result =
                provider.synthesize(SpeechSynthesisRequest.of("r5", "hi", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("hitl_required", result.failureReason());
    }

    @Test
    void timeoutMapsToTimeoutStatus() {
        stubSendThrows(OutboundHttpException.timeout("provider timed out"));

        SpeechSynthesisResult result =
                provider.synthesize(SpeechSynthesisRequest.of("r6", "hi", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.TIMEOUT, result.status());
        assertEquals("provider_timeout", result.failureReason());
    }

    @Test
    void oversizedResponseIsCaughtByParser() {
        OmlxSpeechSynthesisProvider bounded = new OmlxSpeechSynthesisProvider(
                governedTransport,
                new ObjectMapper(),
                true,
                "Qwen3-TTS-12Hz-1.7B-Base-bf16",
                Optional.empty(),
                "wav",
                Duration.ofSeconds(120),
                1200,
                8L, // 8-byte cap
                1,
                "not-needed",
                BASE_URL);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(wavBytes(16)).when(response).body();
        stubSend(response);

        SpeechSynthesisResult result =
                bounded.synthesize(SpeechSynthesisRequest.of("r7", "hi", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.TOO_LARGE, result.status());
        assertEquals("response_too_large", result.failureReason());
    }

    @Test
    void malformedResponseIsCategoricalFailure() {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
                .when(response)
                .body();
        stubSend(response);

        SpeechSynthesisResult result =
                provider.synthesize(SpeechSynthesisRequest.of("r8", "hi", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("malformed_response", result.failureReason());
    }

    @Test
    void non2xxResponseIsCategoricalFailure() {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(500).when(response).statusCode();
        doReturn(new byte[] {1, 2, 3}).when(response).body();
        stubSend(response);

        SpeechSynthesisResult result =
                provider.synthesize(SpeechSynthesisRequest.of("r9", "hi", null, "wav", Duration.ofSeconds(30)));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("provider_http_error", result.failureReason());
    }

    @Test
    void nullRequestIsRejected() {
        assertThrows(NullPointerException.class, () -> provider.synthesize(null));
    }

    @Test
    void emptyProviderResponseIsCategoricalFailure() {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(new byte[0]).when(response).body();
        stubSend(response);

        SpeechSynthesisResult result = provider.synthesize(request("r-empty", "wav"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("empty_response", result.failureReason());
    }

    @Test
    void concurrencyLimitIsReportedWhenTheOnlyPermitIsHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(wavBytes(16)).when(response).body();
        doAnswer(invocation -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return response;
                })
                .when(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));

        Thread holder = new Thread(() -> provider.synthesize(request("holder", "wav")), "tts-permit-holder");
        holder.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the first synthesis never reached the transport");

            SpeechSynthesisResult rejected = provider.synthesize(request("rejected", "wav"));

            assertEquals(SpeechSynthesisResult.Status.FAILED, rejected.status());
            assertEquals("concurrency_limit", rejected.failureReason());
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void streamedResponseOverTheBoundIsMappedToTooLarge() {
        // ResponseTooLargeException extends IOException, and send(...) declares no checked exception,
        // so it has to be raised from an Answer rather than doThrow(...).
        stubSendThrowsUndeclared(new OmlxSpeechSynthesisProvider.ResponseTooLargeException("too big"));

        SpeechSynthesisResult result = provider.synthesize(request("r-stream", "wav"));

        assertEquals(SpeechSynthesisResult.Status.TOO_LARGE, result.status());
        assertEquals("response_too_large", result.failureReason());
    }

    @Test
    void serializationFailureIsAProcessingError() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("boom") {}).when(failing).writeValueAsBytes(any());

        OmlxSpeechSynthesisProvider broken =
                provider(true, failing, Optional.empty(), "not-needed", BASE_URL, Duration.ofSeconds(120));
        SpeechSynthesisResult result = broken.synthesize(request("r-serialize", "wav"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("processing_error", result.failureReason());
    }

    @Test
    void unexpectedRuntimeFailureIsAProviderError() {
        stubSendThrows(new IllegalStateException("boom"));

        SpeechSynthesisResult result = provider.synthesize(request("r-runtime", "wav"));

        assertEquals(SpeechSynthesisResult.Status.FAILED, result.status());
        assertEquals("provider_error", result.failureReason());
    }

    @Test
    void governedFailureCategoriesMapToCategoricalStatuses() {
        stubSendThrows(OutboundHttpException.oversized("response too big"));
        SpeechSynthesisResult oversized = provider.synthesize(request("g-oversized", "wav"));
        assertEquals(SpeechSynthesisResult.Status.TOO_LARGE, oversized.status());
        assertEquals("response_too_large", oversized.failureReason());

        stubSendThrows(OutboundHttpException.policy("egress denied"));
        SpeechSynthesisResult denied = provider.synthesize(request("g-policy", "wav"));
        assertEquals(SpeechSynthesisResult.Status.FAILED, denied.status());
        assertEquals("policy_denied", denied.failureReason());

        // DNS_FAILURE is not named in the switch, so it falls through to the default arm.
        stubSendThrows(OutboundHttpException.dns("unresolvable"));
        SpeechSynthesisResult unresolved = provider.synthesize(request("g-dns", "wav"));
        assertEquals(SpeechSynthesisResult.Status.FAILED, unresolved.status());
        assertEquals("provider_error", unresolved.failureReason());
    }

    @Test
    void payloadCarriesTheVoiceWhenOneIsConfiguredOrRequested() {
        stubSend(okResponse());

        assertTrue(
                sentPayload(provider, SpeechSynthesisRequest.of("v1", "hi", "af_heart", "wav", Duration.ofSeconds(30)))
                        .contains("\"voice\":\"af_heart\""));

        OmlxSpeechSynthesisProvider configured = provider(
                true, new ObjectMapper(), Optional.of("bm_george"), "not-needed", BASE_URL, Duration.ofSeconds(120));
        assertTrue(sentPayload(configured, SpeechSynthesisRequest.of("v2", "hi", null, "wav", Duration.ofSeconds(30)))
                .contains("\"voice\":\"bm_george\""));
    }

    @Test
    void authorizationHeaderIsSentOnlyForARealApiKey() {
        stubSend(okResponse());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        OmlxSpeechSynthesisProvider keyed =
                provider(true, new ObjectMapper(), Optional.empty(), "real-key", BASE_URL, Duration.ofSeconds(120));
        keyed.synthesize(request("k-real", "wav"));
        verify(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        captor.capture(),
                        any(HttpResponse.BodyHandler.class));
        assertEquals(
                "Bearer real-key",
                captor.getValue().headers().firstValue("Authorization").orElse(null));

        // "not-needed" is the sentinel the default configuration ships with, so no header is added.
        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                provider.synthesize(request("k-default", "wav")).status());
    }

    @Test
    void endpointTrimsTrailingSlashesAndAppendsV1WhenAbsent() {
        // Two different base URLs are exercised here, so the stub has to match any endpoint.
        stubSendToAnyEndpoint(okResponse());

        OmlxSpeechSynthesisProvider trailing = provider(
                true, new ObjectMapper(), Optional.empty(), "not-needed", BASE_URL + "///", Duration.ofSeconds(120));
        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                trailing.synthesize(request("e-slash", "wav")).status());
        verify(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));

        OmlxSpeechSynthesisProvider bare = provider(
                true,
                new ObjectMapper(),
                Optional.empty(),
                "not-needed",
                "http://127.0.0.1:9999/",
                Duration.ofSeconds(120));
        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                bare.synthesize(request("e-bare", "wav")).status());
        verify(governedTransport)
                .send(
                        eq("http://127.0.0.1:9999/v1/audio/speech"),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    @Test
    void nullConfiguredTimeoutFallsBackToTheDefault() {
        stubSend(okResponse());
        OmlxSpeechSynthesisProvider noTimeout =
                provider(true, new ObjectMapper(), Optional.empty(), "not-needed", BASE_URL, null);

        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                noTimeout.synthesize(request("t-default", "wav")).status());
    }

    @Test
    void metricsAreRecordedForEachOutcomeAndRegistryFailuresAreSwallowed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        provider.meterRegistry = resolvable(registry);
        stubSend(okResponse());

        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                provider.synthesize(request("m-synth", "wav")).status());
        assertEquals(
                SpeechSynthesisResult.Status.UNSUPPORTED,
                provider.synthesize(
                                SpeechSynthesisRequest.of("m-unsupported", "hi", null, "mp3", Duration.ofSeconds(30)))
                        .status());

        assertEquals(
                1.0,
                registry.get("amsha.tts.synthesis.total")
                        .tag("status", "synthesized")
                        .counter()
                        .count(),
                0.0);
        assertEquals(
                1.0,
                registry.get("amsha.tts.synthesis.total")
                        .tag("status", "unsupported")
                        .counter()
                        .count(),
                0.0);
        assertNotNull(registry.find("amsha.tts.synthesis.duration")
                .tag("status", "synthesized")
                .timer());

        MeterRegistry brokenRegistry = new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                throw new IllegalStateException("metrics unavailable");
            }
        };
        provider.meterRegistry = resolvable(brokenRegistry);
        assertEquals(
                SpeechSynthesisResult.Status.SYNTHESIZED,
                provider.synthesize(request("m-broken", "wav")).status());
    }

    private OmlxSpeechSynthesisProvider provider(
            boolean enabled,
            ObjectMapper mapper,
            Optional<String> voice,
            String apiKey,
            String baseUrl,
            Duration configuredTimeout) {
        return new OmlxSpeechSynthesisProvider(
                governedTransport,
                mapper,
                enabled,
                "Qwen3-TTS-12Hz-1.7B-Base-bf16",
                voice,
                "wav",
                configuredTimeout,
                1200,
                8L * 1024 * 1024,
                1,
                apiKey,
                baseUrl);
    }

    private OmlxSpeechSynthesisProvider disabledProvider() {
        return provider(false, new ObjectMapper(), Optional.empty(), "not-needed", BASE_URL, Duration.ofSeconds(120));
    }

    private String sentPayload(OmlxSpeechSynthesisProvider target, SpeechSynthesisRequest request) {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        target.synthesize(request);
        // atLeastOnce + getValue() reads the request from the most recent call, so the helper can be
        // reused without the captures piling up.
        verify(governedTransport, atLeastOnce())
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        captor.capture(),
                        any(HttpResponse.BodyHandler.class));
        return new String(readBody(captor.getValue()), StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> okResponse() {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(wavBytes(16)).when(response).body();
        return response;
    }

    @SuppressWarnings("unchecked")
    private void stubSend(HttpResponse<byte[]> response) {
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
    }

    @SuppressWarnings("unchecked")
    private void stubSendToAnyEndpoint(HttpResponse<byte[]> response) {
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

    @SuppressWarnings("unchecked")
    private void stubSendThrows(RuntimeException ex) {
        doThrow(ex)
                .when(governedTransport)
                .send(
                        eq(ENDPOINT),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        isNull(),
                        isNull(),
                        eq(false),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    /** Raises a checked exception that {@code send(...)} does not declare, via an Answer. */
    @SuppressWarnings("unchecked")
    private void stubSendThrowsUndeclared(Throwable failure) {
        doAnswer(invocation -> {
                    throw failure;
                })
                .when(governedTransport)
                .send(
                        eq(ENDPOINT),
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

    private static byte[] wavBytes(int len) {
        byte[] b = new byte[len];
        b[0] = 'R';
        b[1] = 'I';
        b[2] = 'F';
        b[3] = 'F';
        b[8] = 'W';
        b[9] = 'A';
        b[10] = 'V';
        b[11] = 'E';
        return b;
    }

    private static SpeechSynthesisRequest request(String text, String format) {
        return SpeechSynthesisRequest.of("supports-test", text, null, format, Duration.ofSeconds(30));
    }
}
