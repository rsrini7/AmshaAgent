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

/**
 * Phase 3 Test 3: Verify tenant isolation - User B cannot search User A's data.
 * Setup: User A ingests "Secret Password". User B searches for "Secret Password".
 * Action: User B calls recall_memory_search.
 * Assert: Result is empty (tenant isolation enforced).
 */
class RecallSearchTenantIsolationTest {

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
    void testTenantIsolationPreventsCrossUserAccess() throws Exception {
        String userA = "user-A";
        String userB = "user-B";
        String sessionId = "test-session";

        // User A's secret data
        RecallMessageDto userASecret = new RecallMessageDto(
                "msg-1",
                sessionId,
                userA,
                5,
                "hash1",
                "user",
                "USER_MESSAGE",
                "My secret password is hunter2",
                null,
                null,
                false,
                false,
                null,
                null);

        // Mock returns data based on query userId - verify in test
        when(mockRecallStore.search(any(RecallSearchQuery.class))).thenAnswer(invocation -> {
            RecallSearchQuery query = invocation.getArgument(0);
            if (query.userId().equals(userA)) {
                return List.of(userASecret);
            }
            return List.of(); // User B gets nothing
        });

        // User B searches - should get empty results
        ToolResponse responseB =
                WorkspaceContext.run(sessionId, userB, "test-chat", AutonomyLevel.SUPERVISED, "tool", () -> {
                    try {
                        return tool.execute(mockCtx(sessionId, userB), Map.of("query", "secret password", "limit", 5));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        assertTrue(responseB.success());
        assertTrue(responseB.output().contains("No exact matches found"), "User B should not see User A's data");

        // User A searches - should see their own data
        ToolResponse responseA =
                WorkspaceContext.run(sessionId, userA, "test-chat", AutonomyLevel.SUPERVISED, "tool", () -> {
                    try {
                        return tool.execute(mockCtx(sessionId, userA), Map.of("query", "secret password", "limit", 5));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        assertTrue(responseA.success());
        assertTrue(responseA.output().contains("hunter2"), "User A should see their own data");
    }

    private ToolExecutionContext mockCtx(String sessionId, String userId) {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.sessionId()).thenReturn(sessionId);
        when(ctx.userId()).thenReturn(userId);
        return ctx;
    }
}
