package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.embedding.EmbeddingModel;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GlobalKnowledgeBaseTest {

    @Mock
    Jdbi jdbi;

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    FtsConnectionInterceptor ftsInterceptor;

    private GlobalKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new GlobalKnowledgeBase();
        knowledgeBase.jdbi = jdbi;
        knowledgeBase.embeddingModel = embeddingModel;
        knowledgeBase.ftsInterceptor = ftsInterceptor;
    }

    @Test
    void testIngestNullOrBlank() {
        assertNull(knowledgeBase.ingest(null));
        assertNull(knowledgeBase.ingest("   "));
    }

    @Test
    void testIngestVectorDisabled() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);
        assertNull(knowledgeBase.ingest("sample text"));
    }

    @Test
    void testSearchNullOrBlank() {
        assertTrue(knowledgeBase.search(null, 5).isEmpty());
        assertTrue(knowledgeBase.search("", 5).isEmpty());
    }

    @Test
    void testSearchVectorDisabled() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);
        assertTrue(knowledgeBase.search("query", 5).isEmpty());
    }

    @Test
    void testFloatArrayToJson() {
        assertEquals("[]", GlobalKnowledgeBase.floatArrayToJson(null));
        assertEquals("[]", GlobalKnowledgeBase.floatArrayToJson(new float[0]));
        assertEquals("[1.0,2.5]", GlobalKnowledgeBase.floatArrayToJson(new float[] {1.0f, 2.5f}));
    }
}
