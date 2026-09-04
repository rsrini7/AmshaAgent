package com.amsha.core.audit;

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
    TOOL_INVOKED, // every ToolRegistry.invokeTool() call
    TOOL_EXECUTED, // recorded at the end of tool execution
    TOOL_BLOCKED, // SecurityException from autonomy check
    TOOL_FAILED, // Audit

    // Approval flow
    APPROVAL_GRANTED, // user replied /approve
    APPROVAL_DENIED, // user replied /deny
    HITL_LEASE_REUSED, // guard satisfied a deterministic HITL pause from an in-session approval lease

    // Reflex layer
    PROMPT_INJECTION_BLOCKED, // PromptInjectionReflex matched
    PII_SCAN_REDACTED, // PiiScanReflex redacted >= 1 field
    REFLEX_COMMAND, // HaltReflex / ResetReflex / StatusReflex executed

    // Vault layer
    VAULT_ACCESS, // replaces VAULT_STORE/RETRIEVE for mandatory tracking
    VAULT_STORE, // SecretVaultImpl.store() called
    VAULT_RETRIEVE, // SecretVaultImpl.get() called
    VAULT_RETRIEVE_MISS, // SecretVaultImpl.get() returned Optional.empty

    // Checkpoint lifecycle
    CHECKPOINT_CREATED, // new entity persisted
    CHECKPOINT_COMPLETED, // stateful store markCompleted()
    CHECKPOINT_FAILED, // stateful store markFailed()
    CHECKPOINT_ORPHANED, // RateLimitRetryScheduler abandoned stale retry

    // Misc Security
    SECURITY,

    // Context memory
    CONTEXT_COMPACTED, // session history summary created

    // Compliance
    BUDGET_EXCEEDED, // standalone mandatory budget violation event

    // Cron lifecycle
    CRONCREATED, // ScheduleTaskTool persisted a new cron row
    CRONFIRED, // AgentCronScheduler dispatched successfully
    CRONFAILED, // AgentCronScheduler caught exception
    CRONDELETED, // Reflex or tool deleted a cron row

    // Skills lifecycle - telemetry tier
    SKILL_LOADED, // SkillLoader completed a full load cycle
    SKILL_REFRESHED, // Manual refresh via GET /api/v1/skills/refresh
    MCP_TOOL_INVOKED, // External call to an internal tool via MCP server
    STRUCTURED_OUTPUT_RETRY, // Single-retry path for schema-correction
    DELEGATE_APPROVAL, // Admin approved on behalf of another user. §7.33

    // Session isolation (Per-key isolation)
    UNAUTHORIZED_ACCESS, // Cross-key session access attempt blocked
    SESSION_INSPECTED, // Privileged replay inspection of another user's session

    // Session lifecycle
    SESSION_CREATED, // new agent session created via REST
    SESSION_TERMINATED, // session force-terminated or reaped

    // Configuration changes
    SETTINGS_CHANGED, // LLM provider/model/prompt updated
    SETTINGS_RESET, // all settings nuked via /settings/reset
    CLASSIFIER_RUNTIME_CHANGE_REQUESTED, // internal classifier kill-switch mutation requested
    CLASSIFIER_RUNTIME_CHANGE_ACCEPTED, // classifier kill-switch mutation accepted
    CLASSIFIER_RUNTIME_CHANGE_REJECTED, // classifier kill-switch mutation rejected after REQUESTED
    ONNX_MODEL_UNLOAD_REQUESTED, // internal ONNX model lifecycle mutation
    ONBOARDING_COMPLETED, // onboarding wizard finished

    // Cron admin operations (REST)
    CRON_PAUSED, // cron job paused via REST
    CRON_RESUMED, // cron job resumed via REST
    CRON_FORCE_TRIGGERED, // cron job force-fired via REST

    // Skill admin operations (REST)
    SKILL_CREATED, // skill created/updated via REST
    SKILL_DELETED, // skill deleted via REST

    // MCP admin operations (REST)
    MCP_CONNECTION_REQUESTED, // outbound MCP connection requested
    MCP_TOOL_POLICY_ENABLED, // MCP tool policy granted executable capability
    MCP_SERVER_CONNECTED, // remote MCP server connected
    MCP_SERVER_DISCONNECTED, // remote MCP server disconnected
    MCP_CONNECTION_VALIDATION_FAILED, // outbound MCP handshake failed
    MCP_CREDENTIAL_CONFIGURED, // vault-backed MCP credential created
    MCP_CREDENTIAL_ROTATED, // vault-backed MCP credential replaced
    MCP_CREDENTIAL_REMOVED, // vault-backed MCP credential removed
    MCP_CREDENTIAL_CLEANUP_FAILED, // vault-backed MCP credential cleanup failed and was queued

    // Onboarding extension lifecycle (telemetry)
    ONBOARDING_EXTENSION_SHOWN,
    ONBOARDING_EXTENSION_SKIPPED,
    ONBOARDING_EXTENSION_VALIDATED,
    ONBOARDING_EXTENSION_COMMITTED,
    ONBOARDING_EXTENSION_FAILED,

    // Auth
    AUTH_REJECTED, // API key auth failed (invalid/missing/rate-limited)
    WS_TOKEN_ISSUED, // WebSocket auth token issued

    // Delegation
    DELEGATION_DISPATCHED, // coordinator delegated to sub-agent

    // RAG and Memory writes
    MEMORY_INGESTED, // IngestDocumentTool indexed content
    MEMORY_WRITE_MUTATED, // CoreMemoryAppend/Replace/ArchivalInsert wrote a block or chunk

    // Media ingestion
    MEDIA_DOWNLOADED, // Governed external media attachment download
    MEDIA_DOWNLOAD_DENIED; // Governed media download rejected before transport

    /**
     * Returns true if a write failure for this event type must HALT the agent
     * (fail-closed posture). A mandatory audit failure raises
     * {@link ComplianceViolationException} and prevents further LLM calls.
     */
    public boolean isMandatory() {
        return switch (this) {
            case VAULT_ACCESS,
                    TOOL_INVOKED,
                    TOOL_EXECUTED,
                    BUDGET_EXCEEDED,
                    PROMPT_INJECTION_BLOCKED,
                    APPROVAL_GRANTED,
                    APPROVAL_DENIED,
                    CRONFIRED,
                    CRONFAILED,
                    MCP_TOOL_INVOKED,
                    DELEGATE_APPROVAL,
                    UNAUTHORIZED_ACCESS,
                    SESSION_INSPECTED,
                    MEMORY_INGESTED,
                    MEDIA_DOWNLOADED,
                    AUTH_REJECTED,
                    SESSION_CREATED,
                    SESSION_TERMINATED,
                    SETTINGS_CHANGED,
                    SETTINGS_RESET,
                    CLASSIFIER_RUNTIME_CHANGE_REQUESTED,
                    CLASSIFIER_RUNTIME_CHANGE_ACCEPTED,
                    CLASSIFIER_RUNTIME_CHANGE_REJECTED,
                    ONNX_MODEL_UNLOAD_REQUESTED,
                    CRON_PAUSED,
                    CRON_RESUMED,
                    CRON_FORCE_TRIGGERED,
                    SKILL_CREATED,
                    SKILL_DELETED,
                    MCP_SERVER_CONNECTED,
                    MCP_SERVER_DISCONNECTED,
                    MCP_CONNECTION_REQUESTED,
                    MCP_TOOL_POLICY_ENABLED,
                    MCP_CONNECTION_VALIDATION_FAILED,
                    MEDIA_DOWNLOAD_DENIED,
                    MCP_CREDENTIAL_CONFIGURED,
                    MCP_CREDENTIAL_ROTATED,
                    MCP_CREDENTIAL_REMOVED,
                    MCP_CREDENTIAL_CLEANUP_FAILED,
                    DELEGATION_DISPATCHED -> true;
            default -> false;
        };
    }
}
