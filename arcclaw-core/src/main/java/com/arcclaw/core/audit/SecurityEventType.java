package com.arcclaw.core.audit;

public enum SecurityEventType {
    // AgentLoop outcomes
    LOOP_FINISH,
    LOOP_ERROR,
    LOOP_BUDGET_EXCEEDED,
    LOOP_MAX_ITERATIONS,
    LOOP_AWAITING_APPROVAL,
    INVALID_ROLE_FALLBACK,
    LEGACY_CHANNEL_FALLBACK,

    // Tool layer
    TOOL_INVOKED,          // every ToolRegistry.invokeTool() call
    TOOL_EXECUTED,         // recorded at the end of tool execution
    TOOL_BLOCKED,          // SecurityException from autonomy check

    // Approval flow
    APPROVAL_GRANTED,      // user replied /approve
    APPROVAL_DENIED,       // user replied /deny

    // Reflex layer
    PROMPT_INJECTION_BLOCKED,  // PromptInjectionReflex matched
    PII_SCAN_REDACTED,         // PiiScanReflex redacted >= 1 field
    REFLEX_COMMAND,            // HaltReflex / ResetReflex / StatusReflex executed

    // Vault layer
    VAULT_ACCESS,          // Phase 44: replaces VAULT_STORE/RETRIEVE for mandatory tracking
    VAULT_STORE,           // SecretVaultImpl.store() called
    VAULT_RETRIEVE,        // SecretVaultImpl.get() called
    VAULT_RETRIEVE_MISS,   // SecretVaultImpl.get() returned Optional.empty

    // Checkpoint lifecycle
    CHECKPOINT_CREATED,    // new entity persisted
    CHECKPOINT_COMPLETED,  // stateful store markCompleted()
    CHECKPOINT_FAILED,     // stateful store markFailed()

    // Misc Security
    SECURITY,

    // Context memory
    CONTEXT_COMPACTED,     // session history summary created

    // Compliance
    BUDGET_EXCEEDED,       // Phase 44: standalone mandatory budget violation event

    // Cron lifecycle (Phase 46)
    CRONCREATED,           // ScheduleTaskTool persisted a new cron row
    CRONFIRED,             // AgentCronScheduler dispatched successfully
    CRONFAILED,            // AgentCronScheduler caught exception
    CRONDELETED,           // Reflex or tool deleted a cron row

    // Skills lifecycle (Phase 60) — telemetry tier
    SKILL_LOADED,       // SkillLoader completed a full load cycle
    SKILL_REFRESHED,    // Manual refresh via GET /api/v1/skills/refresh
    MCP_TOOL_INVOKED,   // External call to an internal tool via MCP server
    STRUCTURED_OUTPUT_RETRY, // Single-retry path for schema-correction
    DELEGATE_APPROVAL,  // Admin approved on behalf of another user. §7.33

    // Session isolation (Per-key isolation)
    UNAUTHORIZED_ACCESS; // Cross-key session access attempt blocked

    /**
     * Returns true if a write failure for this event type must HALT the agent
     * (fail-closed posture). A mandatory audit failure raises
     * {@link ComplianceViolationException} and prevents further LLM calls.
     */
    public boolean isMandatory() {
        return switch (this) {
            case VAULT_ACCESS, TOOL_INVOKED, TOOL_EXECUTED, BUDGET_EXCEEDED,
                 PROMPT_INJECTION_BLOCKED, APPROVAL_GRANTED, APPROVAL_DENIED,
                 CRONFIRED, CRONFAILED, MCP_TOOL_INVOKED, DELEGATE_APPROVAL,
                 UNAUTHORIZED_ACCESS -> true;
            default -> false;
        };
    }
}
