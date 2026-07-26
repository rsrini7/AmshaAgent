package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;

import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.session.SessionRotatedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        verify(sessionStore).register(eq("plat-1"), eq("sess-amsha-1"), eq(conn));
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
        verify(sessionStore).unregister("plat-3");
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

        SessionRotatedEvent evt = new SessionRotatedEvent("sess-old", "sess-new", "owner-1", "websocket");
        assertDoesNotThrow(() -> endpoint.onSessionRotated(evt));
        verify(sessionStore).updateAmshaSession("plat-4", "sess-new");
    }
}
