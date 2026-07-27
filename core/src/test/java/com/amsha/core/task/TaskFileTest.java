package com.amsha.core.task;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TaskFileTest {

    private final Instant now = Instant.parse("2026-07-25T10:00:00Z");
    private final TaskFile task = new TaskFile(
            "/workspace/tasks/2026-07-25/100000-test-task.md",
            "test task",
            TaskState.TODO,
            now,
            now,
            "## Test\nBody content");

    @Test
    void withStatusUpdatesStatusAndUpdatedAt() {
        TaskFile updated = task.withStatus(TaskState.IN_PROGRESS);
        assertEquals(TaskState.IN_PROGRESS, updated.status());
        assertEquals(task.id(), updated.id());
        assertEquals(task.name(), updated.name());
        assertEquals(task.body(), updated.body());
        assertNotNull(updated.updatedAt());
    }

    @Test
    void withBodyUpdatesBodyAndUpdatedAt() {
        TaskFile updated = task.withBody("New body");
        assertEquals("New body", updated.body());
        assertEquals(task.id(), updated.id());
        assertEquals(task.status(), updated.status());
        assertNotNull(updated.updatedAt());
    }

    @Test
    void toFrontmatterContainsAllFields() {
        var fm = task.toFrontmatter();
        assertEquals(task.id(), fm.get("id"));
        assertEquals(task.name(), fm.get("name"));
        assertEquals("todo", fm.get("status"));
        assertEquals(task.createdAt().toString(), fm.get("createdAt"));
        assertEquals(task.updatedAt().toString(), fm.get("updatedAt"));
    }

    @Test
    void toFileContentContainsFrontmatterAndBody() {
        String content = task.toFileContent();
        assertTrue(content.startsWith("---\n"));
        assertTrue(content.contains("id: " + task.id()));
        assertTrue(content.contains("name: " + task.name()));
        assertTrue(content.contains("status: todo"));
        assertTrue(content.contains("---\n"));
        assertTrue(content.contains(task.body()));
    }

    @Test
    void toFileContentWithNullBodyStillHasFrontmatter() {
        TaskFile noBody = new TaskFile("id", "name", TaskState.TODO, now, now, null);
        String content = noBody.toFileContent();
        assertTrue(content.startsWith("---\n"));
        assertTrue(content.endsWith("---\n"));
    }
}
