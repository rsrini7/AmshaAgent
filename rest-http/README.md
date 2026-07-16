# Amsha Agent REST HTTP Test Suite

> **Tool**: [REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client) for VS Code  
> **Version**: V0.1.0 (current)  
> **Base URL**: `http://localhost:8080`

This directory contains `.http` files covering every REST API surface of Amsha Agent. Run them in the order listed below - health and auth first, then the feature-specific flows, and the Phase 64 regression suite last.

---

## Prerequisites

### Server running

```bash
# Serve mode (REST + no Telegram)
./mvnw quarkus:dev -pl amsha-app -am \
  "-Dquarkus.args=serve" \
  "-Damsha.rag.vec-extension-path=../lib/vec0"
```

### Required `.env` values

```env
AMSHA_LLM_API_KEY=gsk_...
AMSHA_API_KEYS_INTERNAL=key-internal-changeme
AMSHA_API_KEYS_CI=key-ci-changeme
AMSHA_VAULT_PEPPER=<32+ char string>
AMSHA_AUDIT_HMAC_KEY=<32+ char string>
AMSHA_RAG_VEC_PATH=../lib/vec0         # dev mode
```

### Optional (for tool tests)

```env
GITHUB_TOKEN=ghp_...    # required for GithubTool tests in 04, 05, 09, 12
```

---

## API Keys Used in These Files

| Variable | Value | Used in |
|---|---|---|
| `@validKey` | `key-internal-cWevfEoRvstUv0PqGpf02LzDY5kPNaz1y9GSl0LZOwU=` | All files |
| `@ciKey` | `key-ci-JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs=` | 02, 06, 07, 08 |

---

## File Index & Execution Order

| # | File | What it covers | Prerequisites | Phase 64 changes |
|---|---|---|---|---|
| 1 | `01_system_health.http` | Health endpoints, startup log assertions, metrics, E2E SQLite smoke test | Server up | B1: HMAC chain under concurrency |
| 2 | `02_auth_security.http` | API key auth, public bypasses, rate limiting per key | Server up | B2: DevSecretSeeder absent in prod |
| 3 | `03_session_lifecycle.http` | Full session CRUD, sync/async run, multi-turn memory, isolation | AMSHA_LLM_API_KEY | B3: random UUID via SessionRegistry |
| 4a | `04_agent_delegation.http` | Agent delegation flows, sub-agent spawning, depth limits | AMSHA_LLM_API_KEY | Post-Phase 64 |
| 4b | `04_agent_roles.http` | All 6 roles + deity aliases, tool schema structural guarantees | AMSHA_LLM_API_KEY | A2: FULL autonomy not downgraded |
| 5 | `05_hitl_governance.http` | HITL approve/deny flows (REST + goal-string), timeout | AMSHA_LLM_API_KEY, GITHUB_TOKEN | A2: FULL session skips approval |
| 6 | `06_reflex_commands.http` | All reflexes: /status /reset /halt /history /crons /skills /mcp store | Server up | None (existing behaviour) |
| 7 | `07_pii_prompt_injection.http` | PII redaction (8 types), store bypass, injection blocking (4 categories) | AMSHA_LLM_API_KEY | A3: non-ASCII FTS5 fix (store path) |
| 8 | `08_audit_log.http` | WORM audit log, HMAC chain verify, tamper detection, all event types | AMSHA_LLM_API_KEY | B1: concurrent chain integrity |
| 9 | `09_cron_scheduler.http` | Schedule/list/delete crons, safeguards, wait for CRONFIRED | AMSHA_LLM_API_KEY | C2: ChannelCronNotificationDispatcher |
| 10 | `10_memory_compaction.http` | Multi-turn memory, rolling window pruning, compaction, memory search | AMSHA_LLM_API_KEY | A3, A6, C3, D1 |
| 11 | `11_skill.http` | Skill CRUD, skill routing (bypasses LLM), upsert, error cases | AMSHA_LLM_API_KEY | D7: SANDBOX_EXEC removed |
| 12 | `12_mcp.http` | MCP server protocol, MCP client (self-connect), admin API | Server up | D7: sandbox_exec → allowlist error |
| 13 | `13_rag.http` | RAG ingest → approve → recall, RRF dual-tier, isolation | AMSHA_LLM_API_KEY | C3: rag_chunk table |
| 14 | `14_budget_governance.http` | Budget soft/hard limits, SUPERVISED/FULL/READONLY autonomy | AMSHA_LLM_API_KEY | A1, A2 |
| 15 | `15_phase64_regression.http` | Full regression suite for all Phase 64 fixes A1–A7, B1–B3, C1–C3, D smoke | AMSHA_LLM_API_KEY | All tiers |
| 16 | `16_ui_settings_onboarding.http` | UI status/SSE streams, settings CRUD, onboarding wizard, HITL task-id approval, WS token | AMSHA_LLM_API_KEY | Post-Phase 64 |
| 19 | `19_identity_access.http` | **P109** first-owner bootstrap claim, `/identity/me`, workspace members, invitations, personal API credentials, last-owner guard, ownership transfer, workspace credential rotation | Fresh DB (UNCLAIMED) | New in P109 |
| 20 | `20_auth_oauth_session.http` | **P109** `/auth/providers` discovery, OAuth login/callback negative paths, web-session rotate, CSRF enforcement, logout, revoke-all | Server up | New in P109 |
| 21 | `21_self_evolve.http` | **Self-evolve** behavioral directive REST surface: list/get, synthesize, reconcile, disable, delete + internal-key-only auth model | Server up | New (dedicated coverage) |

