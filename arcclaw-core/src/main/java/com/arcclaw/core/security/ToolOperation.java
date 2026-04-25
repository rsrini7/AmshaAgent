package com.arcclaw.core.security;

/**
 * Categories of tool operations for fine-grained autonomy control.
 */
public enum ToolOperation {
    /**
     * Reading local files or metadata.
     */
    READ_FS,

    /**
     * Modifying or deleting local files.
     */
    WRITE_FS,

    /**
     * Executing arbitrary shell commands or binaries.
     */
    EXECUTE_SHELL,

    /**
     * External HTTP(S) requests.
     */
    NETWORK_OUTBOUND,

    /**
     * Storing or retrieving secrets from the vault.
     */
    VAULT_ACCESS,

    /**
     * Writing durable autonomous agent tasks (cron scheduling).
     */
    SCHEDULE_AUTONOMOUS,

    /**
     * Storing or modifying information in long-term autonomous memory.
     */
    WRITE_MEMORY,

    /**
     * Delegating tasks to another autonomous agent.
     */
    DELEGATE_AUTONOMOUS
}
