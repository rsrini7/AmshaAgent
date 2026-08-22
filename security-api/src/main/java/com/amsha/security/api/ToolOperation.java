package com.amsha.security.api;

/**
 * Categories of tool operations for fine-grained autonomy control.
 */
public enum ToolOperation {
    /** Reading local files or metadata. */
    READ_FS(RiskLevel.LOW),

    /** Modifying or deleting local files. */
    WRITE_FS(RiskLevel.HIGH),

    /** Executing arbitrary shell commands or binaries. */
    EXECUTE_SHELL(RiskLevel.CRITICAL),

    /** External HTTP(S) requests. */
    NETWORK_OUTBOUND(RiskLevel.MEDIUM),

    /** Storing or retrieving secrets from the vault. */
    VAULT_ACCESS(RiskLevel.CRITICAL),

    /** Writing durable autonomous agent tasks (cron scheduling). */
    SCHEDULE_AUTONOMOUS(RiskLevel.MEDIUM),

    /** Storing or modifying information in long-term autonomous memory. */
    WRITE_MEMORY(RiskLevel.LOW),

    /** Delegating tasks to another autonomous agent. */
    DELEGATE_AUTONOMOUS(RiskLevel.MEDIUM);

    private final RiskLevel riskLevel;

    ToolOperation(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }
}
