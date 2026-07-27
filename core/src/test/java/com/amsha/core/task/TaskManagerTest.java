package com.amsha.core.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskManagerTest {

    @Mock
    FileSystemTaskRepository repository;

    TaskManager manager;

    private final Instant now = Instant.parse("2026-07-25T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        manager = new TaskManager();
        var field = TaskManager.class.getDeclaredField("repository");
        field.setAccessible(true);
        field.set(manager, repository);
    }

    @Test
    void createTaskDelegatesToRepository() {
        when(repository.create("test", "desc")).thenReturn(task("T1", TaskState.TODO));
        TaskFile result = manager.createTask("test", "desc");
        assertEquals("T1", result.id());
    }

    @Test
    void getTaskReturnsFromRepository() {
        when(repository.findById("T1")).thenReturn(Optional.of(task("T1", TaskState.TODO)));
        assertTrue(manager.getTask("T1").isPresent());
    }

    @Test
    void getTaskReturnsEmptyWhenNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertTrue(manager.getTask("missing").isEmpty());
    }

    @Test
    void listTasksDelegates() {
        when(repository.listAll()).thenReturn(List.of(task("T1", TaskState.TODO)));
        assertEquals(1, manager.listTasks().size());
    }

    @Test
    void listActiveTasksDelegates() {
        when(repository.findByStatus(TaskState.IN_PROGRESS)).thenReturn(List.of(task("T2", TaskState.IN_PROGRESS)));
        assertEquals(1, manager.listActiveTasks().size());
    }

    @Test
    void listPendingTasksDelegates() {
        when(repository.findByStatus(TaskState.TODO)).thenReturn(List.of(task("T3", TaskState.TODO)));
        assertEquals(1, manager.listPendingTasks().size());
    }

    @Test
    void transitionToUpdatesTask() {
        TaskFile todo = task("T1", TaskState.TODO);
        when(repository.findById("T1")).thenReturn(Optional.of(todo));
        when(repository.save(any(TaskFile.class))).thenAnswer(i -> i.getArgument(0));
        TaskFile result = manager.transitionTo("T1", TaskState.IN_PROGRESS);
        assertEquals(TaskState.IN_PROGRESS, result.status());
    }

    @Test
    void transitionToThrowsOnTerminalTask() {
        TaskFile completed = task("T2", TaskState.COMPLETED);
        when(repository.findById("T2")).thenReturn(Optional.of(completed));
        assertThrows(IllegalStateException.class, () -> manager.transitionTo("T2", TaskState.TODO));
    }

    @Test
    void transitionToThrowsOnMissingTask() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> manager.transitionTo("missing", TaskState.TODO));
    }

    @Test
    void updateBodyDelegates() {
        TaskFile original = task("T1", TaskState.TODO);
        when(repository.findById("T1")).thenReturn(Optional.of(original));
        when(repository.save(any(TaskFile.class))).thenAnswer(i -> i.getArgument(0));
        TaskFile result = manager.updateBody("T1", "new body");
        assertEquals("new body", result.body());
    }

    @Test
    void deleteTaskDelegates() {
        when(repository.delete("T1")).thenReturn(true);
        assertTrue(manager.deleteTask("T1"));
    }

    @Test
    void findStaleTasksReturnsOldTasks() {
        Instant old = Instant.now().minusSeconds(86401);
        TaskFile stale = new TaskFile("T1", "stale", TaskState.IN_PROGRESS, old, old, "body");
        when(repository.findByStatus(TaskState.IN_PROGRESS)).thenReturn(List.of(stale));
        assertEquals(1, manager.findStaleTasks().size());
    }

    private TaskFile task(String id, TaskState status) {
        return new TaskFile(id, "t", status, now, now, "body");
    }
}
