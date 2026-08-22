package com.amsha.tools.builtin.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ArchivalContextCache;
import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests that ArchivalMemorySearchTool correctly passes chunk types filter
 * and formats results with type/provenance metadata for goal-matching.
 */
class ArchivalGoalMatchingTest {

    @Mock
    ArchivalMemoryStorePort store;

    @Mock
    ArchivalContextCache cache;

    @Mock
    TieredMemoryConfig tieredConfig;

    @Mock
    TieredMemoryConfig.ArchivalConfig archivalConfig;

    @Mock
    Instance<EmbeddingModel> embeddingModelInstance;

    ArchivalMemorySearchTool tool;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        tool = new ArchivalMemorySearchTool();
        tool.archivalStore = store;
        tool.archivalContextCache = cache;
        tool.tieredConfig = tieredConfig;
        tool.embeddingModelInstance = embeddingModelInstance;

        lenient().when(tieredConfig.archival()).thenReturn(archivalConfig);
        lenient().when(archivalConfig.maxSearchResults()).thenReturn(8);
        lenient().when(archivalConfig.confidenceThreshold()).thenReturn(0.6);
        lenient().when(embeddingModelInstance.isUnsatisfied()).thenReturn(true);
    }

    @Test
    void testChunkTypeFilterPassedToQuery() {
        // Arrange: store returns empty (we just want to verify the query)
        when(store.search(any())).thenReturn(List.of());

        // Act: pass chunk_types filter
        tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "patterns", "chunk_types", "CODE,PATTERN"));

        // Assert: search query received the chunk types
        ArgumentCaptor<ArchivalSearchQuery> captor = ArgumentCaptor.forClass(ArchivalSearchQuery.class);
        verify(store).search(captor.capture());
        ArchivalSearchQuery captured = captor.getValue();
        assertTrue(captured.chunkTypes().contains("CODE"));
        assertTrue(captured.chunkTypes().contains("PATTERN"));
        assertFalse(captured.chunkTypes().contains("FACT"));
    }

    @Test
    void testNoChunkTypeFilter_searchesAllTypes() {
        // Arrange
        when(store.search(any())).thenReturn(List.of());

        // Act: no chunk_types parameter
        tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "everything"));

        // Assert: empty set = no type filter
        ArgumentCaptor<ArchivalSearchQuery> captor = ArgumentCaptor.forClass(ArchivalSearchQuery.class);
        verify(store).search(captor.capture());
        assertTrue(captor.getValue().chunkTypes().isEmpty());
    }

    @Test
    void testResultFormatting_includesTypeAndProvenance() {
        // Arrange: different chunk types
        ArchivalChunkDto codeChunk = new ArchivalChunkDto(
                "c1",
                "user1",
                "Use sealed classes",
                "CODE",
                "session-abc",
                "msg42",
                null,
                0.9,
                0,
                Instant.now(),
                Instant.now());
        ArchivalChunkDto prefChunk = new ArchivalChunkDto(
                "c2",
                "user1",
                "Prefers verbose logging",
                "PREFERENCE",
                null,
                null,
                null,
                0.75,
                0,
                Instant.now(),
                Instant.now());

        when(store.search(any())).thenReturn(List.of(codeChunk, prefChunk));

        // Act
        ToolResponse response = tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "coding patterns"));

        // Assert: formatted output has type, provenance, and confidence
        String output = response.output();
        assertTrue(output.contains("[CODE]"));
        assertTrue(output.contains("session-abc"));
        assertTrue(output.contains("confidence=0.90"));
        assertTrue(output.contains("Use sealed classes"));

        assertTrue(output.contains("[PREFERENCE]"));
        assertTrue(output.contains("confidence=0.75"));
        assertTrue(output.contains("Prefers verbose logging"));
    }

    @Test
    void testEmptyResults_returnsNoRelevantMessage() {
        when(store.search(any())).thenReturn(List.of());

        ToolResponse response = tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "nonexistent"));

        assertTrue(response.success());
        assertTrue(response.output().contains("No relevant archival knowledge found"));
    }
}
