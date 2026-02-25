package com.arcclaw.core.audit;

public enum SecurityEventType {
    // AgentLoop outcomes
    LOOP_FINISH, 
    LOOP_ERROR, 
    LOOP_BUDGET_EXCEEDED, 
    LOOP_MAX_ITERATIONS, 
    LOOP_AWAITING_APPROVAL,

    // Tool layer
    TOOL_INVOKED,          // every ToolRegistry.invokeTool() call
    TOOL_BLOCKED,          // SecurityException from autonomy check

    // Approval flow
    APPROVAL_GRANTED,      // user replied /approve
    APPROVAL_DENIED,       // user replied /deny

    // Reflex layer
    PROMPT_INJECTION_BLOCKED,  // PromptInjectionReflex matched
    PII_SCAN_REDACTED,         // PiiScanReflex redacted >= 1 field
    REFLEX_COMMAND,            // HaltReflex / ResetReflex / StatusReflex executed

    // Vault layer
    VAULT_STORE,           // SecretVaultImpl.store() called
    VAULT_RETRIEVE,        // SecretVaultImpl.get() called
    VAULT_RETRIEVE_MISS,   // SecretVaultImpl.get() returned Optional.empty

    // Checkpoint lifecycle
    CHECKPOINT_CREATED,    // new entity persisted
    CHECKPOINT_COMPLETED,  // stateful store markCompleted()
    CHECKPOINT_FAILED      // stateful store markFailed()
}
