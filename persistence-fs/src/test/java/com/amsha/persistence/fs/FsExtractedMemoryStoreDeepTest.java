package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-filesystem companion to {@link FsExtractedMemoryStoreTest}.
 *
 * <p>{@code FsExtractedMemoryStoreTest} drives the adapter through a mocked {@link FilesystemFileStore},
 * so the dedup pass over <em>already persisted</em> records, every {@code limit} arm, the whole
 * {@code getBySession} / {@code getByType} / {@code purgeExpired} surface and the real
 * {@code store.list(...)} sweep never execute — a stubbed store returns one canned value for every
 * call, which short-circuits the streams before their lambdas run. This class binds the adapter to a
 * real store rooted in a temp directory, which is the only way those arms are genuinely reached. The
 * spec deliberately mirrors {@code FsExtractedMemoryStoreTest} (see the repo note on cross-backend
 * contract suites: there is no test-jar infrastructure, so the spec is mirrored per test class by
 * convention rather than shared).
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsExtractedMemoryStoreDeepTest {

    private Path tempDir;
    private FsExtractedMemoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-extracted-deep-");
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

        store = new FsExtractedMemoryStore();
        store.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void insertBatchRejectsNullOrEmptyInput() {
        assertEquals(0, store.insertBatch(null));
        assertEquals(0, store.insertBatch(List.of()));

        assertTrue(store.getBySession("s1", 0).isEmpty());
    }

    @Test
    void insertBatchDedupesAgainstWhatIsAlreadyStored() {
        Instant now = Instant.now();
        assertEquals(
                2,
                store.insertBatch(List.of(
                        mem("m1", "s1", "u1", MemoryRecordType.FACT, "a", 0.5, "h1", now),
                        mem("m2", "s1", "u1", MemoryRecordType.FACT, "b", 0.5, "h2", now))));

        assertEquals(
                1,
                store.insertBatch(List.of(
                        mem("m1-dup", "s1", "u1", MemoryRecordType.FACT, "a again", 0.5, "h1", now),
                        mem("m3", "s1", "u1", MemoryRecordType.FACT, "c", 0.5, "h3", now))),
                "only the record with an unseen content hash is persisted");

        List<String> persisted = ids(store.getBySession("s1", 0));
        assertEquals(3, persisted.size());
        assertTrue(persisted.contains("m3"));
        assertFalse(persisted.contains("m1-dup"));
    }

    @Test
    void getBySessionReturnsNewestFirstAndHonoursTheLimit() {
        Instant now = Instant.now();
        store.insertBatch(List.of(
                mem("m1", "s1", "u1", MemoryRecordType.FACT, "one", 0.5, "h1", now.minusSeconds(30)),
                mem("m2", "s1", "u1", MemoryRecordType.FACT, "two", 0.6, "h2", now.minusSeconds(20)),
                mem("m3", "s1", "u1", MemoryRecordType.FACT, "three", 0.7, "h3", now.minusSeconds(10))));

        assertEquals(List.of("m3", "m2", "m1"), ids(store.getBySession("s1", 0)), "newest first");
        assertEquals(List.of("m3", "m2"), ids(store.getBySession("s1", 2)), "the limit truncates");
        assertTrue(store.getBySession("no-such-session", 0).isEmpty());
    }

    @Test
    void getByUserFiltersByOwnerAndHonoursTheLimit() {
        Instant now = Instant.now();
        store.insertBatch(List.of(mem("m1", "s1", "u1", MemoryRecordType.FACT, "a", 0.5, "h1", now.minusSeconds(30))));
        store.insertBatch(List.of(mem("m2", "s2", "u1", MemoryRecordType.FACT, "b", 0.5, "h2", now.minusSeconds(20))));
        store.insertBatch(List.of(mem("m3", "s3", "u2", MemoryRecordType.FACT, "c", 0.5, "h3", now.minusSeconds(10))));

        assertEquals(List.of("m2", "m1"), ids(store.getByUser("u1", 0)), "scans every session file");
        assertEquals(1, store.getByUser("u1", 1).size(), "the limit truncates");
        assertTrue(store.getByUser("nobody", 0).isEmpty());
    }

    @Test
    void getByTypeFiltersAndOrdersByConfidence() {
        Instant now = Instant.now();
        store.insertBatch(List.of(
                mem("m1", "s1", "u1", MemoryRecordType.FACT, "a", 0.5, "h1", now.minusSeconds(30)),
                mem("m2", "s1", "u1", MemoryRecordType.USER_PREFERENCE, "b", 0.9, "h2", now.minusSeconds(20)),
                mem("m3", "s1", "u1", MemoryRecordType.FACT, "c", 0.8, "h3", now.minusSeconds(10))));

        assertEquals(
                List.of("m3", "m1"),
                ids(store.getByType(MemoryRecordType.FACT, 0)),
                "ordered by confidence, not by time");
        assertEquals(1, store.getByType(MemoryRecordType.FACT, 1).size(), "the limit truncates");
        assertEquals(1, store.getByType(MemoryRecordType.USER_PREFERENCE, 0).size());
        assertTrue(store.getByType(MemoryRecordType.WORKAROUND, 0).isEmpty());
    }

    @Test
    void listUserIdsIsDeduplicatedSortedAndLimited() {
        Instant now = Instant.now();
        store.insertBatch(List.of(mem("m1", "s1", "u2", MemoryRecordType.FACT, "a", 0.5, "h1", now)));
        store.insertBatch(List.of(mem("m2", "s2", "u1", MemoryRecordType.FACT, "b", 0.5, "h2", now)));
        store.insertBatch(List.of(mem("m3", "s3", "u2", MemoryRecordType.FACT, "c", 0.5, "h3", now)));

        assertEquals(List.of("u1", "u2"), store.listUserIds(0), "deduplicated and sorted");
        assertEquals(List.of("u1"), store.listUserIds(1), "the limit truncates");
    }

    @Test
    void existsByHashChecksOnlyTheGivenSession() {
        store.insertBatch(List.of(mem("m1", "s1", "u1", MemoryRecordType.FACT, "a", 0.5, "h1", Instant.now())));

        assertTrue(store.existsByHash("s1", "h1"));
        assertFalse(store.existsByHash("s1", "h9"));
        assertFalse(store.existsByHash("s2", "h1"), "the hash is only looked up inside the session");
    }

    @Test
    void purgeExpiredRemovesOldRecordsAndReportsTheCount() {
        Instant now = Instant.now();
        Instant old = now.minus(31, ChronoUnit.DAYS);
        store.insertBatch(List.of(
                mem("m1", "s1", "u1", MemoryRecordType.FACT, "old", 0.5, "h1", old),
                mem("m2", "s1", "u1", MemoryRecordType.FACT, "fresh", 0.5, "h2", now)));
        store.insertBatch(List.of(mem("m3", "s2", "u1", MemoryRecordType.FACT, "fresh", 0.5, "h3", now)));

        assertEquals(1, store.purgeExpired(30), "only the expired record is counted");
        assertEquals(List.of("m2"), ids(store.getBySession("s1", 0)), "the surviving record is rewritten");
        assertEquals(1, store.getBySession("s2", 0).size(), "a file with nothing to purge is left alone");
        assertEquals(0, store.purgeExpired(30), "a second sweep finds nothing");
    }

    // ─── Helpers ───────────────────────────────────────────────

    private static List<String> ids(List<ExtractedMemory> memories) {
        return memories.stream().map(ExtractedMemory::id).toList();
    }

    private static ExtractedMemory mem(
            String id,
            String sessionId,
            String userId,
            MemoryRecordType recordType,
            String content,
            double confidence,
            String contentHash,
            Instant createdAt) {
        return new ExtractedMemory(
                id, sessionId, userId, recordType, content, confidence, contentHash, "src", createdAt);
    }
}
