package com.amsha.core.speech;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.TurnDispatchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpeechDeliveryServiceTest {

    @Mock
    SpeechReplyService speechReplyService;

    @Mock
    SpeechOutputPolicy speechOutputPolicy;

    @Mock
    UserPreferenceService userPreferenceService;

    @Mock
    ChannelSender sender;

    SpeechDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new SpeechDeliveryService(speechReplyService, speechOutputPolicy, userPreferenceService);
    }

    @Test
    void hadInboundAudioDetectsAudioAttachment() {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.REST_API);
        MediaAttachment audio = MediaAttachment.ofInline(
                "a1", AttachmentType.AUDIO, "audio/ogg", "voice.ogg", new byte[] {1, 2, 3}, Map.of());
        ChannelMessage withAudio =
                new ChannelMessage.RestApiMessage(user, "transcribe this", "m1", Instant.now(), List.of(audio));
        MessageContext audioContext = new MessageContext("chat123", user, withAudio);

        ChannelMessage withoutAudio = new ChannelMessage.RestApiMessage(user, "transcribe this", "m1", Instant.now());
        MessageContext plainContext = new MessageContext("chat123", user, withoutAudio);

        assertTrue(SpeechDeliveryService.hadInboundAudio(audioContext));
        assertFalse(SpeechDeliveryService.hadInboundAudio(plainContext));
        assertFalse(SpeechDeliveryService.hadInboundAudio(null));

        MessageContext dataFlagContext = new MessageContext("chat123", user, withoutAudio);
        TurnDispatchService.markInboundAudio(dataFlagContext);
        assertTrue(SpeechDeliveryService.hadInboundAudio(dataFlagContext));
    }

    @Test
    void deliverAudioOnlySendsSynthesizedAudio() {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.TELEGRAM);
        MessageContext ctx = new MessageContext("chat123", user, null);
        ctx.setGoal("play response");

        when(userPreferenceService.getOutputMode("u1")).thenReturn(Optional.of("AUDIO_ONLY"));
        when(speechOutputPolicy.resolve(any())).thenReturn(OutputMode.AUDIO_ONLY);
        when(speechReplyService.deliver(eq(sender), eq(user), eq(OutputMode.AUDIO_ONLY), anyString()))
                .thenReturn(true);

        deliveryService.deliver(ctx, sender, user, AgentResult.finished("Hello world", List.of()), "Hello world");

        verify(speechReplyService).deliver(eq(sender), eq(user), eq(OutputMode.AUDIO_ONLY), contains("Hello world"));
        verify(sender, never()).sendRaw(any(), any());
    }

    @Test
    void deliverAudioOnlyDoesNotSynthesizeApprovalPause() {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.TELEGRAM);
        MessageContext ctx = new MessageContext("chat123", user, null);
        ctx.setGoal("reply with audio");

        when(userPreferenceService.getOutputMode("u1")).thenReturn(Optional.of("AUDIO_ONLY"));
        when(speechOutputPolicy.resolve(any())).thenReturn(OutputMode.AUDIO_ONLY);

        deliveryService.deliver(
                ctx,
                sender,
                user,
                AgentResult.finished("⏸ Approval needed for tool: serper_web_search", List.of()),
                "⏸ Approval needed for tool: serper_web_search");

        verify(sender).sendRaw(eq(user), contains("Approval needed"));
        verify(speechReplyService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    void deliverTextAndAudioSendsBoth() {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.TELEGRAM);
        MessageContext ctx = new MessageContext("chat123", user, null);
        ctx.setGoal("play response");

        when(userPreferenceService.getOutputMode("u1")).thenReturn(Optional.of("TEXT_AND_AUDIO"));
        when(speechOutputPolicy.resolve(any())).thenReturn(OutputMode.TEXT_AND_AUDIO);

        deliveryService.deliver(ctx, sender, user, AgentResult.finished("Hello world", List.of()), "Hello world");

        verify(sender).sendRaw(eq(user), contains("Hello world"));
        verify(speechReplyService)
                .deliver(eq(sender), eq(user), eq(OutputMode.TEXT_AND_AUDIO), contains("Hello world"));
    }

    @Test
    void nonFinishedResultDeliversRawTextOnly() {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.TELEGRAM);
        MessageContext ctx = new MessageContext("chat123", user, null);

        deliveryService.deliver(
                ctx, sender, user, AgentResult.awaitingApproval("tool1", "{}", "s-1", List.of()), "Awaiting approval");

        verify(sender).sendRaw(eq(user), eq("Awaiting approval"));
        verify(speechReplyService, never()).deliver(any(), any(), any(), anyString());
    }
}
