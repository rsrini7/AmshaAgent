package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
}
