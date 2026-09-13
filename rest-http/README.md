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
./mvnw quarkus:dev -pl app -am \
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

## Native distribution wrappers

The backend wrappers provide one consistent entry point for native
distribution builds, serving, and REST validation. They use isolated data
directories by default and do not require a native binary for `--dry-run` or
validation against an existing `TEST_BASE` runtime.

| Backend | Wrapper | Default data directory | Serve task | Fast validation task |
|---|---|---|---|---|
| SQLite | `scripts/run_dist_sqlite.sh` | `dist/data-sqlite` | `mise run dist:serve-sqlite` | `mise run dist:validate-sqlite` |
| PostgreSQL | `scripts/run_dist_pg.sh` | `dist/data-postgres` | `mise run dist:serve-pg` | `mise run dist:validate-pg` |
| Filesystem | `scripts/run_dist_fs.sh` | `dist/data-fs` | `mise run dist:serve-fs` | `mise run dist:validate-fs` |

Run the wrapper contract tests before a migration or backend switch:

```bash
python3 scripts/test_dist_wrappers.py
```

Inspect a resolved plan without building, starting a server, downloading a
model, or creating data directories:

```bash
./scripts/run_dist_sqlite.sh --dry-run
./scripts/run_dist_pg.sh --dry-run
./scripts/run_dist_fs.sh --dry-run
```

Serve a backend in the foreground. Add `--build` to rebuild its native binary
first; without a command, `serve` is used:

```bash
./scripts/run_dist_sqlite.sh --build
./scripts/run_dist_pg.sh --build
./scripts/run_dist_fs.sh --build
```

Run fast or full REST validation. Fast validation forwards `--fast` to the
master validator; `--validate` without `--fast` runs the full suite:

```bash
./scripts/run_dist_sqlite.sh --validate --fast
./scripts/run_dist_sqlite.sh --validate --full

# Equivalent mise shortcuts (fast validation)
mise run dist:validate-sqlite
mise run dist:validate-pg
mise run dist:validate-fs
```

To validate a server that is already running, set `TEST_BASE`. The wrapper
will not start or stop another server, and a local native binary is not needed
unless `--build` is also supplied:

```bash
TEST_BASE=http://127.0.0.1:8081 \
  ./scripts/run_dist_sqlite.sh --validate --fast
```

When the wrapper owns the validation server, it uses port `8080` by default.
Choose another free port with `AMSHA_VALIDATION_PORT`; an occupied port is
reported rather than terminating an unrelated process:

```bash
AMSHA_VALIDATION_PORT=18081 \
  ./scripts/run_dist_sqlite.sh --validate --fast
```

ONNX installation and validation are opt-in. Use `--onnx` only when the local
bundle is required:

```bash
./scripts/run_dist_sqlite.sh --validate --fast --onnx
```

Without `--onnx`, a validation-owned native server uses the native-compatible
Jina embedding adapter instead of a file-level ONNX provider setting. The
provider is constructed at startup but no embedding request is made by the
fast validation profile.

The wrapper safely reads `.env`, `dist/.env`, and an optional file named by
`AMSHA_ENV_FILE`; shell expressions in values are treated as literal text and
process environment variables take precedence.

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
| 16 | `16_ui_settings_onboarding.http` | UI status/SSE streams, settings CRUD, draft/saga onboarding, HITL task-id approval, WS token | AMSHA_LLM_API_KEY | Post-Phase 64 / P110 |
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
| `202` | Asynchronous run result: completed, `AWAITING_APPROVAL`, or `AWAITING_RETRY`; inspect the response body and audit state |
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
-- P110: durable onboarding drafts replaced the removed session_registry
SELECT * FROM onboarding_draft LIMIT 10;
SELECT * FROM rag_chunk LIMIT 10;

# Phase 64-C3: confirm no "rag:" prefix rows
SELECT COUNT(*) FROM compaction_summary WHERE session_id LIKE 'rag:%';

# Phase 64-B3: inspect durable onboarding drafts (P110 replaced session_registry)
SELECT draft_id, owner_id, current_step_id, state, revision, updated_at
FROM onboarding_draft ORDER BY updated_at DESC LIMIT 10;
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
- Session IDs are random UUIDs stored in the session store
- RAG chunks in dedicated `rag_chunk` table, fully isolated from compaction

---

## P109 — Identity, Access & Workspace Sharing (NEW)

