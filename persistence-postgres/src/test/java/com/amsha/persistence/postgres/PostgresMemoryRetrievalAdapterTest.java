package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.MemoryChunkDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresMemoryRetrievalAdapterTest {

    @Mock
    CompactionStorePort compactionStore;

    @Mock
    EmbeddingModel embeddingModel;

    PostgresMemoryRetrievalAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PostgresMemoryRetrievalAdapter(null, compactionStore, embeddingModel, 400);
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
}
