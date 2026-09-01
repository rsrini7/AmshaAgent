# Security

Amsha Agent is built with a defense-in-depth security posture. This document covers the cryptographic vault, sandbox isolation, audit chain, and governance system. For the architectural overview (agent loop, message pipeline, tool governance decision tree), see [ARCHITECTURE.md](./ARCHITECTURE.md).

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
| 8 | **Pre-policy tool interceptor** | `ToolGuardInterceptor` implementations | Tool-specific deterministic block, dedup, or approval decisions; `Allow` continues |
| 9 | **Tool boundary** | `ToolInvocationGuard` | Validation, dedup, unknown-tool block, HITL approval, monotonic post-policy risk |
| 10 | **Session isolation** | `AgentSessionService` per-key access | Async approve/deny, session-scoped API keys |

Layers 1–6 are **reflex/deterministic** (zero LLM tokens). Layer 7 is the LLM boundary — runs before/after every model call. Each layer assumes the layer below has already passed.

The optional post-policy risk hook runs only after layer 9's deterministic
checks provisionally allow a tool. It may require approval or block, but cannot replace
an existing block, deduplication result, or HITL decision. Missing implementations and
isolated classifier failures preserve the deterministic result and emit no payload data.

Classifier runtime controls are internal-only and cannot make requested state effective
without a registered ready active implementation. A disable rejects new active leases
while accepted work drains. Every state-changing request must successfully record the
mandatory `CLASSIFIER_RUNTIME_CHANGE_REQUESTED` event before mutation. Its detail is
limited to the fixed classifier identifier and requested boolean state.

---

## Cryptographic Vault

AES-256-GCM-SIV authenticated encryption with Argon2id key derivation.

- **Key derivation**: `Argon2id` (memory-hard, salt per encryption)
- **Cipher**: `AES/GCM-SIV/NoPadding` (misuse-resistant — nonce reuse does not break key)
- **Key storage**: `char[]` + `byte[]` in `AutoCloseable` wrappers, aggressively overwritten with zeros on close
- **Pepper**: Application-level secret (`AMSHA_VAULT_PEPPER`) mixed into key derivation — a database leak alone cannot decrypt secrets
- **Associated data (AAD)**: Bound to secret identity/key name — prevents ciphertext swapping attacks

**Operational Key-Rotation Policy**: Amsha Agent does not support automatic vault key rotation. In the event of a suspected compromise, wipe the `EncryptedSecret` table and re-provision all tenant secrets out-of-band.

---

## Immutable Audit Chain (WORM + HMAC)

Every significant event writes an `AuditEvent` with an HMAC-SHA256 chain linking records by sequence number:

```
AuditService.record(sessionId, userId, agentRole, SecurityEventType, detail)
  → AuditHashUtil.compute(id + sessionId + eventType + detail + timestamp, vaultKey)
  → AuditEventRepository.record() → persist
```

- **Tamper detection**: `AuditVerifier.verifyChain(sessionId)` re-computes HMAC for all events and confirms no tampering
- **66 event types**: loop events, tool events, approval events, injection/PII, vault, cron, skill, MCP, session, etc.
- **Dual drain threads**: `AsyncPersistenceEngine` drains two virtual threads (critical path / telemetry path)
- **Fail-closed**: `ComplianceViolationException` thrown when a mandatory audit write fails after all retries

---

## Sandbox Isolation

| Strategy | Isolation Level | Requirements |
|---|---|---|
| `podman` (default) | Rootless container | Podman installed, `amsha-sandbox` image built |
| `namespace` | Linux namespace | Linux host |
| `direct` | None | No sandbox (development only) |
| `none` | Disabled | Sandbox_exec tool unavailable |

**Podman sandbox features:**
- Ephemeral secret injection via RAM-backed tmpfs `.env` file — never as process arguments
- Capped containers: 512MB memory, 120s timeout
- Configurable network access (`AMSHA_SANDBOX_ALLOW_INTERNET`)
- Language runtimes: Python, Java, Node.js, Go, Bash

**Secret transport**: Tool runtime secrets are resolved from `SecretVault` and injected into the sandbox container via a memory-wiped `.env` file on a tmpfs mount — they never appear in process arguments or environment variables visible to host processes.

### MCP credential headers

Outbound HTTP MCP connections may carry credential headers (API keys, bearer tokens, tenant IDs). Header **values** live only in `SecretVault` under a stable owner-scoped partition (`USER:<userId>`); the durable connection record stores only an opaque vault reference and the normalized, non-secret header name. Values are resolved just-in-time into an `AutoCloseable` holder that zeroes its secret bytes after each request, and are never written to config files, logs, audit payloads, tool results, or API responses. Header names follow the RFC 7230 token grammar, are lower-cased for case-insensitive uniqueness, and reject transport-owned / hop-by-hop / framing names. Registration is a sensitive, HITL-gated operation.

---

## HITL (Human-in-the-Loop) Governance

| Autonomy Level | Behavior |
|---|---|
| `FULL` | Non-sensitive tools execute without approval; `NETWORK_OUTBOUND`, `EXECUTE_SHELL`, and governed media downloads remain HITL-gated |
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

## Authentication, Identity, and Multi-Admin Security

Amsha Agent adopts an **OIDC-first identity model with API-key operator fallback** to govern dashboard access, multi-user resource sharing, and system administration.

