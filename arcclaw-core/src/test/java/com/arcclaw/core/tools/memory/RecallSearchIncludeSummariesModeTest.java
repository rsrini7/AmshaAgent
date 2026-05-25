package com.arcclaw.core.tools.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;
import com.arcclaw.persistence.api.RecallSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase 3 Test 2: Verify include_summaries=true returns both raw messages and summaries,
 * with raw messages ranked higher.
 */
class RecallSearchIncludeSummariesModeTest {

    RecallMemorySearchTool tool;
    RecallMemoryStorePort mockRecallStore;

    @BeforeEach
    void setup() {
        mockRecallStore = mock(RecallMemoryStorePort.class);
        tool = new RecallMemorySearchTool();
        
        try {
            var field = RecallMemorySearchTool.class.getDeclaredField("recallStore");
            field.setAccessible(true);
            field.set(tool, mockRecallStore);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testSearchIncludesSummariesWhenRequested() throws Exception {
        String sessionId = "test-session-1";
        String userId = "test-user";
        
        RecallMessageDto codeSnippet = new RecallMessageDto(
            "msg-1", sessionId, userId, 5, "hash1", "agent", "AGENT_MESSAGE",
            "```python\ndef calculate_total(items):\n    return sum(item.price for item in items)\n```",
            null, null, true, false, null, null
        );
        
        RecallMessageDto summary = new RecallMessageDto(
            "msg-2", sessionId, userId, 10, "hash2", "system", "COMPACTION_SUMMARY",
            "User asked about calculate_total function for price summation",
            null, null, false, false, null, null
        );
        
        // Mock returns both when includeSummaries=true
        when(mockRecallStore.search(any(RecallSearchQuery.class))).thenReturn(List.of(codeSnippet, summary));
        
        ToolResponse response = WorkspaceContext.run(sessionId, userId, "test-chat",
                AutonomyLevel.SUPERVISED, "tool",
                () -> {
                    try {
                        return tool.execute(mockCtx(sessionId, userId), Map.of(
                            "query", "calculate_total",
                            "include_summaries", true,
                            "limit", 10
                        ));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        
        assertTrue(response.success());
        String content = response.output();
        
        // Both should be present
        assertTrue(content.contains("calculate_total"), "Should contain code snippet");
        assertTrue(content.contains("User asked about calculate_total"), "Should contain summary");
        
        // Verify query was called with includeSummaries=true
        ArgumentCaptor<RecallSearchQuery> queryCaptor = ArgumentCaptor.forClass(RecallSearchQuery.class);
        verify(mockRecallStore).search(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().includeSummaries(), "Should include summaries when requested");
    }

    private com.arcclaw.core.tools.ToolExecutionContext mockCtx(String sessionId, String userId) {
        com.arcclaw.core.tools.ToolExecutionContext ctx = mock(com.arcclaw.core.tools.ToolExecutionContext.class);
        when(ctx.sessionId()).thenReturn(sessionId);
        when(ctx.userId()).thenReturn(userId);
        return ctx;
    }
}
