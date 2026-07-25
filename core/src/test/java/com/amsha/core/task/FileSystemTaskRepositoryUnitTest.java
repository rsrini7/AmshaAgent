package com.amsha.core.task;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemTaskRepositoryUnitTest {

    @TempDir
    Path tempDir;

    private FileSystemTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new FileSystemTaskRepository();
        repository.workspaceRootDir = tempDir.toString();
        repository.init();
    }

    @Test
    void testCreateFindAndList() {
        TaskFile task = repository.create("my-task", "Task body details");
        assertNotNull(task);
        assertEquals("my-task", task.name());
        assertEquals("Task body details", task.body());
        assertEquals(TaskState.TODO, task.status());

        Optional<TaskFile> found = repository.findById(task.id());
        assertTrue(found.isPresent());
        assertEquals("my task", found.get().name());

        List<TaskFile> dateTasks = repository.findByDate(LocalDate.now());
        assertEquals(1, dateTasks.size());

        List<TaskFile> todoTasks = repository.findByStatus(TaskState.TODO);
        assertEquals(1, todoTasks.size());

        List<TaskFile> all = repository.listAll();
        assertEquals(1, all.size());
    }

    @Test
    void testSaveAndDelete() {
        TaskFile task = repository.create("to-delete", "Body");
        TaskFile updated = new TaskFile(
                task.id(), task.name(), TaskState.COMPLETED, task.createdAt(), task.updatedAt(), "New Body");
        repository.save(updated);

        Optional<TaskFile> found = repository.findById(task.id());
        assertTrue(found.isPresent());
        assertEquals(TaskState.COMPLETED, found.get().status());

        assertTrue(repository.delete(task.id()));
        assertFalse(repository.findById(task.id()).isPresent());
    }
}
