package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteSessionCatalogAdapterTest {

    @Inject
    SessionCatalogStore adapter;

    private final Instant now = Instant.now();

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    private SessionCatalogEntry makeEntry(String sessionId, String owner, String channelType, String channelId) {
        return new SessionCatalogEntry(
                uid(), owner, channelType, channelId, sessionId, null, null, now, now, 0, true, "ACTIVE");
    }

    @Test
    void activateNewPersistsAndMakesActive() {
        String sid = uid();
        String scopeOwner = uid();
        String scopeCid = uid();
        adapter.activateNew(makeEntry(sid, scopeOwner, "telegram", scopeCid));

        SessionCatalogEntry resolved =
                adapter.resolveActive(scopeOwner, "telegram", scopeCid).orElseThrow();
        assertEquals(sid, resolved.sessionId());
        assertTrue(resolved.active());
        assertEquals(1, resolved.seq());
        assertTrue(adapter.resolveActive(scopeOwner, "telegram", uid()).isEmpty());
    }

    @Test
    void activateNewDeactivatesPriorAndAssignsSequentialSeq() {
        String sid1 = uid();
        String sid2 = uid();
        String scopeOwner = uid();
        String scopeCid = uid();

        adapter.activateNew(makeEntry(sid1, scopeOwner, "telegram", scopeCid));
        adapter.activateNew(makeEntry(sid2, scopeOwner, "telegram", scopeCid));

        SessionCatalogEntry active =
                adapter.resolveActive(scopeOwner, "telegram", scopeCid).orElseThrow();
        assertEquals(sid2, active.sessionId());

        var all = adapter.listByScope(scopeOwner, "telegram", scopeCid);
        assertEquals(2, all.size());

        SessionCatalogEntry old = adapter.bySessionId(sid1).orElseThrow();
        assertFalse(old.active());
        assertEquals("INACTIVE", old.status());
        assertEquals(1, old.seq());
        assertEquals(2, active.seq());
    }

    @Test
    void touchUpdatesLastUsedAt() {
        String sid = uid();
        String scopeOwner = uid();
        String scopeCid = uid();
        adapter.activateNew(makeEntry(sid, scopeOwner, "telegram", scopeCid));

        Instant beforeTouch = adapter.bySessionId(sid).orElseThrow().lastUsedAt();
        adapter.touch(sid, scopeOwner, "telegram", scopeCid, "coordinator", "summarize");
        Instant afterTouch = adapter.bySessionId(sid).orElseThrow().lastUsedAt();

        assertFalse(afterTouch.isBefore(beforeTouch));
    }

    @Test
    void archiveSetsStatusAndDeactivates() {
        String sid = uid();
        String scopeOwner = uid();
        String scopeCid = uid();
        adapter.activateNew(makeEntry(sid, scopeOwner, "telegram", scopeCid));

        adapter.archive(sid);
        SessionCatalogEntry archived = adapter.bySessionId(sid).orElseThrow();
        assertEquals("ARCHIVED", archived.status());
        assertFalse(archived.active());
    }

    @Test
    void listInactiveBeforeReturnsOnlyBeforeCutoff() {
        String sid = uid();
        String scopeOwner = uid();
        String scopeCid = uid();
        adapter.activateNew(makeEntry(sid, scopeOwner, "telegram", scopeCid));
        adapter.setActive(sid, false);

        var match = adapter.listInactiveBefore(Instant.now().plusSeconds(3600)).stream()
                .filter(e -> sid.equals(e.sessionId()))
                .toList();
        assertFalse(match.isEmpty());
        var empty = adapter.listInactiveBefore(Instant.now().minusSeconds(3600)).stream()
                .filter(e -> sid.equals(e.sessionId()))
                .toList();
        assertTrue(empty.isEmpty());
    }

    @Test
    void bySequenceReturnsCorrectOrderedEntry() {
        String sid1 = uid();
        String sid2 = uid();
        String scopeOwner = uid();
        String scopeCid = uid();

        adapter.activateNew(makeEntry(sid1, scopeOwner, "telegram", scopeCid));
        adapter.activateNew(makeEntry(sid2, scopeOwner, "telegram", scopeCid));

        assertEquals(
                sid1,
                adapter.bySequence(scopeOwner, "telegram", scopeCid, 1)
                        .orElseThrow()
                        .sessionId());
        assertEquals(
                sid2,
                adapter.bySequence(scopeOwner, "telegram", scopeCid, 2)
                        .orElseThrow()
                        .sessionId());
    }

    @Test
    void recordUpsertsAndAssignsSeqWhenNoneIsGiven() {
        String sid = uid();
        String owner = uid();
        String cid = uid();

        adapter.record(makeEntry(sid, owner, "telegram", cid));
        assertEquals(1, adapter.bySessionId(sid).orElseThrow().seq(), "seq comes from the scope maximum");

        // Re-recording the same session upserts in place rather than adding a row.
        adapter.record(new SessionCatalogEntry(
                uid(), owner, "telegram", cid, sid, "coordinator", "summarize", now, Instant.now(), 0, true, "ACTIVE"));
        assertEquals(1, adapter.listByScope(owner, "telegram", cid).size());

        SessionCatalogEntry updated = adapter.bySessionId(sid).orElseThrow();
        assertEquals("coordinator", updated.role());
        assertEquals("summarize", updated.goalPreview());
    }

    @Test
    void recordHonoursAnExplicitSeq() {
        String sid = uid();
        String owner = uid();
        String cid = uid();

        adapter.record(
                new SessionCatalogEntry(uid(), owner, "telegram", cid, sid, null, null, now, now, 7, true, "ACTIVE"));

        assertEquals(7, adapter.bySessionId(sid).orElseThrow().seq());
    }

    @Test
    void latestByScopeReturnsTheMostRecentlyUsedEntry() {
        String sid1 = uid();
        String sid2 = uid();
        String owner = uid();
        String cid = uid();
        // Distinct last_used_at: the query orders on that column, so equal values would make the result arbitrary.
        Instant older = Instant.now().minusSeconds(60);
        Instant newer = Instant.now();

        adapter.activateNew(new SessionCatalogEntry(
                uid(), owner, "telegram", cid, sid1, null, null, older, older, 0, true, "ACTIVE"));
        adapter.activateNew(new SessionCatalogEntry(
                uid(), owner, "telegram", cid, sid2, null, null, newer, newer, 0, true, "ACTIVE"));

        assertEquals(
                sid2,
                adapter.latestByScope(owner, "telegram", cid).orElseThrow().sessionId());
        assertTrue(adapter.latestByScope(owner, "telegram", uid()).isEmpty());
    }
}
