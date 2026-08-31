package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.OutgoingAudioMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramChannelSenderSendAudioTest {

    private static final ChannelUser USER = new ChannelUser("u1", "chat1", "User 1", ChannelType.TELEGRAM);

    private final ProducerTemplate producer = mock(ProducerTemplate.class);
    private final TelegramMarkdownV2Renderer renderer = mock(TelegramMarkdownV2Renderer.class);
    private final TelegramChannelSender sender = new TelegramChannelSender(producer, renderer);

    @Test
    void sendAudioMapsMimeToExtensionAndSendsSeparateMessage() {
        byte[] audio = wavBytes(16);

        assertTrue(sender.sendAudio(USER, audio, "audio/wav", "evil.exe"));

        ArgumentCaptor<OutgoingAudioMessage> body = ArgumentCaptor.forClass(OutgoingAudioMessage.class);
        verify(producer)
                .sendBodyAndHeader(
                        eq("telegram:bots"), body.capture(), eq(TelegramConstants.TELEGRAM_CHAT_ID), eq("chat1"));

        assertInstanceOf(OutgoingAudioMessage.class, body.getValue());
        assertEquals("amsha-reply.wav", body.getValue().getFilenameWithExtension());
        assertEquals(audio, body.getValue().getAudio());
    }

    @Test
    void sendAudioMp3MapsToMp3Extension() {
        byte[] audio = {1, 2, 3};

        assertTrue(sender.sendAudio(USER, audio, "audio/mpeg", "x.mp3"));

        ArgumentCaptor<OutgoingAudioMessage> body = ArgumentCaptor.forClass(OutgoingAudioMessage.class);
        verify(producer)
                .sendBodyAndHeader(eq("telegram:bots"), body.capture(), eq(TelegramConstants.TELEGRAM_CHAT_ID), any());
        assertEquals("amsha-reply.mp3", body.getValue().getFilenameWithExtension());
    }

    @Test
    void sendAudioUnknownMimeFallsBackToWav() {
        byte[] audio = wavBytes(16);

        assertTrue(sender.sendAudio(USER, audio, "audio/weird", "x"));

        ArgumentCaptor<OutgoingAudioMessage> body = ArgumentCaptor.forClass(OutgoingAudioMessage.class);
        verify(producer)
                .sendBodyAndHeader(eq("telegram:bots"), body.capture(), eq(TelegramConstants.TELEGRAM_CHAT_ID), any());
        assertEquals("amsha-reply.wav", body.getValue().getFilenameWithExtension());
    }

    @Test
    void sendAudioRejectsEmptyPayload() {
        assertFalse(sender.sendAudio(USER, null, "audio/wav", "x"));
        assertFalse(sender.sendAudio(USER, new byte[0], "audio/wav", "x"));
        verify(producer, never()).sendBodyAndHeader(anyString(), any(), anyString(), any());
    }

    @Test
    void sendAudioRejectsNullRecipient() {
        byte[] audio = wavBytes(16);
        // ChannelUser itself forbids a blank channelId, so the recipient==null branch is the
        // reachable guard; the channelId().isBlank() branch stays defensive (unreachable by contract).
        assertFalse(sender.sendAudio(null, audio, "audio/wav", "x"));
        verify(producer, never()).sendBodyAndHeader(anyString(), any(), anyString(), any());
    }

    @Test
    void sendAudioCamelFailureReturnsFalse() {
        byte[] audio = wavBytes(16);
        doThrow(new RuntimeException("telegram down"))
                .when(producer)
                .sendBodyAndHeader(eq("telegram:bots"), any(), eq(TelegramConstants.TELEGRAM_CHAT_ID), any());

        assertFalse(sender.sendAudio(USER, audio, "audio/wav", "x"));
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
