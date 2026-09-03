package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.session.SessionRotatedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
class WebChatEndpointTest {

    @Mock
    WebSocketSessionStore sessionStore;

    @Mock
    ChannelServices channelServices;

    @Mock
    WsTokenStore wsTokenStore;

    private WebChatEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = new WebChatEndpoint();
        endpoint.sessionStore = sessionStore;
        endpoint.channelServices = channelServices;
        endpoint.wsTokenStore = wsTokenStore;
        endpoint.requireAuth = false;
    }

    @Test
    void testOnOpen() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-1");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-1");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());

        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-1"), eq("plat-1")))
                .thenReturn("sess-amsha-1");

        assertDoesNotThrow(() -> endpoint.onOpen(conn));
        verify(sessionStore).register(eq("plat-1"), eq("sess-amsha-1"), eq("plat-1"), eq(conn));
    }

    @Test
    void testOnTextMessage() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-2");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-2");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());

        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-2"), eq("plat-2")))
                .thenReturn("sess-amsha-2");
        when(channelServices.processAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

        endpoint.onOpen(conn);

        String textMessage = "{\"type\":\"message\",\"text\":\"Hello assistant!\"}";
        assertDoesNotThrow(() -> endpoint.onMessage(textMessage, conn));
        verify(channelServices).processAsync(any());
    }

    @Test
    void testOnTextMessageCarriesBoundedAudioAttachment() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-audio");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-audio");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-audio"), eq("plat-audio")))
                .thenReturn("sess-amsha-audio");
        when(channelServices.processAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        endpoint.onOpen(conn);

        String audioMessage = """
                {"type":"message","text":"","attachment":{
                  "mimeType":"audio/mpeg","fileName":"voice.mp3","data":"SUQzAQ=="
                }}
                """;
        endpoint.onMessage(audioMessage, conn);

        ArgumentCaptor<ChannelMessage> messageCaptor = ArgumentCaptor.forClass(ChannelMessage.class);
        verify(channelServices).processAsync(messageCaptor.capture());
        ChannelMessage message = messageCaptor.getValue();
        assertEquals(1, message.attachments().size());
        assertEquals("audio/mpeg", message.attachments().getFirst().mimeType());
        assertEquals("voice.mp3", message.attachments().getFirst().fileName());
        assertArrayEquals(
                new byte[] {'I', 'D', '3', 1}, message.attachments().getFirst().inlineData());
    }

    @Test
    void testOnTextMessageRejectsUnsupportedAudioMime() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-unsupported");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-unsupported");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-unsupported"), eq("plat-unsupported")))
                .thenReturn("sess-amsha-unsupported");
        endpoint.onOpen(conn);

        String unsupportedMessage = """
                {"type":"message","text":"","attachment":{
                  "mimeType":"audio/flac","fileName":"voice.flac","data":"SUQzAQ=="
                }}
                """;
        endpoint.onMessage(unsupportedMessage, conn);
        verify(conn).sendTextAndAwait(contains("Supported audio formats"));
        verify(channelServices, never()).processAsync(any());
    }

    @Test
    void testOnTextMessageRejectsEmptyAudioAttachment() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-empty-audio");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-empty-audio");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-empty-audio"), eq("plat-empty-audio")))
                .thenReturn("sess-amsha-empty-audio");
        endpoint.onOpen(conn);

        String emptyAudioMessage = """
                {"type":"message","text":"","attachment":{
                  "mimeType":"audio/wav","fileName":"voice.wav","data":""
                }}
                """;
        endpoint.onMessage(emptyAudioMessage, conn);
        verify(conn).sendTextAndAwait(contains("Audio attachment is empty"));
        verify(channelServices, never()).processAsync(any());
    }

    @Test
    void testOnTextMessageRejectsInvalidBase64Encoding() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-invalid-b64");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-invalid-b64");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-invalid-b64"), eq("plat-invalid-b64")))
                .thenReturn("sess-amsha-invalid-b64");
        endpoint.onOpen(conn);

        String invalidB64Message = """
                {"type":"message","text":"","attachment":{
                  "mimeType":"audio/wav","fileName":"voice.wav","data":"!!!not-valid-base64!!!"
                }}
                """;
        endpoint.onMessage(invalidB64Message, conn);
        verify(conn).sendTextAndAwait(contains("Audio attachment encoding is invalid"));
        verify(channelServices, never()).processAsync(any());
    }

    @Test
    void testOnClose() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-3");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-3");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());

        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-3"), eq("plat-3")))
                .thenReturn("sess-amsha-3");

        endpoint.onOpen(conn);
        assertDoesNotThrow(() -> endpoint.onClose(conn));
        verify(sessionStore).unregister("plat-3", conn);
    }

    @Test
    void testOnSessionRotated() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest hs = mock(HandshakeRequest.class);
        when(conn.id()).thenReturn("conn-4");
        when(conn.handshakeRequest()).thenReturn(hs);
        when(hs.query()).thenReturn("sessionId=plat-4");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());

        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-4"), eq("plat-4")))
                .thenReturn("sess-old");

        endpoint.onOpen(conn);
        when(sessionStore.get("plat-4")).thenReturn(Optional.of(conn));

        SessionRotatedEvent evt = new SessionRotatedEvent("sess-old", "sess-new", "owner-1", "websocket");
        assertDoesNotThrow(() -> endpoint.onSessionRotated(evt));
        verify(sessionStore).updateAmshaSession("plat-4", conn, "sess-new");
    }
}
