package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-filesystem companion to {@link FsArchivalMemoryStoreTest}.
 *
 * <p>{@code FsArchivalMemoryStoreTest} drives the adapter through a mocked {@link FilesystemFileStore}, so
 * the chunk-type and text filters, the two-key result ordering, the {@code limit} arm, the
 * {@code evictLowConfidence} sweep and every multi-file walk in {@code getById} / {@code delete} /
 * {@code incrementAccessCount} stay unexecuted — a stubbed store returns one canned file for every call, so
 * no loop ever advances. This class binds the adapter to a real store rooted in a temp directory, which is
 * the only way those arms are genuinely reached. The spec deliberately mirrors
 * {@code FsArchivalMemoryStoreTest} (see the repo note on cross-backend contract suites: there is no
 * test-jar infrastructure, so the spec is mirrored per test class by convention rather than shared).
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsArchivalMemoryStoreDeepTest {

    private Path tempDir;
    private FsArchivalMemoryStore archivalStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-archival-deep-");
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

        archivalStore = new FsArchivalMemoryStore();
        archivalStore.store = fileStore;
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
    void upsertReplacesAChunkWithTheSameId() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("c1", "u1", "first", "FACT", 0.5, now));
        archivalStore.upsert(chunk("c1", "u1", "second", "FACT", 0.5, now));

        List<ArchivalChunkDto> chunks = search("u1", null, 0, Set.of());
        assertEquals(1, chunks.size(), "an upsert on the same chunk id replaces rather than appends");
        assertEquals("second", chunks.getFirst().content());
    }

    @Test
    void searchFiltersByChunkTypeAndText() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("c1", "u1", "java is fast", "FACT", 0.9, now.minusSeconds(30)));
        archivalStore.upsert(chunk("c2", "u1", "java is old", "HISTORY", 0.8, now.minusSeconds(20)));
        archivalStore.upsert(chunk("c3", "u1", "rust is fast", "FACT", 0.7, now.minusSeconds(10)));

        assertEquals(List.of("c1", "c2", "c3"), ids(search("u1", null, 0, Set.of())));
        assertEquals(List.of("c1", "c3"), ids(search("u1", null, 0, Set.of("FACT"))), "the type filter skips c2");
        assertEquals(List.of("c1", "c2"), ids(search("u1", "java", 0, Set.of())), "the text filter skips c3");
        assertEquals(List.of("c1"), ids(search("u1", "java", 0, Set.of("FACT"))), "both filters compose");
        assertTrue(search("u1", "absent", 0, Set.of()).isEmpty());
    }

    @Test
    void searchOrdersByConfidenceThenRecencyAndHonoursTheLimit() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("lower", "u2", "same", "FACT", 0.4, now));
        archivalStore.upsert(chunk("higher", "u2", "same", "FACT", 0.9, now.minusSeconds(60)));

        assertEquals(List.of("higher", "lower"), ids(search("u2", null, 0, Set.of())), "confidence outranks recency");

        archivalStore.upsert(chunk("tie-old", "u3", "same", "FACT", 0.5, now.minusSeconds(60)));
        archivalStore.upsert(chunk("tie-new", "u3", "same", "FACT", 0.5, now));

        assertEquals(
                List.of("tie-new", "tie-old"),
                ids(search("u3", null, 0, Set.of())),
                "an equal-confidence tie is broken by createdAt descending");
        assertEquals(List.of("tie-new"), ids(search("u3", null, 1, Set.of())), "the limit truncates");
    }

    /**
     * The comparator's {@code createdAt} tie-break is null-guarded. That guard is genuinely reachable:
     * {@link ArchivalChunkDto} is a plain record with no compact constructor, so nothing in the port
     * contract forbids a null timestamp.
     */
    @Test
    void searchTreatsUndatedChunksAsEqualWhenConfidenceTies() {
        archivalStore.upsert(chunk("undated", "u4", "same", "FACT", 0.5, null));
        archivalStore.upsert(chunk("dated", "u4", "same", "FACT", 0.5, Instant.now()));

        assertEquals(2, search("u4", null, 0, Set.of()).size(), "a null timestamp must not break the sort");
    }

    @Test
    void getByIdScansEveryUserAndReportsMisses() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("c1", "u1", "a", "FACT", 0.5, now));
        archivalStore.upsert(chunk("c1b", "u1", "b", "FACT", 0.5, now));
        archivalStore.upsert(chunk("c2", "u2", "c", "FACT", 0.5, now));

        assertEquals("c2", archivalStore.getById("c2").orElseThrow().chunkId(), "found in the second file");
        assertEquals(
                "c1b",
                archivalStore.getById("c1b").orElseThrow().chunkId(),
                "found after the inner loop skips a non-matching chunk");
        assertTrue(archivalStore.getById("no-such-chunk").isEmpty());
    }

    @Test
    void incrementAccessCountBumpsOnlyTheMatchingChunk() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("c1", "u1", "a", "FACT", 0.5, now));
        archivalStore.upsert(chunk("c2", "u1", "b", "FACT", 0.5, now));

        archivalStore.incrementAccessCount("c2");

        assertEquals(1, byId("c2").accessCount());
        assertEquals(0, byId("c1").accessCount(), "the sibling chunk is untouched");
    }

    @Test
    void incrementAccessCountSkipsFilesThatDoNotContainTheChunk() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("c1", "u1", "a", "FACT", 0.5, now));
        archivalStore.upsert(chunk("c2", "u2", "b", "FACT", 0.5, now));

        archivalStore.incrementAccessCount("c2");

        assertEquals(1, byId("c2").accessCount());
        assertEquals(0, byId("c1").accessCount(), "the file that does not hold the chunk is left alone");
    }

    @Test
    void incrementAccessCountIsANoOpForAnUnknownChunk() {
        archivalStore.upsert(chunk("c1", "u1", "a", "FACT", 0.5, Instant.now()));

        archivalStore.incrementAccessCount("no-such-chunk");

        assertEquals(0, byId("c1").accessCount());
    }

    @Test
    void incrementAccessCountsIgnoresNullOrEmptyInput() {
        archivalStore.upsert(chunk("c1", "u1", "a", "FACT", 0.5, Instant.now()));

        archivalStore.incrementAccessCounts(null);
        archivalStore.incrementAccessCounts(List.of());

        assertEquals(0, byId("c1").accessCount());
    }

    @Test
    void deleteRemovesTheChunkAndSkipsFilesWithoutIt() {
        Instant now = Instant.now();
        archivalStore.upsert(chunk("c1", "u1", "a", "FACT", 0.5, now));
        archivalStore.upsert(chunk("c2", "u2", "b", "FACT", 0.5, now));

        archivalStore.delete("c2");

        assertTrue(archivalStore.getById("c2").isEmpty());
        assertEquals("c1", archivalStore.getById("c1").orElseThrow().chunkId(), "the other user is untouched");

        archivalStore.delete("no-such-chunk");
        assertTrue(archivalStore.getById("c1").isPresent(), "a delete that matches nothing rewrites nothing");
    }

    @Test
    void evictLowConfidenceRemovesOnlyOldLowConfidenceChunks() {
        Instant now = Instant.now();
        Instant old = now.minus(40, ChronoUnit.DAYS);
        archivalStore.upsert(chunk("low-old", "u1", "x", "FACT", 0.2, old));
        archivalStore.upsert(chunk("low-new", "u1", "x", "FACT", 0.2, now));
        archivalStore.upsert(chunk("high-old", "u1", "x", "FACT", 0.9, old));
        archivalStore.upsert(chunk("low-old-2", "u2", "x", "FACT", 0.1, old));
        archivalStore.upsert(chunk("safe", "u3", "x", "FACT", 0.9, now));
        archivalStore.upsert(chunk("undated-low", "u4", "x", "FACT", 0.1, null));

        archivalStore.evictLowConfidence(0.5, 30);

        assertEquals(
                Set.of("low-new", "high-old"),
                Set.copyOf(ids(search("u1", null, 0, Set.of()))),
                "only the old + low-confidence chunk is evicted");
        assertTrue(search("u2", null, 0, Set.of()).isEmpty(), "the sweep is not scoped to one user");
        assertEquals(1, search("u3", null, 0, Set.of()).size(), "a file with nothing to evict is left alone");
        assertEquals(
                List.of("undated-low"),
                ids(search("u4", null, 0, Set.of())),
                "an undated chunk cannot be proven old, so it survives");
    }

    // ─── Helpers ───────────────────────────────────────────────

    private List<ArchivalChunkDto> search(String userId, String query, int limit, Set<String> chunkTypes) {
        return archivalStore.search(new ArchivalSearchQuery(userId, query, limit, chunkTypes, false, true, null));
    }

    private ArchivalChunkDto byId(String chunkId) {
        return archivalStore.getById(chunkId).orElseThrow();
    }

    private static List<String> ids(List<ArchivalChunkDto> chunks) {
        return chunks.stream().map(ArchivalChunkDto::chunkId).toList();
    }

    private static ArchivalChunkDto chunk(
            String chunkId, String userId, String content, String chunkType, double confidence, Instant createdAt) {
        return new ArchivalChunkDto(
                chunkId, userId, content, chunkType, "sess-1", "msg-1", "tag1", confidence, 0, createdAt, createdAt);
    }
}
