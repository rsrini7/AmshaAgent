package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.postgres.jdbi.RagChunkJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
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
}
