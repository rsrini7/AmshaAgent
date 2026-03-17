package com.arcclaw.core.state;

public enum CronStatus {
    ACTIVE,
    RUNNING,
    COMPLETED,
    FAILED,
    AWAITING_APPROVAL,
    SKIPPED;

    /** SQLite text representation — stable across renames */
    public String toStorageValue() {
        return this.name();
    }

    public static CronStatus fromStorageValue(String value) {
        if (value == null || value.isBlank()) return ACTIVE;
        return switch (value.trim().toUpperCase()) {
            case "ACTIVE"             -> ACTIVE;
            case "RUNNING"            -> RUNNING;
            case "COMPLETED"          -> COMPLETED;
            case "FAILED"             -> FAILED;
            case "AWAITING_APPROVAL"  -> AWAITING_APPROVAL;
            case "SKIPPED"            -> SKIPPED;
            default -> throw new IllegalArgumentException(
                "Unknown CronStatus: " + value);
        };
    }
}