Branch P109 introduces a complete identity/access layer **not covered by any
pre-Phase-64 file** (those reference the old `/api/v1/onboarding/*` UI wizard,
which is distinct from the new `/onboarding/bootstrap/*` first-owner claim).
The `/api/v1/onboarding/*` UI surface was further rewritten in P110 into the
durable draft/saga model (see the P110 section below); the bootstrap claim
(`/onboarding/bootstrap/*`) remains the first-owner flow.

### New resources (no `/api/v1` prefix)

| Resource | Path | Auth |
|---|---|---|
| Bootstrap (first-owner claim) | `/onboarding/bootstrap/{status,claim,recovery,confirm}` | Public, loopback-only |
| Identity | `/identity/me`, `/identity/workspaces/{id}/members` | API key or session |
| Invitations | `/identity/workspaces/{id}/invitations`, `/identity/invitations/{token}/accept` | API key or session |
| Members | `DELETE /identity/workspaces/{id}/members/{memberId}` | API key or session |
| Ownership | `POST /identity/workspaces/{id}/owner/transfer` | API key or session |
| Workspace creds | `POST /identity/workspaces/{id}/credentials/rotate` | API key or session |
| API credentials | `/identity/api-credentials` (`POST`/`DELETE`) | API key or session |
| Auth providers | `/auth/providers`, `/auth/login/{p}`, `/auth/callback/{p}` | Public |
| Sessions | `/auth/session/rotate`, `/auth/logout`, `/auth/session/revoke-all` | Session cookie (+CSRF) |

### P110 — Durable Draft/Saga Onboarding, Provider Catalog & Workspace Scoping (NEW)

Branch P110 replaces the legacy numbered-step onboarding wizard
(`/api/v1/onboarding/step/{N}`, `/api/v1/onboarding/complete`) with a durable,
resumable **draft/saga** model and adds the provider catalog, per-connection
network-profile persistence, and the `ActiveWorkspaceFilter`.

New/changed resources (under `/api/v1`):

| Resource | Path | Auth |
|---|---|---|
| Onboarding status | `GET /onboarding/status` | API key (owner) |
| Draft create/resume | `POST /onboarding/drafts` | API key (owner) |
| Draft list | `GET /onboarding/drafts` | API key (owner) |
| Draft get | `GET /onboarding/drafts/{draftId}` | API key (owner) |
| Step advance | `PUT /onboarding/drafts/{draftId}/steps/{stepId}` | API key (owner) |
| Draft commit | `POST /onboarding/drafts/{draftId}/commit` | API key (owner) |
| Routing health | `GET /onboarding/health/routing` | API key (owner) |
| Provider catalog | `GET /onboarding/provider-catalog` | API key (owner) |
| Profile export | `GET /onboarding/export` | API key (owner) |
| Profile import | `POST /onboarding/import` | API key (owner) |
| Draft discard | `POST /onboarding/drafts/discard` | API key (owner) |
| Connection network profile | `PATCH /settings/connection-network-profile` | API key (owner) |
| Bootstrap recovery | `POST /onboarding/bootstrap/recovery` | API key (reserved owner) |
| Workspace scoping | `X-Amsha-Workspace-Id` header on every request | filter-enforced |

The `ActiveWorkspaceFilter` (new) binds an explicitly selected workspace when
the `X-Amsha-Workspace-Id` header is present: unauthenticated + header → `401`,
authenticated non-member → `403`, member → request scoped to that workspace.
The legacy session registry and numbered-step wizard were removed.

### Running the P110 suite

```bash
# Covers pre-auth skip/cookie authentication, negative and successful draft commits,
# strict typed provider catalog, import/export persistence, network-profile positive
# and negative behavior, recovery authz/state, and workspace filtering.
# Uses the skip cookie when enabled; otherwise requires a usable VALID_KEY owner.
bash rest-http/scripts/validate_onboarding.sh
# or via the master runner (runs after the identity/bootstrap suite):
bash rest-http/scripts/run_all_validations.sh --onboarding

# Require the skip-onboarding branch rather than accepting the expected 401 when disabled:
EXPECT_SKIP_ONBOARDING=true bash rest-http/scripts/run_all_validations.sh --onboarding

# Override credentials without editing common_env.sh:
VALID_KEY='<active-owner-key>' bash rest-http/scripts/run_all_validations.sh --onboarding
```

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

All validators default to `http://localhost:8080`. To validate another running
instance, set `TEST_BASE`; the value is shared by the master preflight and every
child validator:

```bash
TEST_BASE=http://localhost:8081 bash rest-http/scripts/run_all_validations.sh --identity
TEST_BASE=http://localhost:8081 bash rest-http/scripts/run_all_validations.sh --auth-session
```

