package com.arcclaw.core.audit;

public enum SecurityEventType {
    // AgentLoop outcomes
    LOOP_FINISH,
    LOOP_ERROR,
    LOOP_BUDGET_EXCEEDED,
    LOOP_MAX_ITERATIONS,
    LOOP_AWAITING_APPROVAL,
    LOOP_AWAITING_RETRY,
    INVALID_ROLE_FALLBACK,
    LEGACY_CHANNEL_FALLBACK,

    // Tool layer
    TOOL_INVOKED,          // every ToolRegistry.invokeTool() call
    TOOL_EXECUTED,         // recorded at the end of tool execution
    TOOL_BLOCKED,          // SecurityException from autonomy check
    TOOL_FAILED,           // Audit

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
    CHECKPOINT_ORPHANED,   // RateLimitRetryScheduler abandoned stale retry

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

    // Skills lifecycle (Phase 60) - telemetry tier
    SKILL_LOADED,       // SkillLoader completed a full load cycle
    SKILL_REFRESHED,    // Manual refresh via GET /api/v1/skills/refresh
    MCP_TOOL_INVOKED,   // External call to an internal tool via MCP server
    STRUCTURED_OUTPUT_RETRY, // Single-retry path for schema-correction
    DELEGATE_APPROVAL,  // Admin approved on behalf of another user. §7.33

    // Session isolation (Per-key isolation)
    UNAUTHORIZED_ACCESS, // Cross-key session access attempt blocked
    
    // Session lifecycle
    SESSION_CREATED,       // new agent session created via REST
    SESSION_TERMINATED,    // session force-terminated or reaped

    // Configuration changes
    SETTINGS_CHANGED,      // LLM provider/model/prompt updated
    SETTINGS_RESET,        // all settings nuked via /settings/reset
    ONBOARDING_COMPLETED,  // onboarding wizard finished

    // Cron admin operations (REST)
    CRON_PAUSED,           // cron job paused via REST
    CRON_RESUMED,          // cron job resumed via REST
    CRON_FORCE_TRIGGERED,  // cron job force-fired via REST

    // Skill admin operations (REST)
    SKILL_CREATED,         // skill created/updated via REST
    SKILL_DELETED,         // skill deleted via REST

    // MCP admin operations (REST)
    MCP_SERVER_CONNECTED,  // remote MCP server connected
    MCP_SERVER_DISCONNECTED, // remote MCP server disconnected

    // Auth
    AUTH_REJECTED,         // API key auth failed (invalid/missing/rate-limited)
    WS_TOKEN_ISSUED,       // WebSocket auth token issued

    // Delegation
    DELEGATION_DISPATCHED, // coordinator delegated to sub-agent

    // RAG and Memory writes
    MEMORY_INGESTED;       // IngestDocumentTool indexed content

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
                 UNAUTHORIZED_ACCESS, MEMORY_INGESTED, AUTH_REJECTED,
                 SESSION_CREATED, SESSION_TERMINATED,
                 SETTINGS_CHANGED, SETTINGS_RESET,
                 CRON_PAUSED, CRON_RESUMED, CRON_FORCE_TRIGGERED,
                 SKILL_CREATED, SKILL_DELETED,
                 MCP_SERVER_CONNECTED, MCP_SERVER_DISCONNECTED,
                 DELEGATION_DISPATCHED -> true;
            default -> false;
        };
    }
}