---

## Phase 64 Changes - What Each Fix Changes in These Files

### Tier A - Correctness

| Fix | Affected Files | What to look for |
|---|---|---|
| **A1** Budget hard limit fires first | 14, 15 | `LOOP_BUDGET_EXCEEDED` on the exceeding call, not the next |
| **A2** AutonomyLevel not hardcoded SUPERVISED | 04, 05, 14, 15 | FULL session: no approval prompt for GitHub tool |
| **A3** Non-ASCII FTS5 search | 10, 15 | Japanese/Arabic memory search returns results |
| **A4** Headings render as bold not `\#\#` | 04 (response visual) | Structured responses in Telegram show bold headings |
| **A5** JSON parse error → retry not FINISH | 15 | Log: `action=OBSERVE` not `action=FINISH` on parse error |
| **A6** compactionInFlight lock released | 10, 15 | Second compaction fires after first observer failure |
| **A7** ChannelType.fromString → Optional | 15 | Known types still resolve; `fromString("UNKNOWN")` → empty |

### Tier B - Security

| Fix | Affected Files | What to look for |
|---|---|---|
| **B1** Audit TOCTOU fixed | 08, 15 | HMAC chain `valid:true` after concurrent writes |
| **B2** DevSecretSeeder compile-excluded | 01, 15 | No DevSecretSeeder log lines in prod/staging startup |
| **B3** Session IDs are random UUIDs | 03, 15 | `session_registry` table; ID ≠ MD5 of chatId:userId |

### Tier C - Architecture

| Fix | Affected Files | What to look for |
|---|---|---|
| **C1** Session key is (channelType, channelId, userId) | 03, 15 | `session_registry` has all three columns |
| **C2** ChannelCronNotificationDispatcher | 09, 15 | Cron notifications still arrive correctly |
| **C3** rag_chunk table (no "rag:" prefix) | 13, 15 | `SELECT COUNT(*) FROM compaction_summary WHERE session_id LIKE 'rag:%'` = 0 |

### Tier D - Quality

| Fix | Affected Files | What to look for |
|---|---|---|
| **D1** Unicode-safe compaction truncation | 10 | Emoji/CJK summaries not garbled |
| **D7** SANDBOX_EXEC removed | 11, 12 | MCP `sandbox_exec` call → allowlist error |

---

## Common Patterns

### Capturing session IDs

```http
### Create session
# @name mySession
POST {{baseUrl}}/api/v1/sessions
Content-Type: application/json
X-Amsha-API-Key: {{validKey}}

### Use the session
POST {{baseUrl}}/api/v1/sessions/{{mySession.response.body.sessionId}}/run
```

### Capturing MCP session ID (file 12 only)

```http
### MCP Initialize
# @name mcpInit
POST {{mcpBase}}/mcp
...

# After MCP-1, capture the header:
@mcpSessionId = {{mcpInit.response.headers.mcp-session-id}}
```

### Checking audit after a flow

```http
GET {{baseUrl}}/api/v1/audit/session/{{mySession.response.body.sessionId}}
X-Amsha-API-Key: {{validKey}}
```

### Verifying HMAC chain

```http
GET {{baseUrl}}/api/v1/audit/{{mySession.response.body.sessionId}}/verify
X-Amsha-API-Key: {{validKey}}
# Expect: {"valid":true,"brokenAtId":null,"totalRows":N}
```

---

## HTTP Response Codes Reference

