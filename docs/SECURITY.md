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

## Secret Storage & Vault Lifecycle Architecture

### 1. Two-Tier Secret Architecture
Amsha Agent partitions secrets into two distinct lifecycles based on bootstrapping constraints:

```
┌─────────────────────────────────────────────────────────────┐
│  TIER 1 - Bootstrap Secrets (.env / Environment)             │
│  Read by AppBootstrap BEFORE Quarkus / CDI / DB is online   │
│                                                             │
│  AMSHA_VAULT_PEPPER   ← master pepper for vault derivation  │
│  AMSHA_DATA_DIR       ← database and storage paths          │
│  AMSHA_AUDIT_HMAC_KEY ← stable audit signing key (prod)     │
│  QUARKUS_PROFILE      ← active execution profile            │
│  AMSHA_<PROVIDER>_API_KEY ← provider boot configuration     │
└─────────────────────────────────────────────────────────────┘
                            ↓ Quarkus boots, database connection online
┌─────────────────────────────────────────────────────────────┐
│  TIER 2 - Tool & Integration Secrets (SecretVault DB)       │
│  Encrypted at rest in DB; resolved lazily post-boot         │
│                                                             │
│  GITHUB_TOKEN           ← Coder agent credential            │
│  AMSHA_TOOLS_SERPER_API_KEY ← Web search tool credential    │
│  Outbound MCP Headers   ← Tenant-scoped service tokens      │
└─────────────────────────────────────────────────────────────┘
```

The bootstrap slot is intentionally generic: new deployments should use the selected provider's canonical `AMSHA_<PROVIDER>_API_KEY` variable. `AMSHA_LLM_API_KEY` remains a legacy bootstrap/system-vault compatibility key in older paths and is not a substitute for that provider credential.

### 2. Build-Time Native Environment Shielding (`build.env`)
GraalVM `native-image` captures compile-time environment variables and bakes their literal values directly into the binary's data segments. To prevent developer `.env` credentials from being compiled into distributable binaries:
- The `build:native` and `native-wsl2.sh` tasks temporarily shield the workspace by renaming `.env` to `.env.build-backup` before invoking Maven, restoring it on exit.
- If static non-secret configuration must be available to native compilation, developers provide an explicit `build.env` file, which is loaded without exposing active development secrets.

### 3. The Vault Pepper Bootstrap Paradox
`AMSHA_VAULT_PEPPER` represents the external cryptographic anchor. Because reading or decrypting the vault requires the pepper, the pepper cannot reside inside the vault itself. It must be provided via the host environment, `.env`, or an external secrets manager (KMS/HSM).

### 4. Dev Secret Seeding (`DevSecretSeeder`) & Production Boundaries
- `AMSHA_AUTO_SEED_SECRETS` defaults to `false` in production (`.env.example`, `application.yaml`, and `DevSecretSeeder.java`).
- In multi-tenant environments, Tier 2 secrets are submitted through authenticated onboarding/settings flows or the guarded `/vault KEY=VALUE` command and encrypted under user-specific session scopes (`USER:<userId>`). There is no public `/api/v1/vault/keys` endpoint.
- When explicitly enabled (`AMSHA_AUTO_SEED_SECRETS=true`) in single-user development or local workflows, `DevSecretSeeder` executes upon the `StartupEvent`, copying tool keys from the environment into the encrypted vault for developer convenience.

### 5. HMAC Audit Key Seeding Lifecycle
- **Dev/Test (`seedAuditHmacKey`)**: Uses an ephemeral 32-byte key generated via `SecureRandom` on each boot. This ensures frequent test database wipes do not preserve dangling HMAC chains from prior sessions.
- **Prod (`seedAuditHmacKeyFromEnv`)**: Seeds the HMAC key from `AMSHA_AUDIT_HMAC_KEY` to guarantee that audit event signatures remain verifiable across process restarts and service upgrades.


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

---

## Governed Outbound LLM Egress (`GovernedLangChainHttpClient`)

Outbound HTTP connections to external LLM providers (reasoning models and Model Arena comparisons) are mediated by `GovernedLangChainHttpClient` and `GovernedTransportService` (`OutboundPurpose.PROVIDER_API_CALL`):

* **SSRF Defense & IP Validation**: Destination IPs are validated against an IP blocklist that rejects loopback, link-local, private LAN ranges, and AWS/GCP/Azure instance metadata endpoints (`169.254.169.254`).
* **Header Sanitization**: Hop-by-hop headers (`Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Transfer-Encoding`, etc.) and restricted internal headers are stripped before dispatch.
* **Credential Leak Prevention**: Inspects outbound payloads to prevent echoing system vault pepper, root API keys, or raw environment secrets.
* **Actor Binding via `ScopedValue`**: Outbound HTTP requests propagate the authenticated user identity (`USER_ID`) into the governed transport boundary, ensuring accurate attribution in logs and audit events.
* **Tamper-Evident Audit Logging**: Successful dispatches record `EGRESS_TRANSPORT_SUCCESS` in the append-only HMAC audit chain before network transmission. Blocked dispatches record `EGRESS_TRANSPORT_BLOCKED`.
* **Telemetry**: Emits `amsha.transport.requests.total` and `amsha.transport.duration` Micrometer metrics categorized by purpose and status.

---

## Governed HTTP Transport & DNS Pinning (`GovernedHttpClient` & `GovernedHttpDnsPinning`)

> **Module note.** These classes are implemented in **`amsha-core`** (`com.amsha.core.security.http`), having
> moved down from `amsha-security` because `amsha-core` MUST NOT depend on an impl-tier module
> (`docs/Architecture_Invariants.md` §1.8, Rule 17) and core's remote decision engine needs the governed
> transport. `com.amsha.security.http` retains thin delegating wrappers for the HTTP client types.
> `OutboundHttpPolicyService` is in `amsha-core`; `GovernedTransportService` orchestration and approval
> receipts remain in `amsha-security`.

