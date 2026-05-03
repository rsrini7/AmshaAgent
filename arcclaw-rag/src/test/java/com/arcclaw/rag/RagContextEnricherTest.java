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
        enricher.minScore = -1.0;
    }

    @Test
    void testEnrichSkipOnCronSession() {
        String sessionId = AgentSentinels.CRON_SESSION_PREFIX + "123";
        String result = enricher.enrich(sessionId, "What is my name?", "Base prompt");
        assertEquals("Base prompt", result);
        verifyNoInteractions(memoryService);
    }

    @Test
    void testEnrichSkipOnWriteIntent() {
        String goal = "Store my name as Srini";
        String result = enricher.enrich("sess-1", goal, "Base prompt");
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
            new ScoredChunk("id2", "It uses RAG for long-term memory.", 0.75)
        );

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
        // Scores: 0.9, 0.8, 0.1 (0.1 should be filtered out if adaptive threshold works)
        List<ScoredChunk> mockChunks = List.of(
            new ScoredChunk("id1", "Strong match 1", 0.9),
            new ScoredChunk("id2", "Strong match 2", 0.8),
            new ScoredChunk("id3", "Weak match", 0.1)
        );

        when(memoryService.search(anyString(), anyString(), anyInt())).thenReturn(mockChunks);

        String result = WorkspaceContext.run("sess-1", "user-1", "chat-1", AutonomyLevel.FULL, "COORDINATOR", () -> {
            return enricher.enrich("sess-1", "user-1", "Query", "Base prompt");
        });

        assertTrue(result.contains("Strong match 1"));
        assertTrue(result.contains("Strong match 2"));
        assertFalse(result.contains("Weak match"), "Weak match should have been filtered by adaptive threshold");
    }
}