| Code | Meaning in Amsha Agent |
|---|---|
| `200` | Success (also used for JSON-RPC errors per spec) |
| `201` | Session or skill created |
| `202` | `run-async` accepted, or `AWAITING_APPROVAL` checkpoint |
| `400` | Bad request (invalid role, missing goal, bad YAML) |
| `401` | Missing or invalid API key |
| `404` | Session/resource not found |
| `409` | Conflict (approve on non-AWAITING session, duplicate connect) |
| `429` | Rate limit exceeded (20 req/min per key) |
| `502` | MCP client could not connect to remote server |

---

## Audit Event Types Quick Reference

| Event | Mandatory | Tier | Triggered by |
|---|---|---|---|
| `CHECKPOINT_CREATED` | No | telemetry | First agent run |
| `LOOP_FINISH` | No | telemetry | Agent completed |
| `LOOP_MAX_ITERATIONS` | No | telemetry | maxIterations exhausted |
| `LOOP_BUDGET_EXCEEDED` | Yes | critical | Hard USD limit hit |
| `LOOP_AWAITING_APPROVAL` | No | telemetry | Tool needs HITL |
| `LOOP_ERROR` | No | telemetry | Unexpected exception |
| `TOOL_INVOKED` | Yes | critical | Any tool executed |
| `TOOL_BLOCKED` | No | telemetry | Tool blocked by guard |
| `APPROVAL_GRANTED` | Yes | critical | /approve sent |
| `APPROVAL_DENIED` | Yes | critical | /deny sent |
| `PROMPT_INJECTION_BLOCKED` | Yes | critical | Injection detected |
| `PII_SCAN_REDACTED` | No | telemetry | PII found and redacted |
| `REFLEX_COMMAND` | No | telemetry | /status, /reset, etc. |
| `VAULT_STORE` | No | telemetry | vault KEY=VALUE |
| `VAULT_RETRIEVE` | Yes | critical | Tool fetched secret |
| `VAULT_RETRIEVE_MISS` | No | telemetry | Secret not found |
| `CONTEXT_COMPACTED` | No | telemetry | Chat window compacted |
| `CRONCREATED` | Yes | critical | Cron job scheduled |
| `CRONFIRED` | Yes | critical | Cron job executed |
| `CRONFAILED` | Yes | critical | Cron job threw error |
| `CRONDELETED` | No | telemetry | Cron job deleted |
| `MCP_TOOL_INVOKED` | No | telemetry | MCP tool called |
| `SKILL_LOADED` | No | telemetry | Skill registry loaded |
| `SKILL_REFRESHED` | No | telemetry | Registry refreshed |
| `STRUCTURED_OUTPUT_RETRY` | No | telemetry | Phase 62 retry |

---

## SQLite Manual Inspection

```bash
# Standard database path (serve/jar mode)
sqlite3 ./data/amsha-meta.db

# Dev mode (run from app/)
sqlite3 ../data/amsha-meta.db

# Useful queries
.tables
SELECT * FROM audit_event WHERE session_id='<uuid>' ORDER BY sequence;
SELECT * FROM agent_checkpoint WHERE session_id='<uuid>';
SELECT * FROM session_registry LIMIT 10;
SELECT * FROM rag_chunk LIMIT 10;

# Phase 64-C3: confirm no "rag:" prefix rows
SELECT COUNT(*) FROM compaction_summary WHERE session_id LIKE 'rag:%';

# Phase 64-B3: inspect session registry
SELECT channel_type, channel_id, user_id, session_id, created_at
FROM session_registry ORDER BY created_at DESC LIMIT 10;
```

---

## What Changed in Phase 64 - Summary for Testers

**Before Phase 64:**
- Budget hard limit could be exceeded by one call (warning fired before hard stop)
- FULL autonomy sessions silently downgraded to SUPERVISED via Telegram
- Non-ASCII memory searches silently returned empty
- Telegram headings rendered as `\#\# Title` (literal escaped hashes)
- JSON parse errors ended the agent loop with FINISH instead of retrying
- Compaction observer failures permanently locked the session (no more compaction)
- `ChannelType.fromString` silently returned TELEGRAM for unknown types
- Audit HMAC chain could fork under concurrent writes (TOCTOU race)
- `DevSecretSeeder` was runtime-guarded (config flag), not compile-time excluded
- Session IDs were MD5-derived (UUID v3) - not cryptographically appropriate
- RAG chunks mixed into `compaction_summary` via `"rag:"` prefix hack

