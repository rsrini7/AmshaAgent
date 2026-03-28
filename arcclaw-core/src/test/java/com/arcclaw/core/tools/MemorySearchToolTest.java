package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
import org.junit.jupiter.api.Test;

class MemorySearchToolTest {

    private final CompactionSummaryRepository repository = mock(CompactionSummaryRepository.class);

    private MemorySearchTool createTool() {
        MemorySearchTool tool = new MemorySearchTool();
        try {
            var repoField = MemorySearchTool.class.getDeclaredField("repository");
            repoField.setAccessible(true);
            repoField.set(tool, repository);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks", e);
        }
        return tool;
    }

    @Test
    void testSearchReturnsRankedResults() throws Exception {
        MemorySearchTool tool = createTool();

        CompactionSummaryEntity e1 = new CompactionSummaryEntity();
        e1.setSessionId("session-1");
        e1.setSummary("Discussed blockchain architecture");
        e1.setCreatedAt(Instant.parse("2026-02-20T10:00:00Z"));

        CompactionSummaryEntity e2 = new CompactionSummaryEntity();
        e2.setSessionId("session-2");
        e2.setSummary("Reviewed blockchain consensus algorithms");
        e2.setCreatedAt(Instant.parse("2026-02-21T14:00:00Z"));

        when(repository.searchMemory("user-42", "\"blockchain\"", 5))
                .thenReturn(List.of(e1, e2));

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(Map.of("query", "blockchain")));

        assertTrue(result.success());
        assertTrue(result.output().contains("session-1"));
        assertTrue(result.output().contains("session-2"));
        assertTrue(result.output().contains("blockchain architecture"));
        assertTrue(result.output().contains("blockchain consensus"));
        assertTrue(result.output().contains("---"));
    }

    @Test
    void testThrowsSecurityExceptionWhenUserIdNotBound() throws Exception {
        MemorySearchTool tool = createTool();

        // Blank userId — intentionally triggers the 403 tenant boundary check
        ToolResponse response = WorkspaceContext.run("test-session", "", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(Map.of("query", "test")));

        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("USER_ID not bound"));
    }

    @Test
    void testBlankQueryReturnsEmptyGracefully() throws Exception {
        MemorySearchTool tool = createTool();

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(Map.of("query", "   ")));

        assertFalse(result.success());
        assertEquals(400, result.exitCode());
        assertEquals("query parameter is required for memory_search", result.output());
    }



    @Test
    void testEmptyFtsResultReturnsGracefulMessage() throws Exception {
        MemorySearchTool tool = createTool();

        when(repository.searchMemory(eq("user-42"), anyString(), eq(5)))
                .thenReturn(List.of());

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(Map.of("query", "unknown topic")));

        assertTrue(result.success());
        assertEquals("No relevant memory found for query: unknown topic. Proceed with current context only.",
                result.output());
    }

    @Test
    void testFtsUnavailableReturnsGracefulMessage() throws Exception {
        MemorySearchTool tool = createTool();

        when(repository.searchMemory(eq("user-42"), anyString(), eq(5)))
                .thenThrow(new RuntimeException("no such table: fts.compaction_summary_fts"));

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(Map.of("query", "blockchain")));

        assertTrue(result.success());
        assertEquals("Memory index temporarily unavailable. Proceed with current context.",
                result.output());
    }

    @Test
    void testFtsGenericExceptionReturnsFailure() throws Exception {
        MemorySearchTool tool = createTool();

        when(repository.searchMemory(eq("user-42"), anyString(), eq(5)))
                .thenThrow(new RuntimeException("connection reset"));

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(Map.of("query", "blockchain")));

        assertFalse(result.success());
        assertEquals(500, result.exitCode());
        assertEquals("Memory search failed: connection reset", result.output());
    }
}
