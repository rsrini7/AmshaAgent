package com.amsha.core.tools.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verify RecallMemorySearchTool excludes COMPACTION_SUMMARY by default.
 * Setup: Ingest a raw code snippet (Turn 5) and a Compaction Summary that mentions the code.
 * Action: Call recall_memory_search with the function name.
 * Assert: Result contains ONLY the raw code snippet. Summary is ABSENT.
 */
class RecallSearchCodeSnippetNoSummaryPollutionTest {

    RecallMemorySearchTool tool;
    RecallMemoryStorePort mockRecallStore;

    @BeforeEach
    void setup() {
        mockRecallStore = mock(RecallMemoryStorePort.class);
        tool = new RecallMemorySearchTool();

        // Inject mock via reflection
        try {
            var field = RecallMemorySearchTool.class.getDeclaredField("recallStore");
            field.setAccessible(true);
            field.set(tool, mockRecallStore);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testSearchExcludesSummariesByDefault() throws Exception {
        String sessionId = "test-session-1";
        String userId = "test-user";

        // Setup: Raw code snippet at Turn 5
        RecallMessageDto codeSnippet = new RecallMessageDto(
                "msg-1",
                sessionId,
                userId,
                5,
                "hash1",
                "agent",
                "AGENT_MESSAGE",
                "```python\ndef calculate_total(items):\n    return sum(item.price for item in items)\n```",
                null,
                null,
                true,
                false,
                null,
                null);

        // Mock search to return only code snippet when includeSummaries=false
        when(mockRecallStore.search(any(RecallSearchQuery.class))).thenReturn(List.of(codeSnippet));

        // Execute search WITHOUT include_summaries flag (default=false), WITH session_id (default=current)
        ToolResponse response =
                WorkspaceContext.run(sessionId, userId, "test-chat", AutonomyLevel.SUPERVISED, "tool", () -> {
                    try {
                        return tool.execute(mockCtx(sessionId, userId), Map.of("query", "calculate_total", "limit", 5));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // Verify success
        assertTrue(response.success(), "Search should succeed");

        // Verify result contains code snippet
        String content = response.output();
        assertTrue(content.contains("calculate_total"), "Should contain code snippet");
        assertTrue(content.contains("agent"), "Should identify role as agent");

        // Verify summary is NOT present
        assertFalse(content.contains("COMPACTION_SUMMARY"), "Should NOT contain summary type");
        assertFalse(content.contains("User asked about calculate_total"), "Summary content should be absent");

        // Verify query was called with includeSummaries=false
        ArgumentCaptor<RecallSearchQuery> queryCaptor = ArgumentCaptor.forClass(RecallSearchQuery.class);
        verify(mockRecallStore).search(queryCaptor.capture());
        assertFalse(queryCaptor.getValue().includeSummaries(), "Default should exclude summaries");
    }

    private ToolExecutionContext mockCtx(String sessionId, String userId) {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.sessionId()).thenReturn(sessionId);
        when(ctx.userId()).thenReturn(userId);
        return ctx;
    }
}
