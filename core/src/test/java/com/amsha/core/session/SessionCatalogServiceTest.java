package com.amsha.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.pipeline.SessionIdResolver;
import com.amsha.core.reflex.ApprovalNotificationService;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionCatalogServiceTest {
    @Mock
    SessionCatalogStore store;

    @Mock
    SessionIdResolver legacyResolver;

    @Mock
    SessionHeartbeatTracker heartbeats;

    @Mock
    ApprovalNotificationService notifications;

    @Test
    void rotateActivatesNewScopedSessionAndFlushesNotifications() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);

        String sessionId = service.rotate("TELEGRAM", "chat-1", "user-1", "old-session");

        ArgumentCaptor<SessionCatalogEntry> entry = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).activateNew(entry.capture());
        assertEquals(sessionId, entry.getValue().sessionId());
        assertEquals("telegram", entry.getValue().channelType());
        assertEquals("chat-1", entry.getValue().channelId());
        verify(notifications).flushPendingNotifications("chat-1");
    }

    @Test
    void archivedSessionCannotBeResumed() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        Instant now = Instant.now();
        when(store.bySessionId("old-session"))
                .thenReturn(Optional.of(new SessionCatalogEntry(
                        "catalog-id",
                        "user-1",
                        "telegram",
                        "chat-1",
                        "old-session",
                        null,
                        null,
                        now,
                        now,
                        1,
                        false,
                        "ARCHIVED")));

        assertThrows(
                IllegalArgumentException.class, () -> service.resume("old-session", "user-1", "TELEGRAM", "chat-1"));
    }

    @Test
    void resumeRejectsCrossOwnerSession() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        Instant now = Instant.now();
        when(store.bySessionId("sess-1"))
                .thenReturn(Optional.of(new SessionCatalogEntry(
                        "cat-1",
                        "owner-A",
                        "telegram",
                        "chat-1",
                        "sess-1",
                        null,
                        null,
                        now,
                        now,
                        1,
                        false,
                        "INACTIVE")));

        assertThrows(IllegalArgumentException.class, () -> service.resume("sess-1", "owner-B", "telegram", "chat-1"));
    }

    @Test
    void resumeCrossChannelScopeRejected() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        Instant now = Instant.now();
        when(store.bySessionId("sess-1"))
                .thenReturn(Optional.of(new SessionCatalogEntry(
                        "cat-1",
                        "user-1",
                        "telegram",
                        "chat-A",
                        "sess-1",
                        null,
                        null,
                        now,
                        now,
                        1,
                        false,
                        "INACTIVE")));

        assertThrows(IllegalArgumentException.class, () -> service.resume("sess-1", "user-1", "telegram", "chat-B"));
    }

    @Test
    void resolveOrCreateSessionReturnsActiveFromCatalog() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        Instant now = Instant.now();
        when(store.resolveActive("user-1", "telegram", "chat-1"))
                .thenReturn(Optional.of(new SessionCatalogEntry(
                        "cat-1",
                        "user-1",
                        "telegram",
                        "chat-1",
                        "sess-active",
                        null,
                        null,
                        now,
                        now,
                        1,
                        true,
                        "ACTIVE")));

        assertEquals("sess-active", service.resolveOrCreateSession("TELEGRAM", "chat-1", "user-1"));
        verify(legacyResolver, never()).resolveOrCreate(anyString(), anyString(), anyString());
    }

    @Test
    void resolveOrCreateSessionFallsBackToLegacyWhenCatalogMissing() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        when(store.resolveActive("user-1", "telegram", "chat-1")).thenReturn(Optional.empty());
        when(legacyResolver.resolveOrCreate("TELEGRAM", "chat-1", "user-1")).thenReturn("legacy-sess");

        assertEquals("legacy-sess", service.resolveOrCreateSession("TELEGRAM", "chat-1", "user-1"));
        verify(store).activateNew(any());
    }

    @Test
    void listScopedReturnsStoreEntries() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        Instant now = Instant.now();
        List<SessionCatalogEntry> entries = List.of(new SessionCatalogEntry(
                "cat-1", "user-1", "telegram", "chat-1", "sess-1", null, null, now, now, 1, true, "ACTIVE"));
        when(store.listByScope("user-1", "telegram", "chat-1")).thenReturn(entries);

        assertEquals(1, service.listScoped("user-1", "TELEGRAM", "chat-1").size());
    }

    @Test
    void createSessionDelegatesToActivateNew() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);

        String sessionId = service.createSession("user-1", "TELEGRAM", "chat-1");

        verify(store).activateNew(any());
        verify(heartbeats).recordHeartbeat(eq(sessionId), any());
        verify(heartbeats).tryBindOwner(eq(sessionId), eq("user-1"));
    }

    @Test
    void resolveOrCreateCanonicalizesChannelType() {
        SessionCatalogService service = new SessionCatalogService(store, legacyResolver, heartbeats, notifications);
        when(store.resolveActive("user-1", "websocket", "chat-1")).thenReturn(Optional.empty());
        when(legacyResolver.resolveOrCreate("WEBSOCKET", "chat-1", "user-1")).thenReturn("ws-sess");

        assertEquals("ws-sess", service.resolveOrCreateSession("WEBSOCKET", "chat-1", "user-1"));
        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).activateNew(captor.capture());
        assertEquals("websocket", captor.getValue().channelType());
    }
}
