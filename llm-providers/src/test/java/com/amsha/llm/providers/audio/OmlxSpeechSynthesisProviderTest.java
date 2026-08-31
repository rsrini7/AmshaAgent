package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OmlxSpeechSynthesisProviderTest {

    private static final String ENDPOINT = "http://127.0.0.1:8000/v1/audio/speech";

    private GovernedTransportService governedTransport;
    private OmlxSpeechSynthesisProvider provider;

    @BeforeEach
    void setUp() {
        governedTransport = mock(GovernedTransportService.class);
        provider = new OmlxSpeechSynthesisProvider(
                governedTransport,
                new ObjectMapper(),
                true,
                "Qwen3-TTS-12Hz-1.7B-Base-bf16",
                Optional.empty(),
                "wav",
                Duration.ofSeconds(120),
                1200,
                8L * 1024 * 1024,
                1,
                "not-needed",
                "http://127.0.0.1:8000/v1");
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
        OmlxSpeechSynthesisProvider smallBound = new OmlxSpeechSynthesisProvider(
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
                "http://127.0.0.1:8000/v1");
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(wavBytes(16)).when(response).body();
        stubSend(response);

        SpeechSynthesisResult result =
                smallBound.synthesize(SpeechSynthesisRequest.of("r7", "hi", null, "wav", Duration.ofSeconds(30)));

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

    private OmlxSpeechSynthesisProvider disabledProvider() {
        return new OmlxSpeechSynthesisProvider(
                governedTransport,
                new ObjectMapper(),
                false,
                "Qwen3-TTS-12Hz-1.7B-Base-bf16",
                Optional.empty(),
                "wav",
                Duration.ofSeconds(120),
                1200,
                8L * 1024 * 1024,
                1,
                "not-needed",
                "http://127.0.0.1:8000/v1");
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
    private void stubSendThrows(OutboundHttpException ex) {
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
