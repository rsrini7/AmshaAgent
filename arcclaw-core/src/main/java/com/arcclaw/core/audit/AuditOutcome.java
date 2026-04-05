package com.arcclaw.core.audit;

public enum AuditOutcome {
    FINISH,
    ERROR,
    BUDGET_EXCEEDED,
    APPROVAL_TIMEOUT,
    MAX_ITERATIONS,
    AWAITING_APPROVAL,
    AWAITING_RETRY,
    SECURITY_EVENT
}
