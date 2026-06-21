package com.arcclaw.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import jakarta.enterprise.inject.Instance;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.arcclaw.persistence.api.BackendCapabilities;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.MemoryEntryDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only: temp dirs in test are acceptable
class FsMemoryRetrievalAdapterTest {

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsMemoryRetrievalAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("arcclaw-adapter-test-");
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
                return 4;
            }
        };
        fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();

        adapter = new FsMemoryRetrievalAdapter();
        setField(adapter, "store", fileStore);
        setField(adapter, "properties", props);
        setField(adapter, "objectMapper", fileStore.objectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (adapter != null) {
            try {
                Field luceneField = findField(FsMemoryRetrievalAdapter.class, "lucene");
                luceneField.setAccessible(true);
                Object lucene = luceneField.get(adapter);
                if (lucene != null) {
                    lucene.getClass().getMethod("close").invoke(lucene);
                }
            } catch (Exception _) {
            }
        }
        if (tempDir != null) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException _) {
                    }
                });
            }
        }
    }

    @Test
    void bm25OnlyWhenNoEmbeddingModel() {
        adapter.init();
        adapter.index(new MemoryEntryDto("b1", "apple banana", Map.of()));
        List<MemoryChunkDto> results = adapter.search("banana", 10, null);
        assertEquals(1, results.size());
        BackendCapabilities caps = adapter.capabilities();
        assertTrue(caps.supportsFullTextSearch());
        assertFalse(caps.supportsVectorSearch());
    }

    @Test
    @SuppressWarnings("unchecked")
    void vectorSearchWithEmbeddingModel() throws Exception {
        EmbeddingModel fakeModel = new FakeEmbeddingModel(4);
        Instance<EmbeddingModel> mockInstance = mock(Instance.class);
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(fakeModel);
        setField(adapter, "embeddingModel", mockInstance);

        adapter.init();

        assertTrue(adapter.capabilities().supportsVectorSearch());

        adapter.index(new MemoryEntryDto("v1", "alpha vector", Map.of()));
        adapter.index(new MemoryEntryDto("v2", "beta vector", Map.of()));

        // FakeEmbeddingModel always returns {0.1, 0.1, 0.1, 0.1},
        // which is equally close to both, so both should be returned
        List<MemoryChunkDto> results = adapter.search("something", 10, null);
        assertEquals(2, results.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void vectorSearchDegradesOnDimensionMismatch() throws Exception {
        EmbeddingModel wrongDimModel = new FakeEmbeddingModel(8);
        Instance<EmbeddingModel> mockInstance = mock(Instance.class);
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(wrongDimModel);
        setField(adapter, "embeddingModel", mockInstance);

        adapter.init();
        adapter.index(new MemoryEntryDto("d1", "dimension mismatch test", Map.of()));

        assertFalse(adapter.capabilities().supportsVectorSearch());

        List<MemoryChunkDto> results = adapter.search("dimension", 10, null);
        assertEquals(1, results.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void vectorSearchFallsBackToBM25OnEmbeddingFailure() throws Exception {
        EmbeddingModel failingModel = mock(EmbeddingModel.class);
        when(failingModel.embed(anyString())).thenThrow(new RuntimeException("Embedding API down"));
        when(failingModel.embedAll(anyList())).thenThrow(new RuntimeException("Embedding API down"));
        Instance<EmbeddingModel> mockInstance = mock(Instance.class);
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(failingModel);
        setField(adapter, "embeddingModel", mockInstance);

        adapter.init();

        // Set vectorSearchEnabled manually since we can't autodetect with a mock
        setField(adapter, "vectorSearchEnabled", true);

        adapter.index(new MemoryEntryDto("f1", "fallback to bm25", Map.of()));

        List<MemoryChunkDto> results = adapter.search("fallback", 10, null);
        assertEquals(1, results.size());
        assertEquals("f1", results.getFirst().chunkId());
    }

    @Test
    void indexAndSearchWithMetadata() {
        adapter.init();
        Map<String, String> metadata = Map.of("source", "unit-test", "type", "verification");
        adapter.index(new MemoryEntryDto("m1", "metadata test content", metadata));

        List<MemoryChunkDto> results = adapter.search("metadata", 10, null);
        assertEquals(1, results.size());
        assertEquals("m1", results.getFirst().chunkId());
        assertEquals("metadata test content", results.getFirst().text());
        assertTrue(results.getFirst().score() > 0);
    }

    @Test
    void ingestResultHasId() {
        adapter.init();
        IngestResult result = adapter.index(new MemoryEntryDto("i1", "ingest test", Map.of()));
        assertEquals("i1", result.id());
    }

    @Test
    void rebuildsLuceneIndexFromJsonOnStartup() throws Exception {
        adapter.init();
        adapter.index(new MemoryEntryDto("r1", "rebuild me", Map.of("source", "test")));
        assertEquals(1, adapter.search("rebuild", 10, null).size());
        adapter.destroy();

        FsMemoryRetrievalAdapter restarted = new FsMemoryRetrievalAdapter();
        setField(restarted, "store", fileStore);
        setField(restarted, "properties", tempProps());
        setField(restarted, "objectMapper", fileStore.objectMapper());
        restarted.init();
        assertEquals(1, restarted.search("rebuild", 10, null).size());
        restarted.destroy();
    }

    @Test
    void capabilitiesReportedCorrectly() {
        adapter.init();
        BackendCapabilities caps = adapter.capabilities();
        assertTrue(caps.supportsFullTextSearch());
        assertFalse(caps.supportsAdvisoryLocks());
        assertFalse(caps.supportsNestedTransactions());
    }

    // ─── Helpers ───────────────────────────────────────────

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), fieldName);
            throw e;
        }
    }

    private FsStoreProperties tempProps() {
        return new FsStoreProperties() {
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
                return 4;
            }
        };
    }

    private static class FakeEmbeddingModel implements EmbeddingModel {
        private final int dimension;

        FakeEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Response<Embedding> embed(String text) {
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = 0.1f;
            }
            return Response.from(new Embedding(vector));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> embeddings =
                    textSegments.stream().map(ts -> embed(ts.text()).content()).toList();
            return Response.from(embeddings);
        }
    }
}
