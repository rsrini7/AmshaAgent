# Amsha Agent REST HTTP Test Suite

> **Tool**: [REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client) for VS Code  
> **Version**: V0.1.0 (current)  
> **Base URL**: `http://localhost:8080`

This directory contains 17 `.http` files covering every REST API surface of Amsha Agent. Run them in the order listed below - health and auth first, then the feature-specific flows, and the Phase 64 regression suite last.

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