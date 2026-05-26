package com.arcclaw.core.tools.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.reasoning.ArchivalContextCache;
import com.arcclaw.core.config.TieredMemoryConfig;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ArchivalToolFirstPathTest {

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
    void testSuccessfulSearch_populatesCache() {
        // Arrange: store returns results
        ArchivalChunkDto chunk = new ArchivalChunkDto(
            "c1", "user1", "Important pattern", "PATTERN",
            "session1", "msg1", null, 0.9, 0, Instant.now(), Instant.now());
        when(store.search(any())).thenReturn(List.of(chunk));

        // Act
        ToolResponse response = tool.execute(
            new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
            Map.of("query", "find patterns"));

        // Assert: cache was populated with formatted text
        verify(cache).put(any(String.class));
        assertTrue(response.success());
        assertTrue(response.output().contains("Important pattern"));
    }

    @Test
    void testEmptySearch_doesNotPolluteCache() {
        // Arrange: store returns empty
        when(store.search(any())).thenReturn(List.of());

        // Act
        ToolResponse response = tool.execute(
            new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
            Map.of("query", "nothing found"));

        // Assert: cache is NOT populated (avoids wasting prompt budget)
        verify(cache, never()).put(any(String.class));
        assertTrue(response.success());
        assertTrue(response.output().contains("No relevant archival knowledge found"));
    }

    @Test
    void testToolFirstPolicy_cacheIsPopulatedBeforeReturn() {
        // Arrange: results present
        ArchivalChunkDto chunk = new ArchivalChunkDto(
            "c1", "user1", "Stored fact", "FACT",
            "session1", "msg1", null, 0.85, 0, Instant.now(), Instant.now());
        when(store.search(any())).thenReturn(List.of(chunk));

        // Act
        ToolResponse response = tool.execute(
            new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null),
            Map.of("query", "find facts"));

        // Assert: cache.put was called (tool-first path)
        verify(cache).put(any(String.class));
        assertTrue(response.output().contains("Stored fact"));
    }
}
