package com.arcclaw.persistence.fs.lucene;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LuceneIndexManagerTest {

    private Path tempDir;
    private LuceneIndexManager index;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("lucene-test-");
        index = new LuceneIndexManager(tempDir, 4);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (index != null) index.close();
        if (tempDir != null) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException _) { }
                });
            }
        }
    }

    @Test
    void indexAndSearchBM25() {
        index.index("1", "hello world", null, null);
        index.index("2", "goodbye world", null, null);

        var results = index.searchBM25("hello", 10);
        assertEquals(1, results.size());
        assertEquals("1", results.getFirst().id());
        assertTrue(results.getFirst().score() > 0);
    }

    @Test
    void searchBM25ReturnsMultiple() {
        index.index("a", "the quick brown fox", null, null);
        index.index("b", "jumps over the lazy dog", null, null);
        index.index("c", "the fox is quick and brown", null, null);

        var results = index.searchBM25("quick fox", 10);
        assertEquals(2, results.size());
    }

    @Test
    void searchBM25NoMatch() {
        index.index("1", "hello world", null, null);

        var results = index.searchBM25("zzzzz", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchBM25RespectsTopK() {
        index.index("a", "apple banana cherry", null, null);
        index.index("b", "banana cherry date", null, null);
        index.index("c", "cherry date elderberry", null, null);

        var results = index.searchBM25("banana", 1);
        assertEquals(1, results.size());
    }

    @Test
    void indexWithMetadataStoredAsJson() {
        index.index("meta1", "content with metadata", "{\"source\":\"test\",\"type\":\"note\"}", null);

        var results = index.searchBM25("metadata", 10);
        assertEquals(1, results.size());
        assertEquals("content with metadata", results.getFirst().content());
    }

    @Test
    void deleteDocument() {
        index.index("del1", "delete me", null, null);
        assertEquals(1, index.searchBM25("delete", 10).size());

        index.delete("del1");
        assertEquals(0, index.searchBM25("delete", 10).size());
    }

    @Test
    void reindexUpdatesDocument() {
        index.index("upd1", "original content", null, null);
        assertEquals(1, index.searchBM25("original", 10).size());
        assertEquals(0, index.searchBM25("updated", 10).size());

        index.index("upd1", "updated content", null, null);
        assertEquals(0, index.searchBM25("original", 10).size());
        assertEquals(1, index.searchBM25("updated", 10).size());
    }

    @Test
    void numDocs() {
        assertEquals(0, index.numDocs());
        index.index("n1", "doc one", null, null);
        assertEquals(1, index.numDocs());
        index.index("n2", "doc two", null, null);
        assertEquals(2, index.numDocs());
        index.delete("n1");
        assertEquals(1, index.numDocs());
    }

    @Test
    void searchWithEmptyQueryReturnsEmpty() {
        index.index("e1", "something", null, null);
        assertTrue(index.searchBM25("", 10).isEmpty());
        assertTrue(index.searchBM25("   ", 10).isEmpty());
        assertTrue(index.searchBM25(null, 10).isEmpty());
    }

    @Test
    void nullContentIsNormalized() {
        index.index("null1", null, null, null);
        assertEquals(1, index.numDocs());
    }

    @Test
    void vectorSearch() {
        float[] vecA = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] vecB = {0.0f, 1.0f, 0.0f, 0.0f};
        float[] vecC = {0.0f, 0.0f, 1.0f, 0.0f};

        index.index("v1", "vector A", null, vecA);
        index.index("v2", "vector B", null, vecB);
        index.index("v3", "vector C", null, vecC);

        float[] query = {0.9f, 0.1f, 0.0f, 0.0f};
        var results = index.searchVector(query, 3);
        assertEquals(3, results.size());
        assertEquals("v1", results.getFirst().id());
    }

    @Test
    void vectorSearchRespectsTopK() {
        float[] vecA = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] vecB = {0.0f, 1.0f, 0.0f, 0.0f};
        index.index("v1", "first", null, vecA);
        index.index("v2", "second", null, vecB);

        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        var results = index.searchVector(query, 1);
        assertEquals(1, results.size());
        assertEquals("v1", results.getFirst().id());
    }

    @Test
    void hybridSearch() {
        float[] vecA = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] vecB = {0.5f, 0.5f, 0.0f, 0.0f};

        index.index("h1", "python programming language", null, vecA);
        index.index("h2", "java programming language and python", null, vecB);

        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        var results = index.searchHybrid("python", query, 5);
        assertEquals(2, results.size());
    }

    @Test
    void indexIsPersistedOnDisk() throws IOException {
        index.index("p1", "persistent data", null, null);
        index.close();

        try (var reopened = new LuceneIndexManager(tempDir, 4)) {
            var results = reopened.searchBM25("persistent", 10);
            assertEquals(1, results.size());
            assertEquals("p1", results.getFirst().id());
        }
    }

    @Test
    void querySpecialCharsAreEscaped() {
        index.index("s1", "hello world foo bar", null, null);

        var results = index.searchBM25("foo AND bar", 10);
        assertEquals(1, results.size());

        results = index.searchBM25("hello OR world", 10);
        assertEquals(1, results.size());

        results = index.searchBM25("hello AND (world OR missing)", 10);
        assertEquals(1, results.size());
    }
}
