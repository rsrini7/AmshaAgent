package com.arcclaw.core.audit;

public enum AuditOutcome {
    FINISH,
    ERROR,
    BUDGET_EXCEEDED,
    APPROVAL_TIMEOUT,
    MAX_ITERATIONS,
    AWAITING_APPROVAL,
    SECURITY_EVENT
}
