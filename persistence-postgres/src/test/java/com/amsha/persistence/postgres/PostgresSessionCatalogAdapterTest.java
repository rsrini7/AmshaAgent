package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
class PostgresSessionCatalogAdapterTest {

    @Inject
    SessionCatalogStore store;

    @Test
    void testRecordAndResolveActive() {
        SessionCatalogEntry entry = new SessionCatalogEntry(
                "cat-1",
                "user-1",
                "cli",
                "chat-1",
                "sess-cat-1",
                "COORDINATOR",
                "goal text",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");

        store.record(entry);
        Optional<SessionCatalogEntry> resolved = store.resolveActive("user-1", "cli", "chat-1");
        assertTrue(resolved.isPresent());
        assertEquals("sess-cat-1", resolved.get().sessionId());
    }

    @Test
    void testTouchAndArchive() {
        SessionCatalogEntry entry = new SessionCatalogEntry(
                "cat-2",
                "user-2",
                "cli",
                "chat-2",
                "sess-cat-2",
                "COORDINATOR",
                "goal text",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");

        store.record(entry);
        store.touch("sess-cat-2", "user-2", "cli", "chat-2", "coder", "new goal");
        store.archive("sess-cat-2");

        Optional<SessionCatalogEntry> byId = store.bySessionId("sess-cat-2");
        assertTrue(byId.isPresent());
        assertEquals("ARCHIVED", byId.get().status());
    }

    @Test
    void testActivateNewAndList() {
        SessionCatalogEntry e1 = new SessionCatalogEntry(
                "cat-3",
                "user-3",
                "cli",
                "chat-3",
                "sess-cat-3",
                "COORDINATOR",
                "goal 1",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");
        store.record(e1);

        SessionCatalogEntry e2 = new SessionCatalogEntry(
                "cat-4",
                "user-3",
                "cli",
                "chat-3",
                "sess-cat-4",
                "COORDINATOR",
                "goal 2",
                Instant.now(),
                Instant.now(),
                2,
                false,
                "INACTIVE");
        store.activateNew(e2);

        List<SessionCatalogEntry> list = store.listByScope("user-3", "cli", "chat-3");
        assertEquals(2, list.size());
    }

    @Test
    void recordAssignsSeqFromTheScopeMaximumWhenNoneIsGiven() {
        String sid = uid();
        String sid2 = uid();
        String owner = uid();
        String cid = uid();

        store.record(entry(sid, owner, "cli", cid, 0, Instant.now(), true));
        assertEquals(1, store.bySessionId(sid).orElseThrow().seq());

        // The second row must be inactive: idx_session_catalog_active is a partial unique index allowing only one
        // active row per scope, and unlike activateNew(), record() does not deactivate the incumbent.
        store.record(entry(sid2, owner, "cli", cid, 0, Instant.now(), false));
        assertEquals(2, store.bySessionId(sid2).orElseThrow().seq(), "the second row takes the scope maximum + 1");
    }

    @Test
    void setActiveTogglesTheRowBothWays() {
        String sid = uid();
        String owner = uid();
        String cid = uid();
        store.record(entry(sid, owner, "cli", cid, 0, Instant.now(), true));

        store.setActive(sid, false);
        SessionCatalogEntry deactivated = store.bySessionId(sid).orElseThrow();
        assertFalse(deactivated.active());
        assertEquals("INACTIVE", deactivated.status());

        store.setActive(sid, true);
        SessionCatalogEntry reactivated = store.bySessionId(sid).orElseThrow();
        assertTrue(reactivated.active());
        assertEquals("ACTIVE", reactivated.status());

        // Both arms tolerate an unknown session id.
        store.setActive("missing-" + uid(), false);
        store.setActive("missing-" + uid(), true);
    }

    @Test
    void listInactiveBeforeSelectsByStatusAndAge() {
        String sid = uid();
        String owner = uid();
        String cid = uid();
        store.record(entry(sid, owner, "cli", cid, 0, Instant.now(), true));
        store.setActive(sid, false);

        // listInactiveBefore is table-wide, so assert membership rather than an exact list.
        assertTrue(store.listInactiveBefore(Instant.now().plusSeconds(3600)).stream()
                .anyMatch(e -> sid.equals(e.sessionId())));
        assertFalse(store.listInactiveBefore(Instant.now().minusSeconds(3600)).stream()
                .anyMatch(e -> sid.equals(e.sessionId())));
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

        store.record(entry(sid1, owner, "cli", cid, 0, older, false));
        store.record(entry(sid2, owner, "cli", cid, 0, newer, true));

        assertEquals(sid2, store.latestByScope(owner, "cli", cid).orElseThrow().sessionId());
        assertTrue(store.latestByScope(owner, "cli", uid()).isEmpty());
    }

    @Test
    void bySequenceReturnsTheOrderedEntry() {
        String sid1 = uid();
        String sid2 = uid();
        String owner = uid();
        String cid = uid();

        store.record(entry(sid1, owner, "cli", cid, 0, Instant.now(), true));
        store.record(entry(sid2, owner, "cli", cid, 0, Instant.now(), false));

        assertEquals(sid1, store.bySequence(owner, "cli", cid, 1).orElseThrow().sessionId());
        assertEquals(sid2, store.bySequence(owner, "cli", cid, 2).orElseThrow().sessionId());
        assertTrue(store.bySequence(owner, "cli", cid, 99).isEmpty());
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    /** {@code seq = 0} asks the adapter to allocate the next sequence within the scope. */
    private static SessionCatalogEntry entry(
            String sessionId,
            String ownerKey,
            String channelType,
            String channelId,
            int seq,
            Instant lastUsedAt,
            boolean active) {
        return new SessionCatalogEntry(
                "cat-" + UUID.randomUUID(),
                ownerKey,
                channelType,
                channelId,
                sessionId,
                "COORDINATOR",
                "goal",
                lastUsedAt,
                lastUsedAt,
                seq,
                active,
                active ? "ACTIVE" : "INACTIVE");
    }
}
