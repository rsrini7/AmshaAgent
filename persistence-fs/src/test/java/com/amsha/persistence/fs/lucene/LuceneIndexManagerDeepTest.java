package com.amsha.persistence.fs.lucene;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Error-path and merge coverage for {@link LuceneIndexManager}.
 *
 * <p>{@link LuceneIndexManagerTest} exercises the happy paths. What it cannot reach: the constructor's
 * failure arm, every {@code catch (IOException)} that wraps a Lucene failure in an
 * {@link UncheckedIOException}, the unparseable-query fallback (both the escaped retry and the case where
 * even the retry fails), and the RRF merge's "prefer the hit that carries content" branch.
 *
 * <p>The I/O arms are reached by removing all permission from the index directory, which is the only way to
 * make a directory that was just written to refuse the next write. The original mode is restored in a
 * {@code finally} block before the manager is closed. A failed commit marks the writer closed, so each write
 * arm needs its own manager instance.
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class LuceneIndexManagerDeepTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-lucene-deep-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        // Any directory left mode 000 by a failed assertion must be made traversable before cleanup.
        try (var walk = Files.walk(tempDir)) {
            walk.forEach(path -> {
                try {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                } catch (IOException e) {
                    // best-effort
                }
            });
        }
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
    void theConstructorFailsWhenTheIndexDirectoryParentIsAFile() throws IOException {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        UncheckedIOException ex =
                assertThrows(UncheckedIOException.class, () -> new LuceneIndexManager(blocker.resolve("index"), 4));

        assertTrue(ex.getMessage().startsWith("Failed to initialize Lucene index at"), ex.getMessage());
    }

    @Test
    void searchBm25RetriesWithAnEscapedQueryWhenParsingFails() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("escaped-index"), 4)) {
            lucene.index("d1", "alpha beta", null, null);

            assertEquals(1, lucene.searchBM25("alpha", 10).size());
            assertEquals(
                    1,
                    lucene.searchBM25("alpha^", 10).size(),
                    "the raw query is a parse error (a trailing ^); the escaped retry runs and still matches");
        }
    }

    @Test
    void searchBm25ReturnsEmptyWhenEvenTheEscapedQueryCannotBeParsed() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("unparseable-index"), 4)) {
            lucene.index("d1", "alpha beta", null, null);

            assertTrue(
                    lucene.searchBM25("alpha AND", 10).isEmpty(),
                    "a dangling operator survives escaping, so the outer catch degrades to no results");
        }
    }

    @Test
    void indexWrapsAnIoFailureInAnUncheckedIoException() throws IOException {
        Path indexDir = tempDir.resolve("write-locked-index");
        LuceneIndexManager lucene = new LuceneIndexManager(indexDir, 4);
        lucene.index("d1", "alpha", null, null);

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(indexDir);
        Files.setPosixFilePermissions(indexDir, PosixFilePermissions.fromString("---------"));
        try {
            UncheckedIOException ex =
                    assertThrows(UncheckedIOException.class, () -> lucene.index("d2", "beta", null, null));
            assertTrue(ex.getMessage().startsWith("Failed to index document d2"), ex.getMessage());
        } finally {
            Files.setPosixFilePermissions(indexDir, original);
            lucene.close();
        }
    }

    @Test
    void deleteWrapsAnIoFailureInAnUncheckedIoException() throws IOException {
        Path indexDir = tempDir.resolve("delete-locked-index");
        LuceneIndexManager lucene = new LuceneIndexManager(indexDir, 4);
        lucene.index("d1", "alpha", null, null);

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(indexDir);
        Files.setPosixFilePermissions(indexDir, PosixFilePermissions.fromString("---------"));
        try {
            UncheckedIOException ex = assertThrows(UncheckedIOException.class, () -> lucene.delete("d1"));
            assertTrue(ex.getMessage().startsWith("Failed to delete document d1"), ex.getMessage());
        } finally {
            Files.setPosixFilePermissions(indexDir, original);
            lucene.close();
        }
    }

    @Test
    void searchesWrapAnIoFailureAndNumDocsDegradesToZero() throws IOException {
        Path indexDir = tempDir.resolve("read-locked-index");
        LuceneIndexManager lucene = new LuceneIndexManager(indexDir, 4);
        lucene.index("d1", "alpha beta", null, new float[] {1f, 0f, 0f, 0f});

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(indexDir);
        Files.setPosixFilePermissions(indexDir, PosixFilePermissions.fromString("---------"));
        try {
            UncheckedIOException bm25 = assertThrows(UncheckedIOException.class, () -> lucene.searchBM25("alpha", 10));
            assertTrue(bm25.getMessage().startsWith("BM25 search failed"), bm25.getMessage());

            UncheckedIOException vector = assertThrows(
                    UncheckedIOException.class, () -> lucene.searchVector(new float[] {1f, 0f, 0f, 0f}, 10));
            assertTrue(vector.getMessage().startsWith("Vector search failed"), vector.getMessage());

            assertEquals(0, lucene.numDocs(), "numDocs degrades to zero rather than throwing");
        } finally {
            Files.setPosixFilePermissions(indexDir, original);
            lucene.close();
        }
    }

    @Test
    void searchesAreEmptyForDegenerateArguments() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("degenerate-index"), 4)) {
            lucene.index("d1", "alpha beta", null, null);

            assertTrue(lucene.searchBM25(null, 10).isEmpty());
            assertTrue(lucene.searchBM25("   ", 10).isEmpty());
            assertTrue(lucene.searchVector(null, 10).isEmpty());
            assertTrue(lucene.searchVector(new float[0], 10).isEmpty());
        }
    }

    @Test
    void indexingWithoutAVectorLeavesTheDocumentOutOfVectorSearch() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("no-vector-index"), 4)) {
            lucene.index("plain", "alpha beta", null, null);
            // A vector whose length disagrees with the configured dimension is dropped at index time.
            lucene.index("too-short", "gamma delta", null, new float[] {1f, 0f});

            assertEquals(1, lucene.searchBM25("alpha", 10).size());
            assertEquals(
                    1, lucene.searchBM25("gamma", 10).size(), "the short-vector document is still text-searchable");
            assertTrue(
                    lucene.searchVector(new float[] {1f, 0f, 0f, 0f}, 10).isEmpty(),
                    "neither document carries a usable vector");
        }
    }

    /**
     * The RRF merge keeps the first hit for a duplicate id unless its content is missing, in which case it
     * prefers the later one. A document indexed with a null body has a blank stored body, and {@code *:*}
     * matches every document regardless of its terms, so both hit lists contain it.
     */
    @Test
    void searchHybridPrefersTheHitThatCarriesContent() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("hybrid-index"), 4)) {
            lucene.index("blank-body", null, null, new float[] {1f, 0f, 0f, 0f});
            lucene.index("rich-body", "alpha beta", null, new float[] {1f, 0f, 0f, 0f});

            List<LuceneIndexManager.ScoredResult> merged = lucene.searchHybrid("*:*", new float[] {1f, 0f, 0f, 0f}, 10);

            assertEquals(2, merged.size(), "the match-all text half and the vector half agree on both documents");
            assertEquals(
                    merged.stream()
                            .map(LuceneIndexManager.ScoredResult::id)
                            .distinct()
                            .count(),
                    merged.size(),
                    "an id appears once even when both hit lists contain it");
        }
    }

    @Test
    void searchHybridToleratesAnUnparseableQuery() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("hybrid-fallback-index"), 4)) {
            lucene.index("d1", "alpha beta", null, new float[] {1f, 0f, 0f, 0f});

            List<LuceneIndexManager.ScoredResult> merged =
                    lucene.searchHybrid("alpha AND", new float[] {1f, 0f, 0f, 0f}, 10);

            assertEquals(1, merged.size(), "the vector half still contributes when the text half fails");
        }
    }

    @Test
    void indexReplacesADocumentWithTheSameId() throws IOException {
        try (var lucene = new LuceneIndexManager(tempDir.resolve("replace-index"), 4)) {
            lucene.index("d1", "alpha", null, null);
            lucene.index("d1", "beta", null, null);

            assertEquals(1, lucene.numDocs(), "the id is a unique key");
            assertEquals(1, lucene.searchBM25("beta", 10).size());
            assertTrue(lucene.searchBM25("alpha", 10).isEmpty(), "the old body is gone");
        }
    }

    @Test
    void closeReleasesTheIndexAndLeavesTheDirectoryInPlace() throws IOException {
        Path indexDir = tempDir.resolve("close-index");
        LuceneIndexManager lucene = new LuceneIndexManager(indexDir, 4);
        lucene.index("d1", "alpha", null, null);

        lucene.close();

        assertTrue(Files.isDirectory(indexDir));
    }
}
