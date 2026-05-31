package com.arcclaw.core.tools.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecallMemorySearchToolTest {

    @Mock
    RecallMemoryStorePort recallStore;

    @InjectMocks
    RecallMemorySearchTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";

    @Test
    void executeReturnsSuccessWithResults() throws Exception {
        RecallMessageDto msg = new RecallMessageDto(
                "msg-1",
                SESSION_ID,
                USER_ID,
                3,
                "hash1",
                "user",
                "USER_MESSAGE",
                "The error was NullPointerException",
                null,
                null,
                false,
                true,
                null,
                null);

        when(recallStore.search(any())).thenReturn(List.of(msg));

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "researcher",
                () -> tool.execute(ctx(), Map.of("query", "NullPointerException")));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Found 1 exact matches"));
        assertTrue(resp.output().contains("NullPointerException"));
    }

    @Test
    void executeReturnsNoMatchesWhenEmpty() throws Exception {
        when(recallStore.search(any())).thenReturn(List.of());

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "researcher",
                () -> tool.execute(ctx(), Map.of("query", "nonexistent")));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("No exact matches found"));
    }

    @Test
    void executeReturnsFailureWhenQueryBlank() throws Exception {
        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "researcher",
                () -> tool.execute(ctx(), Map.of("query", "  ")));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
    }

    @Test
    void executeReturnsFailureWhenQueryMissing() throws Exception {
        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID, USER_ID, "chat-1", AutonomyLevel.FULL, "researcher", () -> tool.execute(ctx(), Map.of()));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
    }

    @Test
    void executeScopesToSessionByDefault() throws Exception {
        when(recallStore.search(any())).thenReturn(List.of());

        WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "researcher",
                () -> tool.execute(ctx(), Map.of("query", "test")));

        verify(recallStore).search(argThat(q -> SESSION_ID.equals(q.sessionId())));
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                "chat-1",
                "researcher",
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
