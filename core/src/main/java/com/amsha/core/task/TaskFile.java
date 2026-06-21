package com.amsha.core.task;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable representation of an agent task persisted as a Markdown file
 * with YAML frontmatter.
 * <p>
 * File layout:
 * <pre>
 * ---
 * id: /workspace/tasks/2026-05-17/143022-review-pr.md
 * createdAt: 2026-05-17T14:30:22Z
 * updatedAt: 2026-05-17T14:35:10Z
 * status: in_progress
 * ---
 * ## Review PR #42
 * Check the open PR for code style issues and suggest improvements.
 * </pre>
 *
 * @param id         Absolute path to the task file.
 * @param name       Short human-readable name (derived from filename).
 * @param status     Current lifecycle state.
 * @param createdAt  When the task was first created.
 * @param updatedAt  When the task was last modified.
 * @param body       Markdown body content (after frontmatter).
 */
public record TaskFile(String id, String name, TaskState status, Instant createdAt, Instant updatedAt, String body) {
    public TaskFile withStatus(TaskState newStatus) {
        return new TaskFile(id, name, newStatus, createdAt, Instant.now(), body);
    }

    public TaskFile withBody(String newBody) {
        return new TaskFile(id, name, status, createdAt, Instant.now(), newBody);
    }

    /**
     * Serialises frontmatter fields into a flat string map for YAML writing.
     */
    public Map<String, String> toFrontmatter() {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("id", id);
        fm.put("name", name);
        fm.put("status", status.name().toLowerCase(Locale.ROOT));
        fm.put("createdAt", createdAt.toString());
        fm.put("updatedAt", updatedAt.toString());
        return fm;
    }

    /**
     * Builds the complete file content: YAML frontmatter + body.
     */
    public String toFileContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        for (var e : toFrontmatter().entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        sb.append("---\n");
        if (body != null && !body.isBlank()) {
            sb.append(body).append("\n");
        }
        return sb.toString();
    }
}
