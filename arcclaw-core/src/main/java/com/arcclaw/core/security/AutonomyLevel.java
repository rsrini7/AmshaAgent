package com.arcclaw.core.security;

/**
 * Defines the level of autonomy the agent has within a workspace.
 */
public enum AutonomyLevel {
    /**
     * Agent can only read data and perform non-side-effecting operations.
     */
    READONLY,

    /**
     * Cron-fired background execution: can read memory and call read-only
     * external APIs (e.g., github reads), but cannot schedule new tasks,
     * mutate filesystem, execute shell commands, or access secrets.
     * Strictly less powerful than SUPERVISED but more capable than READONLY.
     */
    BACKGROUND,

    /**
     * Agent requires explicit human approval for all side-effecting operations
     * (e.g., file writes, shell commands).
     */
    SUPERVISED,

    /**
     * Agent can perform side-effecting operations without per-operation approval,
     * but still bounded by the overall sandbox and budget.
     */
    FULL
}
