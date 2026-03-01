package com.arcclaw.core.tools;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemorySearchToolTest {

    private final CompactionSummaryRepository repository = mock(CompactionSummaryRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private MemorySearchTool createTool() {
        MemorySearchTool tool = new MemorySearchTool();
        // Inject mocks via field access (fields are package-private CDI injection points)
        try {
            var repoField = MemorySearchTool.class.getDeclaredField("repository");
            repoField.setAccessible(true);
            repoField.set(tool, repository);

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

        CompactionSummaryEntity e1 = new CompactionSummaryEntity();
        e1.sessionId = "session-1";
        e1.summary = "Discussed blockchain architecture";
        e1.createdAt = Instant.parse("2026-02-20T10:00:00Z");

        CompactionSummaryEntity e2 = new CompactionSummaryEntity();
        e2.sessionId = "session-2";
        e2.summary = "Reviewed blockchain consensus algorithms";
        e2.createdAt = Instant.parse("2026-02-21T14:00:00Z");

        when(repository.searchMemory("user-42", "blockchain", 5))
                .thenReturn(List.of(e1, e2));

        ToolResponse result = WorkspaceScopeHolder.call("test-session", "user-42", null, () -> 
                tool.execute(Map.of("query", "blockchain")));

        assertTrue(result.success());
        assertTrue(result.output().contains("session-1"));
        assertTrue(result.output().contains("session-2"));
        assertTrue(result.output().contains("blockchain architecture"));
        assertTrue(result.output().contains("blockchain consensus"));
        assertTrue(result.output().contains("---"));
    }

    @Test
    void testThrowsSecurityExceptionWhenUserIdNotBound() {
        MemorySearchTool tool = createTool();

        assertThrows(SecurityException.class, () ->
                tool.execute(Map.of("query", "test")));
    }

    @Test
    void testBlankQueryReturnsEmptyGracefully() throws Exception {
        MemorySearchTool tool = createTool();

        ToolResponse result = WorkspaceScopeHolder.call("test-session", "user-42", null, () -> 
                tool.execute(Map.of("query", "   ")));

        assertTrue(result.success());
        assertEquals("No query provided", result.output());
    }

    @Test
    void testSearchAuditedWithToolInvoked() {
        MemorySearchTool tool = createTool();

        when(repository.searchMemory(eq("user-42"), anyString(), eq(5)))
                .thenReturn(List.of());

        WorkspaceScopeHolder.run("test-session", "user-42", null, () -> 
                tool.execute(Map.of("query", "architecture")));

        verify(auditService).record(
                eq("test-session"),
                eq("user-42"),
                eq("tool"),
                eq(SecurityEventType.TOOL_INVOKED),
                contains("memory_search"));
    }
}
