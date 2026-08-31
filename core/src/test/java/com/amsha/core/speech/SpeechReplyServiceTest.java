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

import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.llm.api.audio.SpeechSynthesisPort;
import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

class SpeechReplyServiceTest {

    private static final ChannelUser USER = new ChannelUser("u1", "chat1", "User 1", ChannelType.TELEGRAM);

    private final SpeechSynthesisPort port = mock(SpeechSynthesisPort.class);
    private final Instance<SpeechSynthesisPort> portInstance = mock(Instance.class);
    private final ChannelSender sender = mock(ChannelSender.class);
    private final SpeechReplyService service =
            new SpeechReplyService(portInstance, 1200, "wav", Duration.ofSeconds(120));

    @Test
    void textOnlyModeReturnsFalseWithoutResolvingPort() {
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_ONLY, "hello"));
        verify(portInstance, never()).isResolvable();
    }

    @Test
    void nullOrBlankAnswerReturnsFalse() {
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, null));
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "   "));
        verify(portInstance, never()).isResolvable();
    }

    @Test
    void portUnavailableReturnsFalseSoTextFallbackApplies() {
        when(portInstance.isResolvable()).thenReturn(false);
        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
    }

    @Test
    void synthesizedAudioIsDeliveredViaSender() {
        when(portInstance.isResolvable()).thenReturn(true);
        when(portInstance.get()).thenReturn(port);
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(port.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.synthesized(wavBytes(16), "audio/wav", Duration.ofMillis(5)));
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));

        verify(sender).sendAudio(USER, wavBytes(16), "audio/wav", "amsha-reply.wav");
    }

    @Test
    void injectedFormatAndTimeoutReachTheSynthesisRequest() {
        when(portInstance.isResolvable()).thenReturn(true);
        when(portInstance.get()).thenReturn(port);
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(port.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.synthesized(wavBytes(16), "audio/wav", Duration.ofMillis(5)));
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));

        ArgumentCaptor<SpeechSynthesisRequest> requestCaptor = ArgumentCaptor.forClass(SpeechSynthesisRequest.class);
        verify(port).synthesize(requestCaptor.capture());
        assertEquals("wav", requestCaptor.getValue().responseFormat());
        assertEquals(Duration.ofSeconds(120), requestCaptor.getValue().timeout());
    }

    @Test
    void audioOnlyModeIsDeliveredViaSender() {
        when(portInstance.isResolvable()).thenReturn(true);
        when(portInstance.get()).thenReturn(port);
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(port.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.synthesized(wavBytes(16), "audio/wav", Duration.ofMillis(5)));
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(service.deliver(sender, USER, OutputMode.AUDIO_ONLY, "hello world"));
        verify(sender).sendAudio(USER, wavBytes(16), "audio/wav", "amsha-reply.wav");
    }

    @Test
    void synthesisFailureReturnsFalseSoTextFallbackApplies() {
        when(portInstance.isResolvable()).thenReturn(true);
        when(portInstance.get()).thenReturn(port);
        when(port.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(port.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.failure(
                        SpeechSynthesisResult.Status.FAILED, "provider_error", Duration.ofMillis(5)));

        assertFalse(service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world"));
        verify(sender, never()).sendAudio(any(), any(), any(), any());
    }

    @Test
    void eventLoopGuardFailsClosedToText() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] onLoop = {false};
            boolean[] result = {true};
            vertx.runOnContext(v -> {
                onLoop[0] = Context.isOnEventLoopThread();
                result[0] = service.deliver(sender, USER, OutputMode.TEXT_AND_AUDIO, "hello world");
                latch.countDown();
            });
            assertTrue(latch.await(10, TimeUnit.SECONDS), "event loop task completed in time");
            assertTrue(onLoop[0], "sanity: handler actually ran on the event loop");
            assertFalse(result[0], "audio must be skipped on the event loop (fail closed to text)");
            verify(portInstance, never()).isResolvable();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
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
}
