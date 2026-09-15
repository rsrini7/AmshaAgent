package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.task.TaskFile;
import com.amsha.core.task.TaskManager;
import com.amsha.core.task.TaskState;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

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

    // --- Task #6: cover the remaining reachable actions / branches (get, update, delete, list_active) ---

    @Test
    void executeListActiveReturnsFormattedTasks() {
        TaskFile t1 =
                new TaskFile("/tasks/a.md", "Fix bug", TaskState.IN_PROGRESS, Instant.now(), Instant.now(), "body");
        when(taskManager.listActiveTasks()).thenReturn(List.of(t1));

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "list_active"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Fix bug"));
    }

    @Test
    void executeListActiveReturnsEmptyMessage() {
        when(taskManager.listActiveTasks()).thenReturn(List.of());

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "list_active"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("No active tasks"));
    }

    @Test
    void executeGetReturnsTaskDetails() {
        TaskFile task =
                new TaskFile("/tasks/x.md", "Do thing", TaskState.TODO, Instant.now(), Instant.now(), "body text");
        when(taskManager.getTask("x.md")).thenReturn(Optional.of(task));

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "get", "taskId", "x.md"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Do thing"));
        assertTrue(resp.output().contains("body text"));
    }

    @Test
    void executeGetReturnsNotFound() {
        when(taskManager.getTask("missing.md")).thenReturn(Optional.empty());

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "get", "taskId", "missing.md"));

        assertFalse(resp.success());
        assertEquals(404, resp.exitCode());
        assertTrue(resp.output().contains("Task not found"));
    }

    @Test
    void executeGetReturnsFailureWhenTaskIdMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "get"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'taskId' is required"));
    }

    @Test
    void executeUpdateReturnsSuccess() {
        TaskFile updated =
                new TaskFile("/tasks/u.md", "Edit", TaskState.TODO, Instant.now(), Instant.now(), "new body");
        when(taskManager.updateBody("u.md", "new body")).thenReturn(updated);

        ToolResponse resp =
                tool.execute(ctx(), Map.of("action", "update", "taskId", "u.md", "description", "new body"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Updated task: Edit"));
        verify(auditService)
                .record(
                        eq(SESSION_ID),
                        eq(USER_ID),
                        eq("coordinator"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        contains("update task="));
    }

    @Test
    void executeUpdateReturnsFailureWhenTaskIdMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "update", "description", "body"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'taskId' is required"));
    }

    @Test
    void executeUpdateReturnsFailureWhenDescriptionMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "update", "taskId", "u.md"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'description' is required"));
    }

    @Test
    void executeDeleteReturnsSuccess() {
        when(taskManager.deleteTask("d.md")).thenReturn(true);

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "delete", "taskId", "d.md"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Deleted task: d.md"));
        verify(auditService)
                .record(
                        eq(SESSION_ID),
                        eq(USER_ID),
                        eq("coordinator"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        contains("deleted task="));
    }

    @Test
    void executeDeleteReturnsNotFound() {
        when(taskManager.deleteTask("d.md")).thenReturn(false);

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "delete", "taskId", "d.md"));

        assertFalse(resp.success());
        assertEquals(404, resp.exitCode());
        assertTrue(resp.output().contains("Task not found"));
    }

    @Test
    void executeDeleteReturnsFailureWhenTaskIdMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "delete"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'taskId' is required"));
    }

    @Test
    void executeTransitionReturnsFailureWhenTaskIdMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "transition", "newStatus", "completed"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'taskId' is required"));
    }

    @Test
    void executeTransitionReturnsFailureWhenNewStatusMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "transition", "taskId", "t.md"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("'newStatus' is required"));
    }

    @Test
    void executeTransitionReturnsFailureWhenManagerRejectsTransition() {
        when(taskManager.transitionTo("terminal.md", TaskState.COMPLETED))
                .thenThrow(new IllegalStateException("Cannot transition terminal task"));

        ToolResponse resp =
                tool.execute(ctx(), Map.of("action", "transition", "taskId", "terminal.md", "newStatus", "completed"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("Cannot transition terminal task"));
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