**After Phase 64:**
- Hard limit checked first - exceeds it on the call that crosses it
- FULL/READONLY autonomy levels honoured through all channels
- Non-ASCII FTS5 queries work correctly (only syntax chars escaped)
- Telegram headings render as **bold text**
- JSON parse errors trigger retry via OBSERVE action
- compactionInFlight lock released on any observer failure
- `ChannelType.fromString` returns `Optional.empty()` for unknown types
- Audit writes serialized per-session via `SessionAuditSequencer`
- DevSecretSeeder excluded at compile time via `@IfBuildProfile("dev")`
- Session IDs are random UUIDs stored in `session_registry` table
- RAG chunks in dedicated `rag_chunk` table, fully isolated from compaction

---

## P109 — Identity, Access & Workspace Sharing (NEW)

Branch P109 introduces a complete identity/access layer **not covered by any
pre-Phase-64 file** (those reference the old `/api/v1/onboarding/*` UI wizard,
which is distinct from the new `/onboarding/bootstrap/*` first-owner claim).

### New resources (no `/api/v1` prefix)

| Resource | Path | Auth |
|---|---|---|
| Bootstrap (first-owner claim) | `/onboarding/bootstrap/{status,claim,confirm}` | Public, loopback-only |
| Identity | `/identity/me`, `/identity/workspaces/{id}/members` | API key or session |
| Invitations | `/identity/workspaces/{id}/invitations`, `/identity/invitations/{token}/accept` | API key or session |
| Members | `DELETE /identity/workspaces/{id}/members/{memberId}` | API key or session |
| Ownership | `POST /identity/workspaces/{id}/owner/transfer` | API key or session |
| Workspace creds | `POST /identity/workspaces/{id}/credentials/rotate` | API key or session |
| API credentials | `/identity/api-credentials` (`POST`/`DELETE`) | API key or session |
| Auth providers | `/auth/providers`, `/auth/login/{p}`, `/auth/callback/{p}` | Public |
| Sessions | `/auth/session/rotate`, `/auth/logout`, `/auth/session/revoke-all` | Session cookie (+CSRF) |

### Running the P109 suite

```bash
# Requires a FRESH serve-mode DB so bootstrap state is UNCLAIMED
bash rest-http/scripts/validate_identity_access.sh
# or via the master runner:
bash rest-http/scripts/run_all_validations.sh --identity
```

The script claims the first owner, extracts the raw key from the
`Set-Cookie: AMSHA_API_KEY=...` response header (the key is deliberately
**not** in the JSON body), and exercises every automatable path.

### Key P109 behaviors asserted

- Bootstrap claim is loopback-only and idempotent (`409 ALREADY_CLAIMED`).
- Raw key is returned only in a `HttpOnly; Secure; SameSite=Strict` cookie.
- `/identity/me` requires an authenticated principal (`401` otherwise).
- Only `OWNER`/`ADMIN` may invite; only `OWNER` may invite `OWNER`, transfer
  ownership, or rotate workspace credentials.
- Personal API credentials cannot grant `INTERNAL`; only roles the caller
  holds may be granted.
- Removing or transferring the **last active OWNER** is blocked
  (`400 LAST_OWNER_REMOVAL_BLOCKED`).
- Web-session mutations require `X-CSRF-Token`; missing/invalid → `403`.
- `GET /auth/login/{unknown}` → `404 PROVIDER_NOT_FOUND`;
  `GET /auth/callback/{p}?state=bad` → `400 INVALID_STATE`.

### Running the Self-Evolve (behavioral directives) suite

```bash
# Works for jar OR native, sqlite/postgres/fs. Requires a running serve-mode
# server on :8080 with self-evolve enabled.
#
# For a sqlite backend, pass the data dir so the script can seed extracted_memories
# + SHADOW directives and exercise the full lifecycle (synthesize/reconcile/disable/
# delete). Without it, only the pure-HTTP checks run.
AMSHA_DATA_DIR=/path/to/data bash rest-http/scripts/validate_self_evolve.sh
# or directly:
bash rest-http/scripts/validate_self_evolve.sh --data-dir /path/to/data
# via the master runner (uses AMSHA_DATA_DIR):
AMSHA_DATA_DIR=/path/to/data bash rest-http/scripts/run_all_validations.sh --self-evolve
```

The script resolves the runtime user (the `bootstrap_state.owner_id` the internal
API key maps to), rewrites `scripts/data/seed_self_evolve.sql` with that user, and
applies it to `amsha-meta.db`. It then runs: list/get, synthesize (LLM → SHADOW
directives), reconcile (promotes/disables by evaluation history), disable, delete,
plus the internal-key-only `403` auth model and `404` checks. Synthesize depends on
the configured LLM; a transient backend error is retried once. Reused seed data lives
in `scripts/data/seed_self_evolve.sql`.