### 1. External Identity Providers (IdP)
- **Generic OIDC**: Supports OIDC authorization code flow with PKCE (RFC 7636) for standards-compliant identity managers (Keycloak, Authentik, Google). Asserts `RS256` asymmetric signatures for ID token validation against public JWKS and requires a valid, non-expired `exp` claim.
- **GitHub OAuth**: A lightweight standalone adapter to authenticate GitHub users and securely retrieve verified primary emails. Provider email verification is retained on the external profile and is not inferred merely from an email string.
- **Fixed Redirect URIs**: Callback endpoints are strictly constrained on the deployment side (`amsha.auth.idp.oidc.redirect-uri` / `amsha.auth.idp.github.redirect-uri`) to prevent open-redirect exploits.

### 2. Browser Session Lifecycle & CSRF
- **Opaque Sessions**: Human UI logins establish server-side revocable web sessions represented by high-entropy random session tokens. Only token SHA-256 digests are stored at rest.
- **Cookie Security**: Session tokens are delivered via `HttpOnly`, `SameSite=Lax`, and `Secure` cookies.
- **Token Rotation**: Sessions rotate after successful authentication, privilege upgrades, or periodically. A 30-second single-use grace overlap window allows in-flight concurrent requests to pass without interruption.
- **CSRF Protection**: All mutating endpoints require matching `X-CSRF-Token` header validation.

### 3. Role Hierarchy & Invitations
- **Workspace Roles**: Every deployment supports `OWNER`, `ADMIN`, and `MEMBER` privileges.
  - `OWNER`: Full administrative rights, workspace-wide credential rotation, invitation issuance, and ownership transfer.
  - `ADMIN`: Resource configuration, integration management, and non-owner member management.
  - `MEMBER`: Read-only/execution privileges over workspace connection channels without credential visibility.
- **Invitations**: expirable (24 hours), single-use invitation tokens allow adding administrators or members. Optional email constraints are accepted only when the authenticated provider profile reports a verified email address; an email value alone is insufficient.
- **Last Owner Invariant**: The system blocks any demotion, deletion, or inactivation that would leave a workspace with zero active `OWNER` users. The membership delete-and-count check is atomic across SQLite, PostgreSQL, and filesystem persistence implementations, preventing concurrent requests from removing the final owner.

### 4. First-Owner Bootstrap & Recovery
- Fresh deployments start in the `UNCLAIMED` status. The first caller on the loopback interface (`localhost`/`127.0.0.1`) can claim initial system ownership, generating a default workspace, the first `OWNER` identity, and an API credential.
- If the initial credential delivery fails, an owner may initiate a retry recovery from the loopback interface when in the `CREDENTIAL_ISSUED` state.

---

## Governed Media Download

Multimodal channel messages may carry `MediaAttachment` (image, document, audio) references that are **never** fetched by the channel adapter. Fetching is delegated to the governed `MediaDownloadPort` → `GovernedMediaDownloadService` (`amsha-security`, `OutboundPurpose.MEDIA_DOWNLOAD`), which resolves opaque `telegram://file/<file_id>` references through `GovernedTelegramMediaResolver`:

* **Streaming size enforcement** — `MediaBudgetLimits` defaults (`10 MiB` image, `25 MiB` document, `50 MiB` total per turn) checked incrementally during streaming; oversize fails closed before full buffering. MIME allowlist (`image/png`, `image/jpeg`, `image/webp`, `image/gif`, `application/pdf`, `audio/mpeg`, `audio/wav`, `audio/ogg`) is validated against `Content-Type`; the safe `audio/x-wav` platform alias is canonicalized to `audio/wav`.
* **SSRF / DNS rebinding** — `MediaSecurityValidator` blocks loopback (`127.0.0.0/8`, `::1`), link-local (`169.254.0.0/16`, `fe80::/10`), private (`10/8`, `172.16/12`, `192.168/16`), and metadata (`169.254.169.254`) targets on every hop, including `Location` redirects (max redirects, revalidated per hop). Hostname → IP resolved via `InetAddress` and re-checked.
* **Deterministic temp files** — `DownloadedMedia` (`AutoCloseable`, `DELETE_ON_CLOSE` semantic) writes to a governed temp file under the workspace temp dir; `close()` deletes and zeroes bookkeeping. No secret bytes are retained.
* **Governed HTTP** — via `GovernedHttpClient` / `OutboundHttpPolicyService` with `AMSHA_MEDIA_CONNECT_TIMEOUT_SECONDS` (10 s) / `AMSHA_MEDIA_READ_TIMEOUT_SECONDS` (30 s), `OutboundHttpException` on policy rejection, and audit `MEDIA_DOWNLOAD` event.

Port contract: `security-api/MediaDownloadPort.java`, `DownloadedMedia.java`, `MediaBudgetLimits.java`; impl: `security/media/GovernedMediaDownloadService.java`, `MediaSecurityValidator.java`. Config: `amsha.media.*` (`AMSHA_MEDIA_*_MB`, `AMSHA_MEDIA_*_TIMEOUT_SECONDS`) — see [CONFIGURATION.md](./CONFIGURATION.md#media-ingestion--download-limits).

## Related Docs

| Doc | What it covers |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Governance decision tree, guard layers overview |
| [Architecture_Invariants.md](./Architecture_Invariants.md) | Transaction rules, native-image, persistence contracts |
| [CONFIGURATION.md](./CONFIGURATION.md) | Vault pepper, HMAC key, sandbox config env vars |
| [AGENTS.md](../AGENTS.md) | Role-based governance bypass invariant (§2.9) |
