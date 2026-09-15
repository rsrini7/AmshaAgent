package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
import java.util.Optional;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.IngestResult;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryEntryDto;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.fs.lucene.LuceneIndexManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Companion to {@link FsMemoryRetrievalAdapterTest}, which covers the search/ingest happy paths.
 *
 * <p>What it leaves unexecuted: the absolute-index-directory branch of {@code init}, {@code destroy}'s
 * failure handling, the JSON→Lucene rebuild loop (the existing rebuild test never has both an empty Lucene
 * index <em>and</em> persisted JSON entries at the same time, so the loop body never runs), the descriptor
 * dimension-mismatch arm of {@code configureVectorCompatibility}, the mid-search vector degradation, and
 * {@code serializeMetadata}'s failure arm.
 */
@SuppressWarnings({"java:S5443", "unchecked"}) // Test-only temp dirs and generic Instance mocks.
class FsMemoryRetrievalAdapterDeepTest {

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsMemoryRetrievalAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-retrieval-deep-");
        fileStore = new FilesystemFileStore(props("lucene-index"));
        fileStore.ensureDataDir();

        adapter = newAdapter();
    }

    @AfterEach
    void tearDown() throws IOException {
        closeQuietly(adapter);
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
    void anAbsoluteLuceneIndexDirIsUsedVerbatim() throws Exception {
        Path absolute = tempDir.resolve("absolute-lucene");
        setField(adapter, "properties", props(absolute.toString()));

        adapter.init();

        assertTrue(Files.isDirectory(absolute), "the index is created under the absolute directory");
        assertFalse(Files.exists(tempDir.resolve("lucene-index")), "the relative default is not used");

        adapter.index(new MemoryEntryDto("a1", "absolute index", Map.of()));
        assertEquals(1, adapter.search("absolute", 10, null).size());
    }

    @Test
    void destroySwallowsACloseFailure() throws Exception {
        LuceneIndexManager failing = mock(LuceneIndexManager.class);
        doThrow(new IllegalStateException("already closed")).when(failing).close();
        setField(adapter, "lucene", failing);

        adapter.destroy();

        verify(failing).close();
    }

    @Test
    void destroyIsANoOpWhenInitNeverRan() {
        // `lucene` is still null — destroy() must not NPE.
        adapter.destroy();
    }

    @Test
    void initRebuildsTheLuceneIndexFromPersistedJsonWhenTheIndexIsEmpty() throws Exception {
        adapter.init();
        adapter.index(new MemoryEntryDto("r1", "rebuild me", Map.of("source", "test")));
        adapter.index(new MemoryEntryDto("r2", "rebuild me too", Map.of()));
        adapter.destroy();

        // Wipe only the Lucene directory: the JSON domain survives, so the next init() must rebuild.
        deleteRecursively(tempDir.resolve("lucene-index"));

        FsMemoryRetrievalAdapter restarted = newAdapter();
        try {
            restarted.init();

            assertEquals(2, restarted.search("rebuild", 10, null).size(), "both entries were re-indexed");
        } finally {
            closeQuietly(restarted);
        }
    }

    @Test
    void aDescriptorDimensionMismatchDisablesVectorSearch() throws Exception {
        enableVectorIndex(adapter, descriptor(8)); // descriptor says 8, the store is configured for 4

        adapter.init();

        assertFalse(
                adapter.capabilities().supportsVectorSearch(),
                "a descriptor whose dimension disagrees with the configured one disables vectors");
        adapter.index(new MemoryEntryDto("m1", "dimension mismatch", Map.of()));
        assertEquals(1, adapter.search("dimension", 10, null).size(), "BM25 still answers");
    }

    @Test
    void anUnavailableActiveDescriptorDisablesVectorSearch() throws Exception {
        EmbeddingIndexDescriptorSource emptySource = Optional::empty;
        Instance<EmbeddingIndexDescriptorSource> sourceInstance = mock(Instance.class);
        when(sourceInstance.isUnsatisfied()).thenReturn(false);
        when(sourceInstance.isAmbiguous()).thenReturn(false);
        when(sourceInstance.get()).thenReturn(emptySource);
        setField(adapter, "descriptorSources", sourceInstance);
        setField(adapter, "vectorIndexMetadata", mock(VectorIndexMetadataPort.class));

        adapter.init();

        assertFalse(adapter.capabilities().supportsVectorSearch());
    }

    @Test
    void anAmbiguousDescriptorSourceDisablesVectorSearch() throws Exception {
        Instance<EmbeddingIndexDescriptorSource> sourceInstance = mock(Instance.class);
        when(sourceInstance.isUnsatisfied()).thenReturn(false);
        when(sourceInstance.isAmbiguous()).thenReturn(true);
        setField(adapter, "descriptorSources", sourceInstance);
        setField(adapter, "vectorIndexMetadata", mock(VectorIndexMetadataPort.class));

        adapter.init();

        assertFalse(adapter.capabilities().supportsVectorSearch());
    }

    /**
     * The first embed call (during {@code index}) returns a valid vector, the second (during {@code search})
     * returns an empty one. {@code validateDimension} runs before its {@code dimensionValidated} short-circuit,
     * so the empty query vector disables vector search mid-search and the request falls through to BM25.
     */
    @Test
    void anEmptyQueryVectorDisablesVectorSearchMidSearch() throws Exception {
        // enableVectorIndex installs its own placeholder model, so the stateful one must be injected after it.
        enableVectorIndex(adapter, descriptor(4));

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[] {0.1f, 0.1f, 0.1f, 0.1f})))
                .thenReturn(Response.from(new Embedding(new float[0])));
        Instance<EmbeddingModel> modelInstance = mock(Instance.class);
        when(modelInstance.isUnsatisfied()).thenReturn(false);
        when(modelInstance.get()).thenReturn(model);
        setField(adapter, "embeddingModel", modelInstance);

        adapter.init();
        adapter.index(new MemoryEntryDto("q1", "mid search degrade", Map.of()));
        assertTrue(adapter.capabilities().supportsVectorSearch(), "still enabled before the search");

        List<MemoryChunkDto> results = adapter.search("mid", 10, null);

        assertEquals(1, results.size(), "the BM25 fallback still answers");
        assertFalse(adapter.capabilities().supportsVectorSearch(), "the empty query vector disabled vectors");
    }

    @Test
    void serializeMetadataDegradesToNullWhenSerializationFails() throws Exception {
        ObjectMapper throwing = mock(ObjectMapper.class);
        when(throwing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        setField(adapter, "objectMapper", throwing);

        adapter.init();

        IngestResult result = adapter.index(new MemoryEntryDto("x1", "metadata failure", Map.of("k", "v")));

        assertEquals("x1", result.id(), "an unserializable metadata map must not fail the ingest");
        assertEquals(1, adapter.search("metadata", 10, null).size(), "the entry is still searchable");
    }

    @Test
    void indexGeneratesAnIdWhenTheEntryHasNone() {
        adapter.init();

        IngestResult result = adapter.index(new MemoryEntryDto(null, "generated id", Map.of()));

        assertNotNull(result.id());
        assertEquals(1, adapter.search("generated", 10, null).size());
    }

    // ─── Helpers ───────────────────────────────────────────────

    private FsMemoryRetrievalAdapter newAdapter() throws Exception {
        FsMemoryRetrievalAdapter fresh = new FsMemoryRetrievalAdapter();
        setField(fresh, "store", fileStore);
        setField(fresh, "properties", props("lucene-index"));
        setField(fresh, "objectMapper", fileStore.objectMapper());
        return fresh;
    }

    private void enableVectorIndex(FsMemoryRetrievalAdapter target, EmbeddingIndexDescriptor descriptor)
            throws Exception {
        EmbeddingModel model = new FakeEmbeddingModel(descriptor.vectorDimension());
        Instance<EmbeddingModel> modelInstance = mock(Instance.class);
        when(modelInstance.isUnsatisfied()).thenReturn(false);
        when(modelInstance.get()).thenReturn(model);
        setField(target, "embeddingModel", modelInstance);

        EmbeddingIndexDescriptorSource source = () -> Optional.of(descriptor);
        Instance<EmbeddingIndexDescriptorSource> sourceInstance = mock(Instance.class);
        when(sourceInstance.isUnsatisfied()).thenReturn(false);
        when(sourceInstance.isAmbiguous()).thenReturn(false);
        when(sourceInstance.get()).thenReturn(source);

        VectorIndexMetadataPort metadata = mock(VectorIndexMetadataPort.class);
        when(metadata.verifyOrInitialize(eq(VectorIndexNamespace.RAG_GLOBAL), eq(descriptor), anyBoolean()))
                .thenReturn(new VectorIndexCompatibility(
                        VectorIndexNamespace.RAG_GLOBAL,
                        VectorIndexCompatibility.Status.MATCH,
                        descriptor.fingerprint(),
                        Optional.of(descriptor.fingerprint())));
        when(metadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL)).thenReturn(true);

        setField(target, "descriptorSources", sourceInstance);
        setField(target, "vectorIndexMetadata", metadata);
    }

    private void closeQuietly(FsMemoryRetrievalAdapter target) {
        if (target == null) return;
        try {
            Field field = findField(FsMemoryRetrievalAdapter.class, "lucene");
            field.setAccessible(true);
            Object lucene = field.get(target);
            if (lucene instanceof LuceneIndexManager manager) manager.close();
        } catch (Exception e) {
            // best-effort
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort
                }
            });
        }
    }

    private FsStoreProperties props(String luceneIndexDir) {
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
                return luceneIndexDir;
            }

            @Override
            public int vectorDimension() {
                return 4;
            }
        };
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), fieldName);
            throw e;
        }
    }

    private static EmbeddingIndexDescriptor descriptor(int dimension) {
        return EmbeddingIndexDescriptor.create(
                "test",
                "test-space",
                "revision-1",
                "tokenizer-1",
                "preprocessing-1",
                "MASKED_MEAN",
                true,
                dimension,
                "FLOAT32",
                "FIRST_MAX_TOKENS",
                "v1",
                512,
                0,
                "DIRECT");
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