### Shell validators (scripts/*.sh)

Each validator is a standalone `bash` script that `curl`s a running server (no VS Code
needed). Run individually or via the master runner `run_all_validations.sh`.

| Flag | Script | Coverage | Cost |
|---|---|---|---|
| `--reflex` | `validate_reflex.sh` | Reflex commands via goal text | zero-token |
| `--skills` | `validate_skill.sh` | Skill CRUD, routing, errors | low |
| `--mcp` | `validate_mcp.sh` | MCP JSON-RPC protocol + connect | low |
| `--memory` | `validate_memory.sh` | Archival/core memory + Arabic search (HITL-aware) | LLM |
| `--web` | `validate_web.sh` | Web search/fetch/SSRF blocks (HITL-aware) | LLM |
| `--roles` | `validate_roles.sh` | Agent roles + aliases (HITL-aware) | LLM |
| `--admin` | `validate_admin.sh` | Directives/prompts/tiered-memory admin | fast |
| `--auth-session` | `validate_auth_session.sh` | API-key auth + session lifecycle | fast |
| `--identity` | `validate_identity_access.sh` | P109 bootstrap/identity/OAuth | fast |
| `--pii-audit` | `validate_pii_injection_audit.sh` | PII redaction + injection + audit HMAC | fast |
| `--self-evolve` | `validate_self_evolve.sh` | Directives lifecycle (seeds when `--data-dir` given) | LLM |
| `--hitl` | `validate_hitl.sh` | Approve/deny (REST + goal-string + taskId), 401/409 | LLM |
| `--rag` | `validate_rag.sh` | Ingest → approve → recall + rag_chunk isolation | LLM |
| `--budget` | `validate_budget.sh` | Budget limits + autonomy SUPERVISED/FULL/READONLY | LLM |
| `--sse-settings` | `validate_sse_settings.sh` | UI SSE streams, ws-token, settings CRUD | fast |
| `--cron` | `validate_cron.sh` | Programmatic cron REST lifecycle | LLM |
| `--fast` | `(master)` | all fast/zero-token validators | mixed |
| `--llm` | `(master)` | memory + web + roles + cron + rag + budget + hitl | LLM |

### New P109 config flags (also in `.env.example`)

```
AMSHA_AUTH_HMAC_KEY=...
AMSHA_AUTH_SESSION_IDLE_MINUTES=720
AMSHA_AUTH_SESSION_ROTATION_GRACE_SECONDS=30
AMSHA_AUTH_IDP_OIDC_ENABLED=false
AMSHA_AUTH_IDP_GITHUB_ENABLED=false
```

## Running a Full Regression Pass

```bash
# 1. Start Amsha Agent in serve mode (see Prerequisites above)

# 2. Run files in order using VS Code REST Client
#    Open each file, Ctrl+Alt+R on each ### block, or "Run All Requests"

# 3. After all files complete, run the regression suite:
#    Open 15_phase64_regression.http
#    Run in order: Tier A → Tier B → Tier C → Tier D → Health Check

# 4. Final verification
#    GET http://localhost:8080/q/health  → all UP
#    GET http://localhost:8080/api/v1/audit/<session>/verify → valid:true
```

---

## Known Limitations

- **13_rag.http RAG ingest**: Requires manual approval (SUPERVISED mode). Run the approve request promptly after the ingest trigger, or switch the session to FULL autonomy first (see 14_budget_governance.http FLOW D).

- **09_cron_scheduler.http wait steps**: CRON-14 and CRON-15 require waiting ~90 seconds after CRON-1 to observe `CRONFIRED`. These cannot be automated in REST Client - open the audit endpoint manually after waiting.

- **08_audit_log.http tamper test**: Requires dropping the SQLite WORM trigger manually and restarting the app. Cannot be automated - follow the inline instructions.

- **07_pii_prompt_injection.http BLOCK_AND_NOTIFY**: Commented out by default. Requires `amsha.pii.mode=BLOCK_AND_NOTIFY` in config and a restart to test.

- **Session IDs in 15_phase64_regression.http**: Some Phase 64 verifications reference specific database state (e.g. `session_registry` table contents) that must be verified via SQLite CLI, not via HTTP. The HTTP tests confirm the behaviour indirectly through status codes and audit events.
