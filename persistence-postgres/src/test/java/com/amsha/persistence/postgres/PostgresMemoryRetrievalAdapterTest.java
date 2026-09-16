package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.IngestResult;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryEntryDto;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.postgres.jdbi.RagChunkJdbiDao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("UnnecessaryAssignment")
class PostgresMemoryRetrievalAdapterTest {

    @Mock
    CompactionStorePort compactionStore;

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    Jdbi jdbi;

    @Mock
    RagChunkJdbiDao ragChunkDao;

    @Mock
    VectorIndexMetadataPort vectorIndexMetadata;

    PostgresMemoryRetrievalAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PostgresMemoryRetrievalAdapter(jdbi, compactionStore, embeddingModel, 400);
    }

    @Test
    void testCapabilitiesAndHelpers() {
        var caps = adapter.capabilities();
        assertTrue(caps.supportsFullTextSearch());
        assertTrue(caps.supportsVectorSearch());
        assertTrue(caps.supportsAdvisoryLocks());
        assertFalse(caps.supportsNestedTransactions());

        assertEquals("[1.0,2.5]", PostgresMemoryRetrievalAdapter.floatArrayToJson(new float[] {1.0f, 2.5f}));
        assertEquals("[]", PostgresMemoryRetrievalAdapter.floatArrayToJson(new float[0]));
    }

    @Test
    void testReciprocalRankFusion() {
        MemoryChunkDto c1 = new MemoryChunkDto("chunk-1", "Text 1", 0.9);
        MemoryChunkDto c2 = new MemoryChunkDto("chunk-2", "Text 2", 0.8);
        MemoryChunkDto c3 = new MemoryChunkDto("chunk-1", "Text 1", 0.7);

        List<MemoryChunkDto> merged = adapter.reciprocalRankFusion(2, List.of(c1, c2), List.of(c3));
        assertEquals(2, merged.size());
        assertEquals("chunk-1", merged.get(0).chunkId());
    }

    @Test
    void reportsTextOnlyCapabilityWhenDescriptorGateDisablesVectors() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);

        var caps = adapter.capabilities();

        assertTrue(caps.supportsFullTextSearch());
        assertFalse(caps.supportsVectorSearch());
    }

    @Test
    void descriptorMismatchFallsBackToFullTextWithoutEmbedding() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);
        when(compactionStore.search("user-1", "matching", 4))
                .thenReturn(
                        List.of(new CompactionSummaryDto("session-1", "matching text", Instant.now(), 1, "user-1")));
        when(jdbi.withExtension(eq(RagChunkJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(ragChunkDao);
        });
        when(ragChunkDao.search("matching", 4)).thenReturn(List.of());

        var results = adapter.search("matching", 2, "user-1");

        assertEquals(1, results.size());
        assertEquals("session-1", results.getFirst().chunkId());
        verifyNoInteractions(embeddingModel);
    }

    // =========================================================================
    // Hybrid search: the lexical and vector arms, and the source-text budget
    // =========================================================================

    /**
     * Runs the consumer the adapter hands to {@code useTransaction} against a handle that attaches the
     * mocked RAG DAO.
     */
    private void givenTransaction() {
        Handle handle = mock(Handle.class);
        when(handle.attach(RagChunkJdbiDao.class)).thenReturn(ragChunkDao);
        doAnswer(invocation -> {
                    HandleConsumer<RuntimeException> consumer = invocation.getArgument(0);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useTransaction(any());
    }

    /** Runs the callback the adapter hands to {@code withExtension} against the mocked RAG DAO. */
    private void givenExtension() {
        when(jdbi.withExtension(eq(RagChunkJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(ragChunkDao);
        });
    }

    /** Runs the consumer the adapter hands to {@code useExtension} against the mocked RAG DAO. */
    private void givenConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(ragChunkDao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(RagChunkJdbiDao.class), any());
    }

    @Test
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Kept for subclasses and tests that need a bare instance; constructing it must not itself
        // touch the database.
        assertNotNull(new PostgresMemoryRetrievalAdapter());
    }

    @Test
    void ragHitsAreRankScoredAndOverlongTextIsCutToTheSourceBudget() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);
        String longSummary = "x".repeat(500);
        when(compactionStore.search("user-1", "q", 6))
                .thenReturn(List.of(
                        new CompactionSummaryDto("session-1", longSummary, Instant.now(), 1, "user-1"),
                        new CompactionSummaryDto("session-2", "short summary", Instant.now(), 1, "user-1")));
        givenExtension();
        when(ragChunkDao.search("q", 6)).thenReturn(List.of(new MemoryChunkDto("chunk-9", "from the rag index", 0.5)));

        List<MemoryChunkDto> results = adapter.search("q", 3, "user-1");

        assertEquals(3, results.size(), "both lexical arms must contribute to the fused list");
        MemoryChunkDto truncated = byId(results, "session-1");
        assertEquals(401, truncated.text().length(), "400 chars plus the ellipsis");
        assertTrue(truncated.text().endsWith("\u2026"));
        assertEquals("short summary", byId(results, "session-2").text());
        assertEquals("from the rag index", byId(results, "chunk-9").text());
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void vectorSearchEmbedsTheQueryAndPassesTheJsonVectorToTheDao() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(embeddingModel.embed("q")).thenReturn(Response.from(Embedding.from(new float[] {1.0f, 2.0f})));
        when(compactionStore.search("user-1", "q", 6)).thenReturn(List.of());
        givenExtension();
        when(ragChunkDao.search("q", 6)).thenReturn(List.of());
        when(ragChunkDao.searchVector("[1.0,2.0]", 6))
                .thenReturn(List.of(new MemoryChunkDto("vec-1", "semantic hit", 0.7)));

        List<MemoryChunkDto> results = adapter.search("q", 3, "user-1");

        assertEquals(1, results.size());
        assertEquals("vec-1", results.getFirst().chunkId());
    }

    @Test
    void aFailedEmbeddingDegradesTheVectorArmToFullTextOnly() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(embeddingModel.embed("q")).thenThrow(new IllegalStateException("model unavailable"));
        when(compactionStore.search("user-1", "q", 4)).thenReturn(List.of());
        givenExtension();
        when(ragChunkDao.search("q", 4)).thenReturn(List.of(new MemoryChunkDto("fts-1", "lexical hit", 0.4)));

        List<MemoryChunkDto> results = adapter.search("q", 2, "user-1");

        assertEquals(1, results.size());
        assertEquals("fts-1", results.getFirst().chunkId(), "the lexical arm must survive a vector failure");
        verify(ragChunkDao, never()).searchVector(any(), anyInt());
    }

    // =========================================================================
    // index(...)
    // =========================================================================

    @Test
    void indexWritesTheChunkAndSkipsEmbeddingWhenVectorsAreDisabled() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);
        givenTransaction();

        IngestResult result = adapter.index(new MemoryEntryDto("chunk-1", "hello", Map.of()));

        assertEquals("chunk-1", result.id());
        assertFalse(result.isGlobalFallback());
        verify(ragChunkDao).insert(eq("chunk-1"), eq("hello"), eq("System"), any());
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void indexGeneratesAChunkIdWhenTheEntryCarriesNone() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);
        givenTransaction();

        IngestResult result = adapter.index(new MemoryEntryDto(null, "hello", Map.of()));

        assertNotNull(result.id(), "an id-less entry must still be stored under a generated chunk id");
        verify(ragChunkDao).insert(eq(result.id()), eq("hello"), eq("System"), any());
    }

    @Test
    void indexUpsertsTheEmbeddingOutsideTheTransactionWhenVectorsAreEnabled() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        givenTransaction();
        givenConsumer();
        when(embeddingModel.embed("hello")).thenReturn(Response.from(Embedding.from(new float[] {1.0f, 2.0f})));

        IngestResult result = adapter.index(new MemoryEntryDto("chunk-1", "hello", Map.of()));

        assertEquals("chunk-1", result.id());
        verify(ragChunkDao).upsertEmbedding("chunk-1", "[1.0,2.0]");
    }

    @Test
    void indexStillCommitsTheRelationalRowWhenTheEmbeddingFails() {
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        givenTransaction();
        when(embeddingModel.embed("hello")).thenThrow(new IllegalStateException("model unavailable"));

        IngestResult result = adapter.index(new MemoryEntryDto("chunk-1", "hello", Map.of()));

        assertEquals("chunk-1", result.id(), "a failed embedding must not fail the ingest");
        verify(ragChunkDao).insert(eq("chunk-1"), eq("hello"), eq("System"), any());
        verify(ragChunkDao, never()).upsertEmbedding(any(), any());
    }

    private static MemoryChunkDto byId(List<MemoryChunkDto> results, String chunkId) {
        return results.stream()
                .filter(chunk -> chunk.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chunk " + chunkId + " in " + results));
    }
}