All outbound HTTP connections (LLM provider API calls, speech provider API calls, Model Arena comparisons, remote MCP handshakes, and media downloads) are mediated by `GovernedHttpClient` and `GovernedTransportService` under a typed `OutboundPurpose`:

* **SSRF Defense & IP Safety**: Destination and proxy hostnames are resolved and validated via `GovernedHttpDnsPinning.validateAddressSafety()`. The resolver rejects loopback (`127.0.0.0/8`, `::1`), link-local (`169.254.0.0/16`, `fe80::/10`), site-local/private LAN (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), multicast (`224.0.0.0/4`), and cloud instance metadata endpoints (`169.254.169.254`).
* **Trusted-Proxy Socket Pinning**: When routing through an outbound egress proxy, `GovernedHttpDnsPinning.resolveProxy()` pre-resolves the proxy host, validates address safety, and constructs a `PinnedProxy` binding the connection to a validated `pinnedAddress` on the `HttpClient`.
* **Pre-Connect DNS Revalidation vs. Direct-Egress Socket Pinning Limitation**: For direct remote connections, `GovernedHttpClient` performs pre-connect DNS revalidation via `GovernedHttpDnsPinning.validateHostDns()` before issuing requests. Because the standard JDK `HttpClient` resolves hostnames internally upon connection without raw socket-level binding, direct remote connections remain subject to potential DNS rebinding between validation and connect (TOCTOU). Complete socket-level pinning for direct remote egress is deferred to an infrastructure trusted egress proxy that owns resolution and connection pinning (mirroring the ADR transport limitation).
* **Checkpoint-Backed Operation Grants**: Outbound operations requiring user authorization (such as `register_mcp_server` or sensitive probes) consume single-use `approvalToken` receipts minted by `ApprovalReceiptIssuer` upon durable checkpoint approval. The receipt binds the actor, session, purpose, and target host, and is verified and consumed before connection establishment.
* **Header Sanitization & Credential Protection**: Hop-by-hop headers and restricted internal headers are stripped before dispatch. Staged credential headers (such as MCP authentication tokens) are resolved just-in-time from `SecretVault` and wiped immediately in `finally` blocks. Sensitive credential headers — including `Authorization`, `Cookie`, `X-API-Key`, and Gnani's `X-API-Key-ID` — are not forwarded across cross-origin redirects.

---

## Read-Only Database Console Security

The internal operator database console (`POST /api/v1/admin/db/query`) enforces defense-in-depth isolation against SQL injection, privilege escalation, and unintended mutations:

* **AST-Level SQL Validation**: Every incoming query is parsed into an Abstract Syntax Tree using JSqlParser:
  - Only single-statement queries are accepted; multi-statement scripts are rejected immediately.
  - Queries must be read-only `SELECT` statements. DML statements (`INSERT`, `UPDATE`, `DELETE`) and DDL statements (`CREATE`, `DROP`, `ALTER`, `TRUNCATE`) are rejected even if nested inside `EXPLAIN` queries, subqueries, or CTEs (`WITH ...`).
  - PRAGMA statements are blocked unless explicitly permitted by an exact allowlist (e.g. `PRAGMA table_info(...)`).
* **Catalog Table Allowlisting**: Metadata queries are restricted to allowlisted system schema tables (`sqlite_master`, `sqlite_schema`, `information_schema.tables`, `flyway_schema_history`). Wildcard catalog queries are denied.
* **Transaction Isolation & Poison Discard**:
  - SQLite connections execute under `PRAGMA query_only = ON;`.
  - PostgreSQL connections execute within a read-only transaction boundary with guaranteed rollback. If resetting connection isolation fails, the connection is closed and discarded rather than returned to the Agroal pool.
* **Authorization & Audit**: Queries require `isInternal()` authorization and log `DB_QUERY_EXECUTED` or `DB_QUERY_REJECTED` in the audit log.

---

## Model Arena Security & Token Bounding

The Model Arena (`POST /api/v1/admin/arena/compare`) provides operator-governed model comparisons:

* **Credential Detection**: Evaluates input prompts against `CredentialDetector`, failing closed with HTTP 400 if API keys, bearer tokens, or vault pepper strings are detected.
* **PII Redaction**: Enforces PII policy before sending prompts to external providers.
* **Token Budget Ceilings**: Enforces $\le 2,048$ input tokens and $\le 1,024$ output tokens per model slot. Prompts are truncated using script-aware Unicode budgeting (`maxTokens * 4` for ASCII, accounting for CJK and multi-byte scripts).
* **Zero Tool Capability**: Arena model instances do not expose tool calling, sandbox execution, or shell execution.

---

## Bounded Health Reporting & Non-Disclosure

The system health endpoint (`GET /api/v1/admin/cockpit/health`) sanitizes runtime diagnostic data:

* Returns bounded categorical health states (`UP`, `DEGRADED`, `DOWN`).
* Strips all JDBC URLs, hostnames, port numbers, passwords, vault paths, and internal stack traces before serializing responses.
* Maps provider connectivity to normalized boolean states and alias names (`claude-code`, `codex`) without leaking API tokens.

---

## Related Docs

| Doc | What it covers |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Governance decision tree, guard layers overview |
| [Architecture_Invariants.md](./Architecture_Invariants.md) | Transaction rules, native-image, persistence contracts |
| [CONFIGURATION.md](./CONFIGURATION.md) | Vault pepper, HMAC key, sandbox config env vars |
| [AGENTS.md](../AGENTS.md) | Role-based governance bypass invariant (§2.9) |
