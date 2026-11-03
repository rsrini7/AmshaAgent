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

Consequently, the current audit event does not contain enough information to reconstruct the original policy decision. This proposal treats bounded audit details as evidence for review and uses a separate fixture corpus for CI regression; redacted or truncated audit arguments are not treated as exact replay inputs.

---

## 2. Requirements and Goals

1. **Policy Regression Replay in CI:** Replay a small, curated corpus of representative tool-call sequences through the Java policy and assert that each decision remains stable.
2. **Useful Audit Evidence:** Persist bounded, redacted arguments with the decision context needed to explain the event, including tool, operation, role, autonomy level, and a stable policy/configuration identifier where available.
3. **Mandatory Credential & Secret Redaction:** Tool arguments must pass through `SecretRedactor` (and PII filters where configured) before persistence so credentials (API keys, passwords, bearer tokens, Telegram tokens) are never persisted in plaintext in audit logs.
4. **Payload Budget & Bounded Size:** Arguments must be serialized to compact JSON and bounded in length (e.g., truncated with a sentinel if exceeding safe audit payload thresholds) to prevent audit log bloating from large text blobs.
5. **Preserve HMAC Audit Integrity:** The changes must integrate into the existing sequence-numbered, HMAC-hashed audit trail (`AuditEventStorePort`, `SessionAuditSequencer`) without breaking schema migrations.

Redacted or truncated audit arguments are evidence for review, not necessarily the original inputs needed for exact policy re-evaluation. Exact audit replay would require preserving complete decision inputs and the corresponding policy/configuration state; that retention design is outside this proposal unless explicitly added to its scope.

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
Update every block-audit call site, including role-capability and autonomy denials, to pass the parameters that were evaluated.

### C. Guard Check Trace Enrichment
In `com.amsha.core.agent.loop.ToolInvocationGuard`:
- Thread parameters through the `publishGuardCheck` call sites and include only sanitized/redacted arguments in trace metadata, so OpenTelemetry and in-memory trace pub/sub match the audit evidence without exposing raw inputs.

### D. Golden-Session Policy Regression Corpus
Create a small, checked-in corpus of sanitized tool-call sequences derived from representative real agent sessions, and replay it in CI through the Java policy path using `ToolInvocationGuard.evaluate(...)`.

Each fixture must contain only the context needed to reproduce the policy decision: ordered tool calls and parameters, role, autonomy level, and any relevant goal, history, skill snapshot, or admission/configuration state. Record the expected decision for each call (`Allow`, `Blocked`, or `PauseForApproval`). Use the real policy implementation with fixed local collaborators; the replay must not call an LLM or access the network.

Keep fixtures minimal and sanitized. Do not check in raw production transcripts, credentials, or unnecessary personal data. When a deliberate policy change changes a corpus decision, update the expected result with a short rationale so the behavior change is reviewed explicitly.

This is a regression test, not a parser for historical audit records. It must not use redacted or truncated audit arguments as if they were the original policy inputs.

### E. Audit Replay Boundary
The audit event should preserve the decision outcome and enough versioned context to explain it. The HMAC chain protects event integrity, but redacted/truncated arguments and missing policy state prevent a guarantee of exact re-evaluation from the audit record alone. If exact forensic replay is required, define a separate protected retention mechanism for complete decision inputs and policy state before claiming that capability.

---

## 4. Work Breakdown & Acceptance Criteria

- [ ] **Argument Serialization Utility:** Add `AuditPayloadFormatter` or helper in `core` that safely serializes and runs `SecretRedactor.redact()` on tool arguments.
- [ ] **`ToolRegistry` Audit Enrichment:** Include redacted arguments in `TOOL_INVOKED`.
- [ ] **`ToolGovernanceGuard` Audit Enrichment:** Include redacted arguments in `TOOL_BLOCKED`.
- [ ] **Unit Tests:**
  - Verify arguments appear in `AuditEventRecord.eventDetail`.
  - Verify secrets (passwords, tokens, keys) are masked with `[REDACTED]`.
  - Verify payload size limits are respected.
  - Verify audit evidence includes the policy outcome and available role/autonomy context for allow, block, and approval-required decisions.
- [ ] **Golden-Session Replay Test:** Check in at least three sanitized, representative session fixtures and replay their ordered tool calls through the Java policy in CI; fail when a decision changes unexpectedly.
- [ ] **Audit Replay Scope:** Document which events are explainable from redacted audit records and keep any exact-replay guarantee conditional on a separately designed protected retention mechanism.
