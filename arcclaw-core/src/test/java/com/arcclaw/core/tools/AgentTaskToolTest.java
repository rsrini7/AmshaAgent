package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.task.TaskFile;
import com.arcclaw.core.task.TaskManager;
import com.arcclaw.core.task.TaskState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentTaskToolTest {

    @Mock
    TaskManager taskManager;

    @Mock
    AuditService auditService;

    @InjectMocks
    AgentTaskTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";

    @Test
    void executeCreateReturnsSuccess() {
        TaskFile task = new TaskFile(
                "/tasks/task-1.md", "Review PR", TaskState.TODO, Instant.now(), Instant.now(), "Check code");
        when(taskManager.createTask("Review PR", "Check code")).thenReturn(task);

        ToolResponse resp =
                tool.execute(ctx(), Map.of("action", "create", "name", "Review PR", "description", "Check code"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Created task: Review PR"));
        assertTrue(resp.output().contains("task-1.md"));
        verify(auditService)
                .record(
                        eq(SESSION_ID),
                        eq(USER_ID),
                        eq("coordinator"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        contains("created task=Review PR"));
    }

    @Test
    void executeCreateReturnsFailureWhenNameMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "create"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'name' is required"));
    }

    @Test
    void executeListReturnsFormattedTasks() {
        TaskFile t1 =
                new TaskFile("/tasks/a.md", "Fix bug", TaskState.IN_PROGRESS, Instant.now(), Instant.now(), "body");
        TaskFile t2 = new TaskFile("/tasks/b.md", "Write tests", TaskState.TODO, Instant.now(), Instant.now(), "body");
        when(taskManager.listTasks()).thenReturn(List.of(t1, t2));

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "list"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Fix bug"));
        assertTrue(resp.output().contains("Write tests"));
    }

    @Test
    void executeTransitionUpdatesTaskStatus() {
        TaskFile updated =
                new TaskFile("/tasks/t.md", "Deploy", TaskState.COMPLETED, Instant.now(), Instant.now(), "body");
        when(taskManager.transitionTo("t.md", TaskState.COMPLETED)).thenReturn(updated);

        ToolResponse resp =
                tool.execute(ctx(), Map.of("action", "transition", "taskId", "t.md", "newStatus", "completed"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("completed"));
        verify(auditService)
                .record(
                        eq(SESSION_ID),
                        eq(USER_ID),
                        eq("coordinator"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        contains("transition"));
    }

    @Test
    void executeReturnsFailureForUnknownAction() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "invalid"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("Unknown action"));
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                "chat-1",
                "coordinator",
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
