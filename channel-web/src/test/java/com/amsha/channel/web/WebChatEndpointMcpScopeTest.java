package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
class WebChatEndpointMcpScopeTest {
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
    void authenticatedWebSocketMessagesUseDurableOwnerScope() {
        WebSocketConnection connection = mock(WebSocketConnection.class);
        HandshakeRequest handshake = mock(HandshakeRequest.class);
        when(connection.id()).thenReturn("conn-owner");
        when(connection.handshakeRequest()).thenReturn(handshake);
        when(handshake.query()).thenReturn("sessionId=platform-tab");
        when(connection.sendText(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(channelServices.resolveOrCreateSession(anyString(), eq("platform-tab"), eq("platform-tab")))
                .thenReturn("anonymous-session");
        when(channelServices.resolveOrCreateSession(anyString(), eq("platform-tab"), eq("usr-owner")))
                .thenReturn("owner-session");
        when(wsTokenStore.validateAndConsumeToken("ws-token")).thenReturn(Optional.of("usr-owner"));
        when(channelServices.processAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

        endpoint.onOpen(connection);
        endpoint.onMessage("{\"type\":\"AUTH\",\"token\":\"ws-token\"}", connection);
        endpoint.onMessage("{\"type\":\"message\",\"text\":\"Use Tapetide\"}", connection);

        ArgumentCaptor<ChannelMessage> messages = ArgumentCaptor.forClass(ChannelMessage.class);
        Mockito.verify(channelServices).processAsync(messages.capture());
        ChannelUser sender = messages.getValue().sender();
        assertEquals("usr-owner", sender.platformId());
        assertEquals("platform-tab", sender.channelId());
        assertEquals(ChannelType.WEBSOCKET, sender.channel());
    }
}
