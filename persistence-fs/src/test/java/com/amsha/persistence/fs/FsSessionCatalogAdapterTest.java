package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.SessionCatalogEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsSessionCatalogAdapterTest {

    private static final List<String> ROOT = List.of("sessions", "catalog");

    @Mock
    FilesystemFileStore store;

    @Mock
    FsLockManager locks;

    @InjectMocks
    FsSessionCatalogAdapter adapter;

    private final Instant now = Instant.now();
    private SessionCatalogEntry activeEntry;
    private SessionCatalogEntry inactiveEntry;

    @BeforeEach
    void setUp() {
        lenient().when(locks.tryLock(anyString(), anyInt())).thenReturn(true);
        activeEntry = new SessionCatalogEntry(
                "cat-1", "user-1", "telegram", "chat-1", "sess-1", "coder", "build", now, now, 1, true, "ACTIVE");
        inactiveEntry = new SessionCatalogEntry(
                "cat-2", "user-2", "telegram", "chat-2", "sess-2", null, null, now, now, 2, false, "INACTIVE");
    }

    // ── record ──────────────────────────────────────────────────────

    @Test
    void recordWritesEntry() {
        adapter.record(activeEntry);
        verify(store).write(List.of("sessions", "catalog", "sess-1.json"), activeEntry);
    }

    @Test
    void recordKeepsExistingSeqWhenPositive() {
        SessionCatalogEntry e =
                new SessionCatalogEntry("cat-3", "u1", "t", "c", "s3", null, null, now, now, 5, false, "ACTIVE");
        adapter.record(e);
        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).write(anyList(), captor.capture());
        assertEquals(5, captor.getValue().seq());
    }

    // ── touch ───────────────────────────────────────────────────────

    @Test
    void touchUpdatesLastUsedAt() {
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        adapter.touch("sess-1", "user-1", "telegram", "chat-1", "coder", "build");
        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).write(anyList(), captor.capture());
        assertTrue(captor.getValue().lastUsedAt().isAfter(now));
    }

    @Test
    void touchDoesNothingWhenNotFound() {
        when(store.readOptional(anyList(), any())).thenReturn(Optional.empty());
        adapter.touch("missing", "u", "t", "c", null, null);
        verify(store, never()).write(anyList(), any());
    }

    // ── resolveActive ───────────────────────────────────────────────

    @Test
    void resolveActiveReturnsActiveEntry() {
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-1.json", "sess-2.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        when(store.readOptional(List.of("sessions", "catalog", "sess-2.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(inactiveEntry));
        var result = adapter.resolveActive("user-1", "telegram", "chat-1");
        assertTrue(result.isPresent());
        assertEquals("sess-1", result.get().sessionId());
    }

    @Test
    void resolveActiveReturnsEmptyWhenNoneActive() {
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-2.json"));
        when(store.readOptional(anyList(), any())).thenReturn(Optional.of(inactiveEntry));
        assertTrue(adapter.resolveActive("user-2", "telegram", "chat-2").isEmpty());
    }

    // ── activateNew ─────────────────────────────────────────────────

    @Test
    void activateNewDeactivatesOldAndWritesNew() {
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-active.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-active.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        SessionCatalogEntry newEntry = new SessionCatalogEntry(
                "cat-new", "user-1", "telegram", "chat-1", "sess-new", "coder", "build", now, now, 0, true, "ACTIVE");
        adapter.activateNew(newEntry);
        verify(store, times(2)).write(anyList(), any());
    }

    // ── setActive ───────────────────────────────────────────────────

    @Test
    void setActiveTrueDelegatesToActivateNew() {
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-1.json"));
        adapter.setActive("sess-1", true);
        verify(store, atLeastOnce()).write(anyList(), any());
    }

    @Test
    void setActiveFalseMarksInactive() {
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        adapter.setActive("sess-1", false);
        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).write(anyList(), captor.capture());
        assertFalse(captor.getValue().active());
        assertEquals("INACTIVE", captor.getValue().status());
    }

    // ── listByScope ─────────────────────────────────────────────────

    @Test
    void listByScopeFiltersByOwnerAndChannel() {
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-1.json", "sess-2.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        when(store.readOptional(List.of("sessions", "catalog", "sess-2.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(inactiveEntry));
        var results = adapter.listByScope("user-1", "telegram", "chat-1");
        assertEquals(1, results.size());
    }

    // ── listInactiveBefore ──────────────────────────────────────────

    @Test
    void listInactiveBeforeFiltersByCutoff() {
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-2.json"));
        when(store.readOptional(anyList(), any())).thenReturn(Optional.of(inactiveEntry));
        var before = adapter.listInactiveBefore(Instant.now().plusSeconds(10));
        assertEquals(1, before.size());
    }

    // ── latestByScope ───────────────────────────────────────────────

    @Test
    void latestByScopeReturnsMaxLastUsedAt() {
        SessionCatalogEntry newer = new SessionCatalogEntry(
                "cat-3",
                "user-1",
                "telegram",
                "chat-1",
                "sess-3",
                null,
                null,
                now,
                now.plusSeconds(60),
                3,
                true,
                "ACTIVE");
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-1.json", "sess-3.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        when(store.readOptional(List.of("sessions", "catalog", "sess-3.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(newer));
        var result = adapter.latestByScope("user-1", "telegram", "chat-1");
        assertTrue(result.isPresent());
        assertEquals("sess-3", result.get().sessionId());
    }

    // ── bySessionId ─────────────────────────────────────────────────

    @Test
    void bySessionIdReturnsEntry() {
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        assertTrue(adapter.bySessionId("sess-1").isPresent());
    }

    @Test
    void bySessionIdReturnsEmptyWhenNotFound() {
        when(store.readOptional(anyList(), any())).thenReturn(Optional.empty());
        assertTrue(adapter.bySessionId("missing").isEmpty());
    }

    // ── bySequence ──────────────────────────────────────────────────

    @Test
    void bySequenceFiltersBySeq() {
        when(store.list(ROOT, ".json")).thenReturn(List.of("sess-1.json"));
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        var result = adapter.bySequence("user-1", "telegram", "chat-1", 1);
        assertTrue(result.isPresent());
    }

    // ── archive ─────────────────────────────────────────────────────

    @Test
    void archiveMarksAsArchived() {
        when(store.readOptional(List.of("sessions", "catalog", "sess-1.json"), SessionCatalogEntry.class))
                .thenReturn(Optional.of(activeEntry));
        adapter.archive("sess-1");
        ArgumentCaptor<SessionCatalogEntry> captor = ArgumentCaptor.forClass(SessionCatalogEntry.class);
        verify(store).write(anyList(), captor.capture());
        assertEquals("ARCHIVED", captor.getValue().status());
        assertFalse(captor.getValue().active());
    }

    @Test
    void archiveDoesNothingWhenNotFound() {
        when(store.readOptional(anyList(), any())).thenReturn(Optional.empty());
        adapter.archive("missing");
        verify(store, never()).write(anyList(), any());
    }
}
