package com.arcclaw.core.tools.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.agent.reasoning.ArchivalContextCache;
import com.arcclaw.core.config.TieredMemoryConfig;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchivalMemorySearchToolTest {

    @Mock
    ArchivalMemoryStorePort archivalStore;

    @Mock
    ArchivalContextCache archivalContextCache;

    @Mock
    TieredMemoryConfig tieredConfig;

    @Mock
    Instance<EmbeddingModel> embeddingModelInstance;

    @InjectMocks
    ArchivalMemorySearchTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";

    @Test
    void executeReturnsFilteredResults() throws Exception {
        TieredMemoryConfig.ArchivalConfig archivalConfig = mock(TieredMemoryConfig.ArchivalConfig.class);
        when(tieredConfig.archival()).thenReturn(archivalConfig);
        when(archivalConfig.maxSearchResults()).thenReturn(8);
        when(archivalConfig.confidenceThreshold()).thenReturn(0.5);

        when(embeddingModelInstance.isUnsatisfied()).thenReturn(true);

        ArchivalChunkDto highConfidence = new ArchivalChunkDto(
                "c1",
                USER_ID,
                "Use builder pattern for DTOs",
                "PATTERN",
                SESSION_ID,
                null,
                "java,design",
                0.9,
                0,
                Instant.now(),
                Instant.now());
        ArchivalChunkDto lowConfidence = new ArchivalChunkDto(
                "c2", USER_ID, "Maybe use MVC?", "DOC", SESSION_ID, null, null, 0.3, 0, Instant.now(), Instant.now());

        when(archivalStore.search(any())).thenReturn(List.of(highConfidence, lowConfidence));

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "architect",
                () -> tool.execute(ctx(), Map.of("query", "design patterns")));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("PATTERN"));
        assertTrue(resp.output().contains("builder pattern"));
        assertFalse(resp.output().contains("Maybe use MVC"));
    }

    @Test
    void executeReturnsNoResultsMessage() throws Exception {
        TieredMemoryConfig.ArchivalConfig archivalConfig = mock(TieredMemoryConfig.ArchivalConfig.class);
        when(tieredConfig.archival()).thenReturn(archivalConfig);
        when(archivalConfig.maxSearchResults()).thenReturn(8);
        when(archivalConfig.confidenceThreshold()).thenReturn(0.5);

        when(embeddingModelInstance.isUnsatisfied()).thenReturn(true);
        when(archivalStore.search(any())).thenReturn(List.of());

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "architect",
                () -> tool.execute(ctx(), Map.of("query", "nonexistent")));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("No relevant archival knowledge found"));
    }

    @Test
    void executeReturnsFailureWhenQueryBlank() throws Exception {
        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "architect",
                () -> tool.execute(ctx(), Map.of("query", "  ")));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
    }

    @Test
    void executeCachesResultsForPromptInjection() throws Exception {
        TieredMemoryConfig.ArchivalConfig archivalConfig = mock(TieredMemoryConfig.ArchivalConfig.class);
        when(tieredConfig.archival()).thenReturn(archivalConfig);
        when(archivalConfig.maxSearchResults()).thenReturn(8);
        when(archivalConfig.confidenceThreshold()).thenReturn(0.5);

        when(embeddingModelInstance.isUnsatisfied()).thenReturn(true);

        ArchivalChunkDto chunk = new ArchivalChunkDto(
                "c1",
                USER_ID,
                "Always use UUID for IDs",
                "DIRECTIVE",
                SESSION_ID,
                null,
                null,
                1.0,
                0,
                Instant.now(),
                Instant.now());
        when(archivalStore.search(any())).thenReturn(List.of(chunk));

        WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "architect",
                () -> tool.execute(ctx(), Map.of("query", "ID strategy")));

        verify(archivalContextCache).put(contains("Always use UUID"));
    }

    @Test
    void executeHandlesExceptionGracefully() throws Exception {
        TieredMemoryConfig.ArchivalConfig archivalConfig = mock(TieredMemoryConfig.ArchivalConfig.class);
        when(tieredConfig.archival()).thenReturn(archivalConfig);
        when(archivalConfig.maxSearchResults()).thenReturn(8);

        when(embeddingModelInstance.isUnsatisfied()).thenReturn(true);
        when(archivalStore.search(any())).thenThrow(new RuntimeException("Index corrupted"));

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "architect",
                () -> tool.execute(ctx(), Map.of("query", "test")));

        assertFalse(resp.success());
        assertEquals(1, resp.exitCode());
        assertTrue(resp.output().contains("Archival search error"));
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                "chat-1",
                "architect",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
