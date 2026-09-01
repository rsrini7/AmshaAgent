package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.core.pipeline.MessageContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceReflexTest {

    @Mock
    UserPreferenceService userPreferenceService;

    @InjectMocks
    VoiceReflex reflex;

    private MessageContext context(String input) {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "s-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void testMatching() {
        assertTrue(reflex.matches("/voice"));
        assertTrue(reflex.matches("/voices"));
        assertTrue(reflex.matches("/tts"));
        assertTrue(reflex.matches("/tts-voice"));
        assertTrue(reflex.matches("/voice vivian"));
        assertTrue(reflex.matches("/voice default"));
        assertTrue(reflex.matches("/voice mode audio_only"));
        assertFalse(reflex.matches("/unknown"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void testSetVoice() {
        when(userPreferenceService.isValidVoice("vivian")).thenReturn(true);
        MessageContext ctx = context("/voice vivian");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice vivian", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("vivian"));
        verify(userPreferenceService).setVoice("user1", "vivian");
    }

    @Test
    void testSetOutputMode() {
        when(userPreferenceService.isValidOutputMode("AUDIO_ONLY")).thenReturn(true);
        MessageContext ctx = context("/voice mode audio_only");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice mode audio_only", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("AUDIO_ONLY"));
        verify(userPreferenceService).setOutputMode("user1", "AUDIO_ONLY");
    }

    @Test
    void testSetOutputModeDirect() {
        when(userPreferenceService.isValidOutputMode("TEXT_AND_AUDIO")).thenReturn(true);
        MessageContext ctx = context("/voice text_and_audio");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice text_and_audio", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("TEXT_AND_AUDIO"));
        verify(userPreferenceService).setOutputMode("user1", "TEXT_AND_AUDIO");
    }

    @Test
    void testClearOutputMode() {
        when(userPreferenceService.getEffectiveOutputMode("user1")).thenReturn(Optional.of("TEXT_ONLY"));
        MessageContext ctx = context("/voice mode default");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice mode default", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("cleared"));
        verify(userPreferenceService).clearOutputMode("user1");
    }

    @Test
    void testSetVoiceInvalidRejects() {
        when(userPreferenceService.isValidVoice("invalid_arg")).thenReturn(false);
        when(userPreferenceService.getAvailableVoices()).thenReturn(List.of("default", "vivian", "serena"));
        MessageContext ctx = context("/voice invalid_arg");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice invalid_arg", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("Unsupported argument"));
        verify(userPreferenceService, never()).setVoice(anyString(), anyString());
    }

    @Test
    void testClearVoice() {
        when(userPreferenceService.getEffectiveVoice("user1")).thenReturn(Optional.of("female_1"));
        MessageContext ctx = context("/voice default");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice default", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("cleared"));
        verify(userPreferenceService).clearVoice("user1");
    }

    @Test
    void testListVoices() {
        when(userPreferenceService.getVoice("user1")).thenReturn(Optional.of("vivian"));
        when(userPreferenceService.getEffectiveVoice("user1")).thenReturn(Optional.of("vivian"));
        when(userPreferenceService.getOutputMode("user1")).thenReturn(Optional.of("TEXT_AND_AUDIO"));
        when(userPreferenceService.getEffectiveOutputMode("user1")).thenReturn(Optional.of("TEXT_AND_AUDIO"));
        when(userPreferenceService.getAvailableVoices())
                .thenReturn(List.of("default", "vivian", "serena", "eric", "ryan"));

        MessageContext ctx = context("/voice");
        ReflexResult result = reflex.execute("s-1", "user1", "chat1", "/voice", ctx);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("Voice & Speech Settings"));
        assertTrue(result.commandResponse().get().contains("vivian"));
        assertTrue(result.commandResponse().get().contains("TEXT_AND_AUDIO"));
    }
}
