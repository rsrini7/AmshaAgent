package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.json.Json;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
class WebJobNotifierTest {

    @Mock
    WebSocketSessionStore sessionStore;

    @Mock
    WebSocketConnection conn;

    private WebJobNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new WebJobNotifier();
        notifier.sessionStore = sessionStore;
    }

    @Test
    void testSupports() {
        assertTrue(notifier.supports(ChannelType.WEBSOCKET));
        assertFalse(notifier.supports(ChannelType.CLI));
    }

    @Test
    void testNotifyApprovalRequiredNoSession() {
        when(sessionStore.get("sess-1")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> notifier.notifyApprovalRequired("sess-1", "cron-1", "bash", "Approval needed"));
    }

    @Test
    void testNotifyApprovalRequiredFallsBackToAuthenticatedOwnerConnections() {
        when(sessionStore.get("internal")).thenReturn(Optional.empty());
        when(sessionStore.getConnectionsByOwner("internal")).thenReturn(List.of(conn));
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());

        notifier.notifyApprovalRequired("internal", "cron-1", "sandbox_exec", "Approval needed");

        verify(conn).sendText(contains("approval_required"));
    }

    @Test
    void approvalNotificationCarriesDisplayParametersForUiCard() {
        when(sessionStore.get("internal")).thenReturn(Optional.empty());
        when(sessionStore.getConnectionsByOwner("internal")).thenReturn(List.of(conn));
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());

        notifier.notifyApprovalRequired(
                "internal",
                "cron-1",
                "garuda",
                "Approval needed for garuda",
                "{\"agent\":\"brahma\",\"goal\":\"compute 11!\"}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(conn).sendText(payload.capture());
        try (var reader = Json.createReader(new StringReader(payload.getValue()))) {
            var approval = reader.readObject().getJsonObject("approval");
            assertEquals("garuda", approval.getString("tool"));
            assertEquals("{\"agent\":\"brahma\",\"goal\":\"compute 11!\"}", approval.getString("params"));
        }
    }

    @Test
    void testNotifyJobCompletedWithSession() {
        when(sessionStore.get("sess-1")).thenReturn(Optional.of(conn));
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());

        assertDoesNotThrow(() -> notifier.notifyJobCompleted("sess-1", "Done!", "sess-1"));
        verify(conn).sendText(contains("Done!"));
    }

    @Test
    void testNotifyApprovalTimeout() {
        when(sessionStore.get("sess-1")).thenReturn(Optional.of(conn));
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());

        assertDoesNotThrow(() -> notifier.notifyApprovalTimeout("sess-1", "sess-1", "bash", 15));
        verify(conn).sendText(contains("Approval timeout: tool 'bash'"));
    }
}
