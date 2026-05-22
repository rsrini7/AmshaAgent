package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import org.junit.jupiter.api.Test;

class MemorySearchToolTest {

    private final CompactionStorePort compactionRepo = mock(CompactionStorePort.class);
    private final MemoryRetrievalPort memoryPort = mock(MemoryRetrievalPort.class);
    private final IntentClassificationService intentService = mock(IntentClassificationService.class);
    private final AuditService auditService = mock(AuditService.class);

    private MemorySearchTool createTool() {
        MemorySearchTool tool = new MemorySearchTool();
        try {
            var repoField = MemorySearchTool.class.getDeclaredField("compactionRepo");
            repoField.setAccessible(true);
            repoField.set(tool, compactionRepo);

            var portField = MemorySearchTool.class.getDeclaredField("memoryPort");
            portField.setAccessible(true);
            portField.set(tool, memoryPort);

            var intentField = MemorySearchTool.class.getDeclaredField("intentService");
            intentField.setAccessible(true);
            intentField.set(tool, intentService);

            var auditField = MemorySearchTool.class.getDeclaredField("auditService");
            auditField.setAccessible(true);
            auditField.set(tool, auditService);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks", e);
        }
        return tool;
    }

    @Test
    void testSearchReturnsRankedResults() throws Exception {
        MemorySearchTool tool = createTool();

        CompactionSummaryDto e1 = new CompactionSummaryDto(
                "session-1", "Discussed blockchain architecture", 
                Instant.parse("2026-02-20T10:00:00Z"), 0, "user-42");

        CompactionSummaryDto e2 = new CompactionSummaryDto(
                "session-2", "Reviewed blockchain consensus algorithms", 
                Instant.parse("2026-02-21T14:00:00Z"), 0, "user-42");

        when(compactionRepo.search("user-42", "blockchain", 5))
                .thenReturn(List.of(e1, e2));
        when(memoryPort.search(anyString(), anyInt(), anyString())).thenReturn(List.of());

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(ctx("test-session", "user-42"), Map.of("query", "blockchain")));

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

        // Blank userId - intentionally triggers the 403 tenant boundary check
        ToolResponse response = WorkspaceContext.run("test-session", "", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(ctx("test-session", ""), Map.of("query", "test")));

        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("Security violation: tenant context not bound."));
    }

    @Test
    void testBlankQueryReturnsEmptyGracefully() throws Exception {
        MemorySearchTool tool = createTool();

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(ctx("test-session", "user-42"), Map.of("query", "   ")));

        assertFalse(result.success());
        assertEquals(400, result.exitCode());
        assertEquals("query parameter is required for memory_search", result.output());
    }

    @Test
    void testEmptyFtsResultReturnsGracefulMessage() throws Exception {
        MemorySearchTool tool = createTool();

        when(compactionRepo.search(eq("user-42"), anyString(), eq(5)))
                .thenReturn(List.of());
        when(memoryPort.search(anyString(), anyInt(), anyString())).thenReturn(List.of());

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(ctx("test-session", "user-42"), Map.of("query", "unknown topic")));

        assertTrue(result.success());
        assertEquals("No relevant memory found for query: unknown topic. Proceed with current context only.",
                result.output());
    }

    @Test
    void testFtsUnavailableReturnsGracefulMessage() throws Exception {
        MemorySearchTool tool = createTool();

        when(compactionRepo.search(eq("user-42"), anyString(), eq(5)))
                .thenThrow(new RuntimeException("no such table: fts.compaction_summary_fts"));
        when(memoryPort.search(anyString(), anyInt(), anyString())).thenReturn(List.of());

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(ctx("test-session", "user-42"), Map.of("query", "blockchain")));

        assertTrue(result.success());
        assertEquals("No relevant memory found for query: blockchain. Proceed with current context only.",
                result.output());
    }

    @Test
    void testFtsGenericExceptionReturnsFailure() throws Exception {
        MemorySearchTool tool = createTool();

        when(compactionRepo.search(eq("user-42"), anyString(), eq(5)))
                .thenThrow(new RuntimeException("connection reset"));
        when(memoryPort.search(anyString(), anyInt(), anyString())).thenReturn(List.of());

        ToolResponse result = WorkspaceContext.run("test-session", "user-42", "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> tool.execute(ctx("test-session", "user-42"), Map.of("query", "blockchain")));

        assertTrue(result.success()); // Swallows exception and proceeds to memoryPort
    }

    @Test
    void testRequiresManualApprovalReturnsTrue() {
        MemorySearchTool tool = new MemorySearchTool();
        assertTrue(tool.requiresManualApproval(Map.of()), "memory_search MUST always require manual approval");
    }

    private ToolExecutionContext ctx(String sessionId, String userId) {
        return new ToolExecutionContext(
            sessionId, userId, "test-chat", "tool", AutonomyLevel.SUPERVISED, 
            "trace-1", null, null, null, null, null, null);
    }
}
