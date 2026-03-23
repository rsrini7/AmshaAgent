package com.arcclaw.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.arcclaw.core.state.RagChunkEntity;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GlobalKnowledgeBaseTest {

    @Mock
    EntityManager em;

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    FtsConnectionInterceptor ftsInterceptor;

    @InjectMocks
    GlobalKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() throws Exception {
        // Set embedDimensions via reflection (it's a @ConfigProperty)
        Field dimsField = GlobalKnowledgeBase.class.getDeclaredField("embedDimensions");
        dimsField.setAccessible(true);
        dimsField.setInt(knowledgeBase, 768);
    }

    @Test
    void testIngestedChunkStoredInRagChunkTable() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        Embedding embedding = Embedding.from(vector);
        when(embeddingModel.embed("This is a RAG test document."))
            .thenReturn(new Response<>(embedding));

        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(eq(1), anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(eq(2), anyString())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        String chunkId = knowledgeBase.ingest("This is a RAG test document.");

        assertNotNull(chunkId, "Chunk ID should not be null when vec0 is enabled");

        // Verify the RagChunkEntity was persisted (not CompactionSummaryEntity)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(em).persist(captor.capture());

        Object persisted = captor.getValue();
        assertTrue(persisted instanceof RagChunkEntity, "Must persist RagChunkEntity, not CompactionSummaryEntity");

        RagChunkEntity entity = (RagChunkEntity) persisted;
        assertEquals(chunkId, entity.chunkId);
        assertEquals("This is a RAG test document.", entity.text);
        assertEquals("System", entity.source);
        assertNotNull(entity.createdAt);
    }

    @Test
    void testRagPrefixHackNoLongerPresent() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed("Prefix hack test"))
            .thenReturn(new Response<>(Embedding.from(vector)));

        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(eq(1), anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(eq(2), anyString())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        String chunkId = knowledgeBase.ingest("Prefix hack test");
        assertNotNull(chunkId);

        // Verify chunk ID does NOT start with "rag:" prefix
        assertFalse(chunkId.startsWith("rag:"), "Chunk ID must not use the old rag: prefix hack");

        // Verify persisted entity's chunkId doesn't use rag: prefix
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(em).persist(captor.capture());
        RagChunkEntity entity = (RagChunkEntity) captor.getValue();
        assertFalse(entity.chunkId.startsWith("rag:"));
    }

    @Test
    void testSearchHydratesFromRagChunkEntity() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(anyString()))
            .thenReturn(new Response<>(Embedding.from(vector)));

        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);

        // Simulate vec_global returning one row
        Object[] row = new Object[]{"chunk-abc", Double.valueOf(0.15)};
        when(nativeQuery.getResultList()).thenReturn(Collections.singletonList(row));

        // Simulate RagChunkEntity hydration (not CompactionSummaryEntity)
        RagChunkEntity entity = new RagChunkEntity();
        entity.chunkId = "chunk-abc";
        entity.text = "Hydrated RAG text";
        when(em.find(eq(RagChunkEntity.class), eq("chunk-abc"))).thenReturn(entity);

        List<ScoredChunk> results = knowledgeBase.search("test query", 10);

        assertEquals(1, results.size());
        assertEquals("chunk-abc", results.get(0).chunkId());
        assertEquals("Hydrated RAG text", results.get(0).text());
        assertEquals(0.85, results.get(0).score(), 0.001); // 1.0 - 0.15

        // Verify it queries RagChunkEntity, NOT CompactionSummaryRepository
        verify(em).find(RagChunkEntity.class, "chunk-abc");
    }

    @Test
    void testVec0DisabledReturnsNull() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);

        String chunkId = knowledgeBase.ingest("Some text");
        assertNull(chunkId, "Should return null when vec0 is disabled");

        // Verify no persistence operations
        verify(em, never()).persist(any());
    }

    @Test
    void testVec0DisabledSearchReturnsEmpty() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);

        List<ScoredChunk> results = knowledgeBase.search("query", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void testVectorSerializationUsesRootLocale() throws Exception {
        java.util.Locale defaultLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMAN);
            
            when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
            float[] vector = new float[]{0.1f, 0.2f};
            when(embeddingModel.embed(anyString())).thenReturn(new Response<>(Embedding.from(vector)));

            Query nativeQuery = mock(Query.class);
            when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
            when(nativeQuery.setParameter(anyInt(), anyString())).thenReturn(nativeQuery);
            when(nativeQuery.executeUpdate()).thenReturn(1);

            knowledgeBase.ingest("Test locale");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(nativeQuery).setParameter(eq(2), captor.capture());
            String vectorString = captor.getValue();
            
            // Should contain "0.1" not "0,1"
            assertTrue(vectorString.contains("0.1"), "Vector serialization must use dot separator even in German locale, found: " + vectorString);
            assertFalse(vectorString.contains("0,1"), "Vector serialization must not use comma separator: " + vectorString);
            
        } finally {
            java.util.Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void embeddingSerializationIsLocaleInvariant() {
        float[] embedding = {0.1f, 0.2f, 0.33f};
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            String json = GlobalKnowledgeBase.floatArrayToJson(embedding);
            
            // German locale would produce "0,1" and "0,2".
            // Array separator produces ",0" but we must not match that.
            // We check for the presence of the decimal comma specifically.
            assertFalse(json.contains("0,1") || json.contains("0,2"), 
                "Locale leaked into float format (decimal comma found) — got: " + json);
            
            assertTrue(json.contains("0.1") && json.contains("0.2"), 
                "Expected dot decimal separator — got: " + json);
            assertEquals("[0.1,0.2,0.33]", json);
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
