package com.amsha.persistence.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

import com.amsha.persistence.api.MemoryChunkDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionKnowledgeBaseTest {

    EmbeddingModel embeddingModel;
    SessionKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() throws Exception {
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[4])));

        knowledgeBase = new SessionKnowledgeBase();
        var field = SessionKnowledgeBase.class.getDeclaredField("embeddingModel");
        field.setAccessible(true);
        field.set(knowledgeBase, embeddingModel);
    }

    @Test
    void ingestStoresText() {
        knowledgeBase.ingest("chunk-1", "test content");
        List<MemoryChunkDto> results = knowledgeBase.search("test", 10);
        assertEquals(1, results.size());
        assertEquals("chunk-1", results.get(0).chunkId());
    }

    @Test
    void searchReturnsMultipleResults() {
        knowledgeBase.ingest("c1", "apple");
        knowledgeBase.ingest("c2", "banana");
        List<MemoryChunkDto> results = knowledgeBase.search("fruit", 10);
        assertEquals(2, results.size());
    }

    @Test
    void searchRespectsTopN() {
        knowledgeBase.ingest("c1", "alpha");
        knowledgeBase.ingest("c2", "beta");
        knowledgeBase.ingest("c3", "gamma");
        List<MemoryChunkDto> results = knowledgeBase.search("query", 2);
        assertTrue(results.size() <= 2);
    }

    @Test
    void searchWithMinScoreFiltersResults() {
        knowledgeBase.ingest("c1", "test");
        List<MemoryChunkDto> results = knowledgeBase.search("test", 10, 0.99);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchWithoutIngestReturnsEmpty() {
        List<MemoryChunkDto> results = knowledgeBase.search("anything", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void ingestHandlesExceptionGracefully() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("API down"));
        knowledgeBase.ingest("chunk-1", "failing text");
        List<MemoryChunkDto> results = knowledgeBase.search("anything", 10);
        assertTrue(results.isEmpty());
    }
}
