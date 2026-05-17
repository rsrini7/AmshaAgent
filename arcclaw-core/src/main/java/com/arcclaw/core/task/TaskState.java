package com.arcclaw.core.task;

/**
 * Lifecycle states for agent task files.
 * <p>
 * Stored in YAML frontmatter as a plain string, used to drive
 * the agent's task management workflow.
 */
public enum TaskState {
    TODO,
    IN_PROGRESS,
    COMPLETED,
    AWAITING_HUMAN_INPUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == IN_PROGRESS || this == AWAITING_HUMAN_INPUT;
    }

    public static TaskState fromString(String s) {
        if (s == null || s.isBlank()) return TODO;
        return switch (s.trim().toUpperCase()) {
            case "TODO" -> TODO;
            case "IN_PROGRESS", "IN PROGRESS", "INPROGRESS" -> IN_PROGRESS;
            case "COMPLETED", "DONE" -> COMPLETED;
            case "AWAITING_HUMAN_INPUT", "AWAITING INPUT", "BLOCKED" -> AWAITING_HUMAN_INPUT;
            case "CANCELLED", "CANCELED" -> CANCELLED;
            default -> TODO;
        };
    }
}
