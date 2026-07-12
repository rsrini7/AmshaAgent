package com.amsha.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
}
