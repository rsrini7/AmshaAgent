# Audit Decision Replay & Tool Argument Serialization

> **Document type:** Architecture & compliance design proposal  
> **Status:** Backlog / Proposed  
> **Target modules:** `core`, `common`, `persistence-api`  
> **Governing invariants:** Architecture Invariants §3.2 (Audit & Compliance), §5.17 (Fail-closed Tool Admission), §5.24 (Secret & Credential Handling)  
> **Origin:** Propose vs. Decide architecture review & audit replayability feedback  
> **Date:** November 2, 2026  

---

## 1. Context and Problem Statement

AmshaAgent enforces a strict **Propose vs. Decide** boundary:
- **Propose:** The LLM engine proposes an action with inputs (`ReasoningStep` containing `action` and `actionInput`).
- **Decide:** The Java runtime deterministically decides whether to permit, block, or pause for approval via `ToolInvocationGuard` and `ToolGovernanceGuard`.

While the policy logic is implemented as plain Java code with extensive unit test coverage for allow/deny rules, a gap exists in **audit replayability**:
1. When a tool is invoked, `ToolRegistry.java` records a mandatory `SecurityEventType.TOOL_INVOKED` event containing only `"tool=" + toolName + " op=" + tool.requiredOperation()`. **The tool arguments (`params`) are omitted.**
2. When a tool is blocked by policy or role capability, `ToolGovernanceGuard.java` records `SecurityEventType.TOOL_BLOCKED` containing `"tool=" + toolName + " " + reason`, also omitting `params`.
3. In `ToolInvocationGuard.java`, `publishGuardCheck` emits trace stage `GUARD_CHECK` with `.withDetail(toolName)` without parameter metadata.
4. Only human-in-the-loop (HITL) approval pauses capture arguments today via `ApprovalFrame`.

Consequently, an auditor or compliance system cannot deterministically replay and verify an allow/deny decision against the original tool input payload.

---

## 2. Requirements and Goals

1. **Deterministic Decision Replay:** An auditor must be able to take an audit event's metadata `(agentRole, autonomyLevel, toolName, args)` and re-evaluate `ToolInvocationGuard` / `ToolGovernanceGuard` to verify that the recorded decision matches the expected policy outcome.
2. **Mandatory Credential & Secret Redaction:** Tool arguments must pass through `SecretRedactor` (and PII filters where configured) before persistence so credentials (API keys, passwords, bearer tokens, Telegram tokens) are never persisted in plaintext in audit logs.
3. **Payload Budget & Bounded Size:** Arguments must be serialized to compact JSON and bounded in length (e.g., truncated with a sentinel if exceeding safe audit payload thresholds) to prevent audit log bloating from large text blobs.
4. **Preserve HMAC Audit Integrity:** The changes must integrate into the existing sequence-numbered, HMAC-hashed audit trail (`AuditEventStorePort`, `SessionAuditSequencer`) without breaking schema migrations.

---

## 3. Implementation Design

### A. Parameter Redaction and Serialization
In `com.amsha.core.tools.ToolRegistry`:
```java
// Sanitize nulls and unknown schema keys (already present)
Map<String, Object> sanitizedParams = sanitizeParams(params, tool);

// Serialize to compact JSON and apply SecretRedactor
String serializedArgs = objectMapper.writeValueAsString(sanitizedParams);
String redactedArgs = SecretRedactor.redact(serializedArgs);

// Record in HMAC-chained audit log
auditService.record(
    ctx.sessionId(),
    ctx.userId(),
    ctx.agentRole(),
    SecurityEventType.TOOL_INVOKED,
    "tool=" + toolName + " op=" + tool.requiredOperation() + " args=" + redactedArgs);
```

### B. Capture Arguments on Policy Blocks
In `com.amsha.core.tools.ToolGovernanceGuard`:
```java
public void recordToolBlockedAudit(
        ToolExecutionContext ctx, String toolName, String reason, Map<String, Object> params) {
    String redactedArgs = sanitizeAndRedact(params);
    auditService.record(
        ctx.sessionId(),
        ctx.userId(),
        ctx.agentRole(),
        SecurityEventType.TOOL_BLOCKED,
        "tool=" + toolName + " " + reason + " args=" + redactedArgs);
}
```

### C. Guard Check Trace Enrichment
In `com.amsha.core.agent.loop.ToolInvocationGuard`:
- Include sanitized/redacted arguments in trace metadata when publishing `TraceStage.GUARD_CHECK` so OpenTelemetry and in-memory trace pub/sub match the audit logs.

### D. Audit Replay Verification Harness
Create `ToolDecisionReplayTest` under `core/src/test/java/com/amsha/core/audit/`:
- Parse historical audit event payloads.
- Re-run parameters through `ToolInvocationGuard.evaluate(...)`.
- Assert that the replay decision (`Allow`, `Blocked`, or `PauseForApproval`) matches the recorded outcome.

---

## 4. Work Breakdown & Acceptance Criteria

- [ ] **Argument Serialization Utility:** Add `AuditPayloadFormatter` or helper in `core` that safely serializes and runs `SecretRedactor.redact()` on tool arguments.
- [ ] **`ToolRegistry` Audit Enrichment:** Include redacted arguments in `TOOL_INVOKED`.
- [ ] **`ToolGovernanceGuard` Audit Enrichment:** Include redacted arguments in `TOOL_BLOCKED`.
- [ ] **Unit Tests:**
  - Verify arguments appear in `AuditEventRecord.eventDetail`.
  - Verify secrets (passwords, tokens, keys) are masked with `[REDACTED]`.
  - Verify payload size limits are respected.
- [ ] **Replay Verification Test:** Demonstrate deterministic replay of allow and deny decisions using recorded audit tuples.