The master runner exits nonzero when one or more suites fail. The identity suite
requires a fresh database; use an isolated `AMSHA_DATA_DIR` when validating a
rebuilt native binary so the normal `dist/data` is not modified.

The shell suite is an automatable smoke/contract subset, not complete endpoint
coverage. It does not perform workspace credential rotation, a successful
ownership transfer, separate-user invitation acceptance, or concurrent
last-owner race. Session logout/rotation and CSRF success paths are covered by
the Java security tests and the HTTP client scenarios.

Seeded advanced checks are available through
`rest-http/scripts/validate_advanced_identity.sh`. They run two concurrent
owner-removal requests and optionally verify a second user's workspace access:

```bash
TEST_BASE=http://localhost:8080 \
TEST_OWNER_KEY=... TEST_WORKSPACE_ID=... TEST_OWNER_IDS=owner-a,owner-b \
TEST_SECOND_OWNER_KEY=... TEST_SECOND_OWNER_ID=owner-b \
bash rest-http/scripts/validate_advanced_identity.sh
```

The fixture must contain two active owners in an isolated workspace. OAuth and
CSRF-positive flows require a mock identity provider and web-session cookie
fixture; the advanced script deliberately fails fast instead of claiming those
flows are covered without those prerequisites.

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
| `--skills-admission` | `validate_skillsjar_admission.sh` | SkillsJar admission, quarantine schema, refresh stability | zero-token |
| `--mcp` | `validate_mcp.sh` | MCP JSON-RPC protocol plus approval-gated discover/connect, policies, reconfigure, pause/resume, import, setup-token, and cleanup | low |

`validate_mcp.sh` starts a local deterministic MCP fixture for outbound lifecycle
checks. It may exceed the default application mutation budget in one run; the
validator reports `429` without waiting by default. Set
`AMSHA_MCP_VALIDATION_RATE_LIMIT_WAIT_SECONDS=62` when deliberately running a
complete flow across the rate-limit window.
| `--memory` | `validate_memory.sh` | Archival/core memory + Arabic search (HITL-aware) | LLM |
| `--web` | `validate_web.sh` | Web search/fetch/SSRF blocks (HITL-aware) | LLM |
| `--roles` | `validate_roles.sh` | Agent roles + aliases (HITL-aware) | LLM |
| `--admin` | `validate_admin.sh` | Directives/prompts/tiered-memory admin | fast |
| `--auth-session` | `validate_auth_session.sh` | API-key auth + session lifecycle | fast |
| `--identity` | `validate_identity_access.sh` | P109 bootstrap/identity/OAuth | fast |
| `--pii-audit` | `validate_pii_injection_audit.sh` | PII redaction + injection + audit HMAC | fast |
| `--onnx` | `validate_onnx.sh` | ONNX health, fixed-text inference metric, bundle digests, vector compatibility, non-activating classifier controls | local CPU |
| `--self-evolve` | `validate_self_evolve.sh` | Directives lifecycle (seeds when `--data-dir` given) | LLM |
| `--hitl` | `validate_hitl.sh` | Approve/deny (REST + goal-string + taskId), 401/409 | LLM |
| `--rag` | `validate_rag.sh` | Ingest → approve → recall + rag_chunk isolation | LLM |
| `--budget` | `validate_budget.sh` | Budget limits + autonomy; FULL safe-operation bypass and mandatory network/shell HITL | LLM |
| `--sse-settings` | `validate_sse_settings.sh` | UI SSE streams, ws-token, settings CRUD | fast |
| `--cron` | `validate_cron.sh` | Programmatic cron REST lifecycle | LLM |
| `--fast` | `(master)` | all fast/zero-token validators | mixed |
The `--fast` profile includes `validate_mcp_fast.sh`: MCP admin status/tools, REST API-key rejection, the current durable connection-list shape, JSON-RPC initialize, session handshake, tools/list, and unknown-tool rejection. It does not invoke LLM-backed tools or mutate managed connections.
| `--llm` | `(master)` | memory + web + roles + cron + rag + budget + hitl | LLM |

`--onnx` targets an already-running process. To also authenticate against the local
OMLX OpenAI-compatible API and manage an isolated native lifecycle, keep the required
`AMSHA_OMLX_*` credentials in the ignored root `.env` and run
`mise run smoke:local-runtime -- native --binary dist/amsha-sqlite`. That workflow
forces `generic-openai` plus ONNX, never selects Ollama, and does not print raw HTTP
payloads or credentials. See the
[local ONNX guide](../docs/ONNX_LOCAL_ML_GUIDE.md#reusable-local-runtime-smoke-runner).

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
