package com.amsha.tools.builtin.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ArchivalContextCache;
import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ArchivalConfidenceThresholdTest {

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
    void testResults_belowConfidenceThreshold_filteredOut() {
        // Arrange: one chunk above threshold (0.8), one below (0.3)
        ArchivalChunkDto highConf = new ArchivalChunkDto(
                "c1",
                "user1",
                "High confidence fact",
                "FACT",
                "session1",
                "msg1",
                null,
                0.8,
                0,
                Instant.now(),
                Instant.now());
        ArchivalChunkDto lowConf = new ArchivalChunkDto(
                "c2",
                "user1",
                "Low confidence guess",
                "FACT",
                "session1",
                "msg2",
                null,
                0.3,
                0,
                Instant.now(),
                Instant.now());

        when(store.search(any())).thenReturn(List.of(highConf, lowConf));

        // Act
        ToolResponse response = tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "test query"));

        // Assert: low confidence chunk filtered out
        assertTrue(response.success());
        assertTrue(response.output().contains("High confidence fact"));
        assertFalse(response.output().contains("Low confidence guess"));
    }

    @Test
    void testResults_atExactThreshold_included() {
        // Arrange: chunk at exactly 0.6 threshold
        ArchivalChunkDto exactThreshold = new ArchivalChunkDto(
                "c1", "user1", "Boundary fact", "FACT", "session1", "msg1", null, 0.6, 0, Instant.now(), Instant.now());

        when(store.search(any())).thenReturn(List.of(exactThreshold));

        // Act
        ToolResponse response = tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "boundary query"));

        // Assert: exact threshold chunk is included
        assertTrue(response.success());
        assertTrue(response.output().contains("Boundary fact"));
    }

    @Test
    void testAllResultsBelowThreshold_returnsNoRelevantKnowledge() {
        // Arrange: all chunks below threshold
        ArchivalChunkDto lowConf = new ArchivalChunkDto(
                "c1", "user1", "Noisy result", "FACT", "session1", "msg1", null, 0.4, 0, Instant.now(), Instant.now());

        when(store.search(any())).thenReturn(List.of(lowConf));

        // Act
        ToolResponse response = tool.execute(
                new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of("query", "noisy query"));

        // Assert: empty results
        assertTrue(response.success());
        assertTrue(response.output().contains("No relevant archival knowledge found"));
    }
}
