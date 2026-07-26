package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.session.SessionRotatedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
class WebChatEndpointUnitTest {

    @Mock
    WebSocketSessionStore sessionStore;

    @Mock
    ChannelServices channelServices;

    @Mock
    WsTokenStore wsTokenStore;

    @InjectMocks
    WebChatEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint.requireAuth = false;
    }

    @Test
    void testOnOpen() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        when(conn.id()).thenReturn("conn-1");

        HandshakeRequest handshake = mock(HandshakeRequest.class);
        when(handshake.query()).thenReturn("sessionId=plat-1");
        when(conn.handshakeRequest()).thenReturn(handshake);

        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-1"), eq("plat-1")))
                .thenReturn("amsha-sess-1");
        when(sessionStore.get("plat-1")).thenReturn(Optional.empty());

        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());

        assertDoesNotThrow(() -> endpoint.onOpen(conn));
        verify(sessionStore).register("plat-1", "amsha-sess-1", conn);
    }

    @Test
    void testOnMessageAndClose() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        when(conn.id()).thenReturn("conn-1");

        HandshakeRequest handshake = mock(HandshakeRequest.class);
        when(handshake.query()).thenReturn("sessionId=plat-1");
        when(conn.handshakeRequest()).thenReturn(handshake);
        when(channelServices.resolveOrCreateSession(anyString(), eq("plat-1"), eq("plat-1")))
                .thenReturn("amsha-sess-1");
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());

        endpoint.onOpen(conn);

        when(channelServices.processAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        endpoint.onMessage("{\"type\":\"message\",\"text\":\"hello\"}", conn);

        assertDoesNotThrow(() -> endpoint.onClose(conn));
        assertDoesNotThrow(() -> endpoint.onError(conn, new RuntimeException("error")));
    }

    @Test
    void testOnSessionRotated() {
        SessionRotatedEvent event = new SessionRotatedEvent("old-sess", "new-sess", "user-1", "WEBSOCKET");
        assertDoesNotThrow(() -> endpoint.onSessionRotated(event));
    }
}
