package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.SessionCatalogEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsSessionCatalogAdapterTest {

    private static final List<String> CATALOG_ROOT = List.of("sessions", "catalog");

    @Mock
    FilesystemFileStore store;

    @Mock
    FsLockManager locks;

    @InjectMocks
    FsSessionCatalogAdapter adapter;

    private final Instant now = Instant.now();

    private SessionCatalogEntry entry(String sessionId, boolean active, String status, int seq) {
        return new SessionCatalogEntry(
                "cat-" + sessionId,
                "user-1",
                "telegram",
                "chat-1",
                sessionId,
                null,
                null,
                now,
                now,
                seq,
                active,
                status);
    }

    @Test
    void activateNewDeactivatesOldAndInsertsActive() {
        when(locks.tryLock(anyString(), anyInt())).thenReturn(true);
        SessionCatalogEntry old = entry("old-sess", true, "ACTIVE", 1);
        when(store.list(CATALOG_ROOT, ".json")).thenReturn(List.of("old-sess.json"));
        when(store.readOptional(List.of("sessions", "catalog", "old-sess.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(old));

        adapter.activateNew(entry("new-sess", true, "ACTIVE", 0));

        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store, times(2)).write(any(), captor.capture());
        List<SessionCatalogEntry> written = captor.getAllValues();

        SessionCatalogEntry deactivated = written.get(0);
        assertEquals("old-sess", deactivated.sessionId());
        assertFalse(deactivated.active());
        assertEquals("INACTIVE", deactivated.status());

        SessionCatalogEntry activated = written.get(1);
        assertEquals("new-sess", activated.sessionId());
        assertTrue(activated.active());
        assertEquals("ACTIVE", activated.status());
    }

    @Test
    void archiveSetsArchivedAndInactive() {
        SessionCatalogEntry existing = entry("sess-1", true, "ACTIVE", 1);
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(existing));

        adapter.archive("sess-1");

        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).write(any(), captor.capture());

        SessionCatalogEntry archived = captor.getValue();
        assertEquals("sess-1", archived.sessionId());
        assertEquals("ARCHIVED", archived.status());
        assertFalse(archived.active());
    }

    @Test
    void resolveActiveReturnsEntryWhenActive() {
        SessionCatalogEntry e = entry("sess-1", true, "ACTIVE", 1);
        when(store.list(CATALOG_ROOT, ".json")).thenReturn(List.of("sess-1.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(e));

        assertTrue(adapter.resolveActive("user-1", "telegram", "chat-1").isPresent());
    }

    @Test
    void resolveActiveReturnsEmptyWhenNoActive() {
        SessionCatalogEntry e = entry("sess-1", false, "INACTIVE", 1);
        when(store.list(CATALOG_ROOT, ".json")).thenReturn(List.of("sess-1.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(e));

        assertTrue(adapter.resolveActive("user-1", "telegram", "chat-1").isEmpty());
    }

    @Test
    void listInactiveBeforeReturnsMatchingEntries() {
        Instant oldTime = Instant.now().minusSeconds(86400);
        SessionCatalogEntry stale = new SessionCatalogEntry(
                "cat-stale",
                "user-1",
                "telegram",
                "chat-1",
                "stale-sess",
                null,
                null,
                oldTime,
                oldTime,
                1,
                false,
                "INACTIVE");
        SessionCatalogEntry recent = new SessionCatalogEntry(
                "cat-recent", "user-1", "telegram", "chat-1", "recent-sess", null, null, now, now, 2, true, "ACTIVE");
        when(store.list(CATALOG_ROOT, ".json")).thenReturn(List.of("stale-sess.json", "recent-sess.json"));
        when(store.readOptional(List.of("sessions", "catalog", "stale-sess.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(stale));
        when(store.readOptional(List.of("sessions", "catalog", "recent-sess.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(recent));

        List<SessionCatalogEntry> result =
                adapter.listInactiveBefore(Instant.now().minusSeconds(3600));
        assertEquals(1, result.size());
        assertEquals("stale-sess", result.get(0).sessionId());
    }

    @Test
    void activateNewAssignsSequentialSeq() {
        when(locks.tryLock(anyString(), anyInt())).thenReturn(true);
        when(store.list(CATALOG_ROOT, ".json")).thenReturn(Collections.emptyList());

        adapter.activateNew(entry("sess-1", true, "ACTIVE", 0));

        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).write(any(), captor.capture());
        assertEquals(1, captor.getValue().seq());
    }
}
