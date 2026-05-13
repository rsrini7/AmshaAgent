package com.arcclaw.core.security;

import com.arcclaw.core.governance.ApprovalFrame;

/**
 * Categories of tool operations for fine-grained autonomy control.
 */
public enum ToolOperation {
    /** Reading local files or metadata. */
    READ_FS(ApprovalFrame.RiskLevel.LOW),

    /** Modifying or deleting local files. */
    WRITE_FS(ApprovalFrame.RiskLevel.HIGH),

    /** Executing arbitrary shell commands or binaries. */
    EXECUTE_SHELL(ApprovalFrame.RiskLevel.CRITICAL),

    /** External HTTP(S) requests. */
    NETWORK_OUTBOUND(ApprovalFrame.RiskLevel.MEDIUM),

    /** Storing or retrieving secrets from the vault. */
    VAULT_ACCESS(ApprovalFrame.RiskLevel.CRITICAL),

    /** Writing durable autonomous agent tasks (cron scheduling). */
    SCHEDULE_AUTONOMOUS(ApprovalFrame.RiskLevel.MEDIUM),

    /** Storing or modifying information in long-term autonomous memory. */
    WRITE_MEMORY(ApprovalFrame.RiskLevel.LOW),

    /** Delegating tasks to another autonomous agent. */
    DELEGATE_AUTONOMOUS(ApprovalFrame.RiskLevel.MEDIUM);

    private final ApprovalFrame.RiskLevel riskLevel;

    ToolOperation(ApprovalFrame.RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public ApprovalFrame.RiskLevel riskLevel() {
        return riskLevel;
    }
}
