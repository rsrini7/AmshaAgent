package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OmlxAudioTranscriptionProviderTest {

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
                "http://127.0.0.1:8000/v1",
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
                        eq("http://127.0.0.1:8000/v1/audio/transcriptions"),
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
    void unsupportedOrOversizedAudioDoesNotCallProvider() {
        assertFalse(provider.supports("image/png", 10));
        assertFalse(provider.supports("audio/wav", 1025));

        AudioTranscriptionResult result = provider.transcribe(AudioTranscriptionRequest.ofInline(
                "audio-2", "image/png", new byte[] {1}, null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.UNSUPPORTED, result.status());
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
        OmlxAudioTranscriptionProvider broken = new OmlxAudioTranscriptionProvider(
                governedTransport,
                new ObjectMapper(),
                true,
                "whisper-large-v3-turbo",
                Duration.ofSeconds(30),
                1024,
                12,
                "test-key",
                "http://127.0.0.1:8000/v1",
                "/nonexistent/ffmpeg-bin");

        byte[] ogg = {'O', 'g', 'g', 'S', 1, 2, 3, 4};
        AudioTranscriptionResult result = broken.transcribe(
                AudioTranscriptionRequest.ofInline("audio-4", "audio/ogg", ogg, null, Duration.ofSeconds(5)));

        assertEquals(AudioTranscriptionResult.Status.FAILED, result.status());
        assertEquals("audio_convert_error", result.failureReason());
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
}
