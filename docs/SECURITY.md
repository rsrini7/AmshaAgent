# Security

ArcClaw is built with a defense-in-depth security posture. This document covers the cryptographic vault, sandbox isolation, audit chain, and governance system. For the architectural overview (agent loop, message pipeline, tool governance decision tree), see [ARCHITECTURE.md](./ARCHITECTURE.md).

---

## 10 Guard Layers

| # | Layer | Component | What |
|---|---|---|---|
| 1 | **Channel gate** | `AllowlistHandler` | Channel user allowlist (Telegram numeric ID check) |
| 2 | **Injection match** | `PromptInjectionReflex` | 25 regex patterns across 5 categories (governance bypass, exfiltration, identity hijacking, direct override, context wipe) |
| 3 | **PII detection** | `PiiScanReflex` | 12 PII types (JWT, email, credit card, Aadhaar, SSN, PAN, PHONE, etc.) — blocks or redacts |
| 4 | **Destructive ops** | `DestructiveVocabularyFilter` | Routes destructive-language inputs to reduced autonomy |
| 5 | **Goal sanitize** | `AgentDispatchHandler` PII re-sanitizer | Applies `piiSanitizer.sanitize()` to goal before agent dispatch |
| 6 | **HITL boundary** | `CheckpointResumeCoordinator` / `ToolInvocationGuard` | Approval frames, autonomy policy, delegated approval |
| 7 | **LLM boundary** | `GuardrailCoordinator` + `PreLlmGuard`/`PostLlmGuard` | Injection check (`InjectionInputGuard`), PII sanitize (`PiiInputGuard`), response validation (`ParseabilityOutputGuard`) |
| 8 | **Tool boundary** | `ToolInvocationGuard` | Dedup, unknown-tool block, HITL approval, GitHub-specific intent guard |
| 9 | **Tool interceptor** | `GithubToolGuardInterceptor` | Cross-loop dedup for GitHub, intent validation |
| 10 | **Session isolation** | `AgentSessionService` per-key access | Async approve/deny, session-scoped API keys |

Layers 1–6 are **reflex/deterministic** (zero LLM tokens). Layer 7 is the LLM boundary — runs before/after every model call. Each layer assumes the layer below has already passed.

---

## Cryptographic Vault

AES-256-GCM-SIV authenticated encryption with Argon2id key derivation.

- **Key derivation**: `Argon2id` (memory-hard, salt per encryption)
- **Cipher**: `AES/GCM-SIV/NoPadding` (misuse-resistant — nonce reuse does not break key)
- **Key storage**: `char[]` + `byte[]` in `AutoCloseable` wrappers, aggressively overwritten with zeros on close
- **Pepper**: Application-level secret (`ARCCLAW_VAULT_PEPPER`) mixed into key derivation — a database leak alone cannot decrypt secrets
- **Associated data (AAD)**: Bound to secret identity/key name — prevents ciphertext swapping attacks

**Operational Key-Rotation Policy**: ArcClaw does not support automatic vault key rotation. In the event of a suspected compromise, wipe the `EncryptedSecret` table and re-provision all tenant secrets out-of-band.

---

## Immutable Audit Chain (WORM + HMAC)

Every significant event writes an `AuditEvent` with an HMAC-SHA256 chain linking records by sequence number:

```
AuditService.record(sessionId, userId, agentRole, SecurityEventType, detail)
  → AuditHashUtil.compute(id + sessionId + eventType + detail + timestamp, vaultKey)
  → AuditEventRepository.record() → persist
```

- **Tamper detection**: `AuditVerifier.verifyChain(sessionId)` re-computes HMAC for all events and confirms no tampering
- **61 event types**: loop events, tool events, approval events, injection/PII, vault, cron, skill, MCP, session, etc.
- **Dual drain threads**: `AsyncPersistenceEngine` drains two virtual threads (critical path / telemetry path)
- **Fail-closed**: `ComplianceViolationException` thrown when a mandatory audit write fails after all retries

---

## Sandbox Isolation

| Strategy | Isolation Level | Requirements |
|---|---|---|
| `podman` (default) | Rootless container | Podman installed, `arcclaw-sandbox` image built |
| `namespace` | Linux namespace | Linux host |
| `direct` | None | No sandbox (development only) |
| `none` | Disabled | Sandbox_exec tool unavailable |

**Podman sandbox features:**
- Ephemeral secret injection via RAM-backed tmpfs `.env` file — never as process arguments
- Capped containers: 512MB memory, 120s timeout
- Configurable network access (`ARCCLAW_SANDBOX_ALLOW_INTERNET`)
- Language runtimes: Python, Java, Node.js, Go, Bash

**Secret transport**: Tool runtime secrets are resolved from `SecretVault` and injected into the sandbox container via a memory-wiped `.env` file on a tmpfs mount — they never appear in process arguments or environment variables visible to host processes.

---

## HITL (Human-in-the-Loop) Governance

| Autonomy Level | Behavior |
|---|---|
| `FULL` | All tools execute without approval |
| `SUPERVISED` (default) | Sensitive operations (`NETWORK_OUTBOUND`, `EXECUTE_SHELL`) pause for approval |
| `READONLY` | Only read operations allowed; all writes blocked |

**Approval flow:**
1. Tool requires SUPERVISED → `AwaitingApproval` checkpoint persisted
2. SSE + channel notification (Telegram inline keyboard / Web UI)
3. User approves/denies per-tool or batch
4. Approved tools re-invoke; denied tools skipped
5. Agent loop continues with results

**Trusted role bypass**: Roles with `requiresApproval=false` skip non-sensitive op checks. `NETWORK_OUTBOUND` and `EXECUTE_SHELL` always require HITL regardless. See [Architecture_Invariants.md §4.10.2](./Architecture_Invariants.md#4102-role-based-tool-governance-bypass-invariant-rolegovernancebypass).

**Approval timeout**: Configurable; stale checkpoints auto-denied on restart (all channels).

---

## Related Docs

| Doc | What it covers |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Governance decision tree, guard layers overview |
| [Architecture_Invariants.md](./Architecture_Invariants.md) | Transaction rules, native-image, persistence contracts |
| [CONFIGURATION.md](./CONFIGURATION.md) | Vault pepper, HMAC key, sandbox config env vars |
| [AGENTS.md](../AGENTS.md) | Role-based governance bypass invariant (§2.9) |
