package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.common.SessionKnowledgeBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqliteMemoryRetrievalAdapterTest {

    @Mock
    GlobalKnowledgeBase globalKnowledgeBase;

    @Mock
    Instance<SessionKnowledgeBase> sessionKnowledgeBase;

    @Mock
    CompactionStorePort compactionStore;

    @Mock
    FtsConnectionInterceptor ftsInterceptor;

    @Mock
    VectorIndexMetadataPort vectorIndexMetadata;

    private SqliteMemoryRetrievalAdapter adapter;

    @BeforeEach
    @SuppressWarnings("UnnecessaryAssignment")
    void setUp() {
        adapter = new SqliteMemoryRetrievalAdapter();
        adapter.globalKb = globalKnowledgeBase;
        adapter.sessionKb = sessionKnowledgeBase;
        adapter.compactionRepo = compactionStore;
        adapter.ftsInterceptor = ftsInterceptor;
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        adapter.maxSourceChars = 400;
    }

    @Test
    void descriptorMismatchFallsBackToFullTextWithoutEmbedding() {
        CompactionSummaryDto summary =
                new CompactionSummaryDto("session-1", "matching text", Instant.now(), 1, "user-1");
        when(compactionStore.search("user-1", "matching", 4)).thenReturn(List.of(summary));
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);

        var results = adapter.search("matching", 2, "user-1");

        assertEquals(1, results.size());
        assertEquals("session-1", results.getFirst().chunkId());
        verify(globalKnowledgeBase, never()).search("matching", 4);
    }
}
