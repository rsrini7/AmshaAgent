package com.arcclaw.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SuppressWarnings("java:S1192")
class RagContextEnricherTest {

    @Mock
    TwoTierMemoryService memoryService;

    @InjectMocks
    RagContextEnricher enricher;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        enricher.maxChunkChars = 300;
        enricher.maxTotalChars = 800;
        enricher.contextChunks = 3;
        enricher.minScore = 0.01;
    }

    @Test
    void testEnrichSkipOnCronSession() {
        String sessionId = AgentSentinels.CRON_SESSION_PREFIX + "123";
        String result = enricher.enrich(sessionId, "user-1", "What is my name?", "Base prompt");
        assertEquals("Base prompt", result);
        verifyNoInteractions(memoryService);
    }

    @Test
    void testEnrichSkipOnWriteIntent() {
        String goal = "Store my name as Srini";
        String result = enricher.enrich("sess-1", "user-1", goal, "Base prompt");
        assertEquals("Base prompt", result);
        verifyNoInteractions(memoryService);
    }

    @Test
    void testEnrichWithResults() throws Exception {
        String sessionId = "sess-1";
        String goal = "Tell me about ArcClaw";
        String basePrompt = "Base prompt";

        List<ScoredChunk> mockChunks = List.of(
                new ScoredChunk("id1", "ArcClaw is an agentic AI framework.", 0.85),
                new ScoredChunk("id2", "It uses RAG for long-term memory.", 0.75));

        when(memoryService.search(anyString(), eq("user-1"), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run(sessionId, "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich(sessionId, "user-1", goal, basePrompt);
        });

        assertTrue(result.contains("ArcClaw is an agentic AI framework."));
        assertTrue(result.contains("It uses RAG for long-term memory."));
        assertTrue(result.contains("Base prompt"));
    }

    @Test
    void testEnrichEmptyResults() throws Exception {
        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(Collections.emptyList());

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertEquals("Base prompt", result);
    }

    @Test
    void testAdaptiveThresholdFiltering() throws Exception {
        List<ScoredChunk> mockChunks = List.of(
                new ScoredChunk("id1", "Strong match 1", 0.9),
                new ScoredChunk("id2", "Strong match 2", 0.8),
                new ScoredChunk("id3", "Weak match", 0.1));

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("Strong match 1"));
        assertTrue(result.contains("Strong match 2"));
        assertFalse(result.contains("Weak match"), "Weak match should have been filtered by adaptive threshold");
    }

    @Test
    void testMinScoreHardFloorFiltersBelow() throws Exception {
        enricher.minScore = 0.5;

        List<ScoredChunk> mockChunks = List.of(
                new ScoredChunk("id1", "High score", 0.9),
                new ScoredChunk("id2", "Medium score", 0.6),
                new ScoredChunk("id3", "Low score", 0.3));

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("High score"));
        assertTrue(result.contains("Medium score"));
        assertFalse(result.contains("Low score"));
    }

    @Test
    void testMinScoreMinusOneDisablesFiltering() throws Exception {
        enricher.minScore = -1.0;

        List<ScoredChunk> mockChunks = List.of(
                new ScoredChunk("id1", "Best", 0.9),
                new ScoredChunk("id2", "Good", 0.8),
                new ScoredChunk("id3", "Mid", 0.5),
                new ScoredChunk("id4", "Low", 0.1));

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("Best"));
        assertTrue(result.contains("Good"));
    }

    @Test
    void testSingleChunkDoesNotApplyAdaptiveThreshold() throws Exception {
        List<ScoredChunk> mockChunks = List.of(new ScoredChunk("id1", "Only result", 0.01));

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("Only result"));
    }

    @Test
    void testTwoChunksUseZeroThreshold() throws Exception {
        List<ScoredChunk> mockChunks =
                List.of(new ScoredChunk("id1", "Result A", 0.05), new ScoredChunk("id2", "Result B", -0.1));

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("Result A"));
        assertFalse(result.contains("Result B"));
    }

    @Test
    void testNullGoalReturnsBasePrompt() {
        String result = enricher.enrich("sess-1", "user-1", null, "Base prompt");
        assertEquals("Base prompt", result);
        verifyNoInteractions(memoryService);
    }

    @Test
    void testBlankGoalReturnsBasePrompt() {
        String result = enricher.enrich("sess-1", "user-1", "   ", "Base prompt");
        assertEquals("Base prompt", result);
        verifyNoInteractions(memoryService);
    }

    @Test
    void testNullUserIdFallsBackToSessionId() throws Exception {
        when(memoryService.search(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(new ScoredChunk("id1", "Content", 0.8)));

        WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", null, "Query", "Base prompt");
        });

        verify(memoryService).search(anyString(), eq("sess-1"), anyInt());
    }

    @Test
    void testContextChunksLimit() throws Exception {
        List<ScoredChunk> manyChunks = List.of(
                new ScoredChunk("id1", "One", 0.9),
                new ScoredChunk("id2", "Two", 0.89),
                new ScoredChunk("id3", "Three", 0.88),
                new ScoredChunk("id4", "Four", 0.87));

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(manyChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("One"));
        assertTrue(result.contains("Three"));
    }
}
