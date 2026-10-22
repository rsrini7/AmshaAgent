package com.amsha.core.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.llm.api.audio.SpeechSynthesisPort;
import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

class SpeechReplyServiceTest {
    private static final ChannelUser USER = new ChannelUser("u1", "chat1", "User 1", ChannelType.TELEGRAM);
    private static final String OMLX_PROVIDER = "omlx";

    private final SpeechSynthesisPort port = mock(SpeechSynthesisPort.class);
    private final Instance<SpeechSynthesisPort> portInstance = mock(Instance.class);
    private final ChannelSender sender = mock(ChannelSender.class);
    private final UserPreferenceService userPreferenceService = mock(UserPreferenceService.class);
    private final SpeechReplyService service = newService(OMLX_PROVIDER);

    @Test
    void textOnlyModeReturnsFalseWithoutResolvingPort() {
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_ONLY, "hello"));
        verify(portInstance, never()).stream();
    }

    @Test
    void nullOrBlankAnswerReturnsFalse() {
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, null));
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, " "));
        verify(portInstance, never()).stream();
    }

    @Test
    void missingSelectedProviderPreservesTextFallback() {
        when(portInstance.stream()).thenReturn(Stream.empty());

        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
    }

    @Test
    void unknownProviderIdPreservesTextFallback() {
        when(port.providerId()).thenReturn(OMLX_PROVIDER);
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(portInstance.stream()).thenReturn(Stream.of(port));

        assertFalse(newService("gnani").deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
        verify(port, never()).synthesize(any(SpeechSynthesisRequest.class));
    }

    @Test
    void duplicateProviderIdsPreserveTextFallback() {
        SpeechSynthesisPort duplicate = mock(SpeechSynthesisPort.class);
        when(port.providerId()).thenReturn(OMLX_PROVIDER);
        when(duplicate.providerId()).thenReturn(OMLX_PROVIDER);
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(duplicate.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(portInstance.stream()).thenReturn(Stream.of(port, duplicate));

        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
        verify(port, never()).synthesize(any(SpeechSynthesisRequest.class));
    }

    @Test
    void synthesizedAudioIsDeliveredViaSender() {
        stubAvailablePort();
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
        verify(sender).sendAudio(USER, wavBytes(16), "audio/wav", "amsha-reply.wav");
    }

    @Test
    void injectedFormatAndTimeoutReachSynthesisRequest() {
        stubAvailablePort();
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
        ArgumentCaptor<SpeechSynthesisRequest> requestCaptor = ArgumentCaptor.forClass(SpeechSynthesisRequest.class);
        verify(port).synthesize(requestCaptor.capture());
        assertEquals("wav", requestCaptor.getValue().responseFormat());
        assertEquals(Duration.ofSeconds(120), requestCaptor.getValue().timeout());
    }

    @Test
    void audioOnlyModeIsDeliveredViaSender() {
        stubAvailablePort();
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(service.deliver(sender, USER, OutputMode.AUDIO_ONLY, "hello world"));
        verify(sender).sendAudio(USER, wavBytes(16), "audio/wav", "amsha-reply.wav");
    }

    @Test
    void synthesisFailureReturnsFalseSoTextFallbackApplies() {
        stubAvailablePort();
        when(port.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.failure(
                        SpeechSynthesisResult.Status.FAILED, "provider_error", Duration.ofMillis(5)));

        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
        verify(sender, never()).sendAudio(any(), any(), any(), any());
    }

    @Test
    void synthesisIsSkippedOnVertxEventLoop() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] onLoop = new boolean[1];
            boolean[] result = new boolean[1];
            vertx.runOnContext(_ -> {
                onLoop[0] = Context.isOnEventLoopThread();
                result[0] = service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world");
                latch.countDown();
            });

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertTrue(onLoop[0]);
            assertFalse(result[0]);
            verify(portInstance, never()).stream();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private void stubAvailablePort() {
        when(port.providerId()).thenReturn(OMLX_PROVIDER);
        when(portInstance.stream()).thenReturn(Stream.of(port));
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(port.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.synthesized(wavBytes(16), "audio/wav", Duration.ofMillis(5)));
    }

    private SpeechReplyService newService(String selectedProviderId) {
        return new SpeechReplyService(
                portInstance, userPreferenceService, selectedProviderId, 1200, "wav", Duration.ofSeconds(120));
    }

    private static byte[] wavBytes(int length) {
        byte[] bytes = new byte[length];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[8] = 'W';
        bytes[9] = 'A';
        bytes[10] = 'V';
        bytes[11] = 'E';
        return bytes;
    }
}
