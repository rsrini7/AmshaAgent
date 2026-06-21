package com.amsha.core.task;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for agent task lifecycle management.
 * <p>
 * Agents interact with this class through {@link AgentTaskTool}.
 * Tasks are persisted as Markdown files with YAML frontmatter, making
 * them human-readable, diffable, and directly editable by the agent.
 */
@ApplicationScoped
public class TaskManager {

    @Inject
    FileSystemTaskRepository repository;

    public TaskFile createTask(String name, String description) {
        return repository.create(name, description);
    }

    public Optional<TaskFile> getTask(String id) {
        return repository.findById(id);
    }

    public List<TaskFile> listTasks() {
        return repository.listAll();
    }

    public List<TaskFile> listActiveTasks() {
        return repository.findByStatus(TaskState.IN_PROGRESS);
    }

    public List<TaskFile> listPendingTasks() {
        return repository.findByStatus(TaskState.TODO);
    }

    public TaskFile transitionTo(String id, TaskState newState) {
        var task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));

        if (task.status().isTerminal()) {
            throw new IllegalStateException(
                    "Cannot transition terminal task '" + task.name() + "' (state=" + task.status() + ")");
        }

        TaskFile updated = task.withStatus(newState);
        return repository.save(updated);
    }

    public TaskFile updateBody(String id, String newBody) {
        var task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        TaskFile updated = task.withBody(newBody);
        return repository.save(updated);
    }

    public boolean deleteTask(String id) {
        return repository.delete(id);
    }

    /**
     * Scans all tasks and returns those that have been in-progress for too long
     * (stale detection — no changes in over 24h).
     */
    public List<TaskFile> findStaleTasks() {
        Instant cutoff = Instant.now().minusSeconds(86400);
        return repository.findByStatus(TaskState.IN_PROGRESS).stream()
                .filter(t -> t.updatedAt().isBefore(cutoff))
                .toList();
    }
}
