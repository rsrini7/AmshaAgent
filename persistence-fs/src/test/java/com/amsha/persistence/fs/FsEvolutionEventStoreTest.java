package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FsEvolutionEventStore} against a real {@link FilesystemFileStore} rooted in a
 * temp directory, so the JSON round-trip through {@code EventBatch} is exercised rather than mocked
 * away. Field injection uses reflection, matching {@code FsStoreSmokeTest}.
 */
@SuppressWarnings({"java:S1192", "java:S5443"}) // Test-only: temp dirs are acceptable here
class FsEvolutionEventStoreTest {

    private Path tempDir;
    private FsEvolutionEventStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-evolution-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();

        store = new FsEvolutionEventStore();
        setField(store, "store", fileStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    /* best-effort cleanup */
                }
            });
        }
    }

    // ─── insertBatch ───────────────────────────────────────────

    @Test
    void insertBatchRejectsNullAndEmptyInput() {
        assertEquals(0, store.insertBatch(null));
        assertEquals(0, store.insertBatch(List.of()));
    }

    @Test
    void insertBatchAppendsAcrossCallsAndReportsTheBatchSize() {
        Instant now = Instant.now();
        assertEquals(1, store.insertBatch(List.of(event("s1", "u1", now))));
        // The return value is the size of the *batch*, not the accumulated total.
        assertEquals(2, store.insertBatch(List.of(event("s1", "u1", now), event("s1", "u1", now))));
        assertEquals(3, store.getBySession("s1", 0).size());
    }

    // ─── getBySession ──────────────────────────────────────────

    @Test
    void getBySessionAppliesTheLimitOnlyWhenItIsPositiveAndExceeded() {
        Instant now = Instant.now();
        store.insertBatch(List.of(event("s1", "u1", now), event("s1", "u1", now), event("s1", "u1", now)));

        assertEquals(3, store.getBySession("s1", 0).size(), "a non-positive limit means no limit");
        assertEquals(3, store.getBySession("s1", 10).size(), "a limit above the size is not exceeded");
        assertEquals(2, store.getBySession("s1", 2).size());
    }

    @Test
    void getBySessionForAnUnknownSessionIsEmpty() {
        assertTrue(store.getBySession("never-written", 0).isEmpty());
    }

    // ─── getByUser ─────────────────────────────────────────────

    @Test
    void getByUserFiltersAcrossSessionsAndSortsNewestFirst() {
        Instant base = Instant.now();
        // Three sessions: two belong to u1, one to u2. Newest is in a different file from the oldest.
        store.insertBatch(List.of(event("session-a", "u1", base.minus(3, ChronoUnit.HOURS))));
        store.insertBatch(List.of(event("session-b", "u1", base.minus(2, ChronoUnit.HOURS))));
        store.insertBatch(List.of(event("session-a", "u1", base.minus(1, ChronoUnit.HOURS))));
        store.insertBatch(List.of(event("session-c", "u2", base)));

        List<EvolutionEvent> mine = store.getByUser("u1", 0);
        assertEquals(3, mine.size());
        assertEquals(
                List.of("session-a", "session-b", "session-a"),
                mine.stream().map(EvolutionEvent::sessionId).toList(),
                "sorted by createdAt descending: t-1h, t-2h, t-3h");
        assertTrue(mine.get(0).createdAt().isAfter(mine.get(1).createdAt()), "the newest event must come first");

        assertEquals(2, store.getByUser("u1", 2).size());
        assertEquals(3, store.getByUser("u1", 10).size(), "a limit above the size is not exceeded");
        assertEquals(1, store.getByUser("u2", 0).size());
        assertTrue(store.getByUser("nobody", 0).isEmpty());
    }

    // ─── purgeExpired ──────────────────────────────────────────

    @Test
    void purgeExpiredRemovesOnlyEventsOlderThanTheCutoff() {
        Instant now = Instant.now();
        store.insertBatch(List.of(event("s1", "u1", now.minus(10, ChronoUnit.DAYS))));
        store.insertBatch(List.of(event("s1", "u1", now)));

        assertEquals(1, store.purgeExpired(1), "only the 10-day-old event is past a 1-day retention");
        List<EvolutionEvent> remaining = store.getBySession("s1", 0);
        assertEquals(1, remaining.size());
        assertEquals(now, remaining.getFirst().createdAt());

        // Nothing left to purge: the write-back must be skipped, and the count stays 0.
        assertEquals(0, store.purgeExpired(1));
        assertEquals(1, store.getBySession("s1", 0).size());
    }

    @Test
    void purgeExpiredOverAnEmptyStoreIsZero() {
        assertEquals(0, store.purgeExpired(7));
    }

    // ─── Helpers ───────────────────────────────────────────────

    private static EvolutionEvent event(String sessionId, String userId, Instant createdAt) {
        return new EvolutionEvent(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                EvolutionEventType.USER_MESSAGE,
                "coordinator",
                "content",
                null,
                1,
                "{}",
                createdAt);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
