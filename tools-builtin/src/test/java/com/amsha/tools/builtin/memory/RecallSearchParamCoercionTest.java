package com.amsha.tools.builtin.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * LLMs often stringify tool args ({@code "limit":"5"}). Coercion must not ClassCast.
 */
class RecallSearchParamCoercionTest {

    RecallMemorySearchTool tool;
    RecallMemoryStorePort mockRecallStore;

    @BeforeEach
    void setup() throws Exception {
        mockRecallStore = mock(RecallMemoryStorePort.class);
        tool = new RecallMemorySearchTool();
        var field = RecallMemorySearchTool.class.getDeclaredField("recallStore");
        field.setAccessible(true);
        field.set(tool, mockRecallStore);
        when(mockRecallStore.search(any(RecallSearchQuery.class))).thenReturn(List.of());
    }

    @Test
    void acceptsStringLimitAndBooleanLikeCloudflareToolCall() throws Exception {
        String sessionId = "sess-1";
        String userId = "user-1";

        ToolResponse response =
                WorkspaceContext.run(sessionId, userId, "test-chat", AutonomyLevel.SUPERVISED, "tool", () -> {
                    try {
                        return tool.execute(
                                mockCtx(sessionId, userId),
                                Map.of(
                                        "query",
                                        "Hi",
                                        "session_id",
                                        "current session",
                                        "include_summaries",
                                        "false",
                                        "limit",
                                        "5"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        assertTrue(response.success(), () -> "expected success, got: " + response.output());

        ArgumentCaptor<RecallSearchQuery> captor = ArgumentCaptor.forClass(RecallSearchQuery.class);
        verify(mockRecallStore).search(captor.capture());
        RecallSearchQuery q = captor.getValue();
        assertEquals(5, q.limit());
        assertFalse(q.includeSummaries());
    }

    @Test
    void acceptsStringTrueForIncludeSummaries() throws Exception {
        String sessionId = "sess-2";
        String userId = "user-2";

        ToolResponse response =
                WorkspaceContext.run(sessionId, userId, "test-chat", AutonomyLevel.SUPERVISED, "tool", () -> {
                    try {
                        return tool.execute(
                                mockCtx(sessionId, userId),
                                Map.of("query", "summary", "include_summaries", "true", "limit", 3));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        assertTrue(response.success());
        ArgumentCaptor<RecallSearchQuery> captor = ArgumentCaptor.forClass(RecallSearchQuery.class);
        verify(mockRecallStore).search(captor.capture());
        assertTrue(captor.getValue().includeSummaries());
        assertEquals(3, captor.getValue().limit());
    }

    private static ToolExecutionContext mockCtx(String sessionId, String userId) {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.sessionId()).thenReturn(sessionId);
        when(ctx.userId()).thenReturn(userId);
        return ctx;
    }
}
