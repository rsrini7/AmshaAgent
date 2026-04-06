## Phase 77 — Updated Testing Guide

### Layer 1 — Unit Tests (~30 sec)

```powershell
./mvnw test `
  -pl arcclaw-core,arcclaw-mcp,arcclaw-app `
  -Dtest="SystemEnvironmentContextEnricherTest,CheckListToolTest,ClaudeCodeOAuthTokenExtractorTest,BraveWebSearchToolTest,SerperWebSearchToolTest,McpSelfRegisterToolTest" `
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Expected: 18 tests | Failures: 0 | Errors: 0**

| Test Class | Module | Tests |
|---|---|---|
| `SystemEnvironmentContextEnricherTest` | `arcclaw-core` | 2 |
| `CheckListToolTest` | `arcclaw-core` | 5 |
| `BraveWebSearchToolTest` | `arcclaw-core` | 5 |
| `SerperWebSearchToolTest` | `arcclaw-core` | 5 |
| `ClaudeCodeOAuthTokenExtractorTest` | `arcclaw-app` | 5 |
| `McpSelfRegisterToolTest` | `arcclaw-mcp` | 4 |

***

### Layer 2 — Compile Check (~2 min)

```powershell
./mvnw clean compile test-compile `
  -pl arcclaw-core,arcclaw-mcp,arcclaw-app `
  --no-transfer-progress
```

Catches `ToolCapability` rename breaks and `addEnumProperty` signature issues that Mockito tests won't catch.

***

### Layer 3 — Stale Reference Grep

```powershell
# No stale WEB_SEARCH refs (non-suffixed)
grep -rn "ToolCapability\.WEB_SEARCH[^_]" arcclaw-core/src arcclaw-app/src

# Both new capabilities present
grep -rn "WEB_SEARCH_BRAVE\|WEB_SEARCH_SERPER\|CHECK_LIST" `
  arcclaw-core/src/main/java/com/arcclaw/core/agent/role/ToolCapability.java

# NETWORK_OUTBOUND exists in enum
grep -n "NETWORK_OUTBOUND" `
  arcclaw-core/src/main/java/com/arcclaw/core/security/ToolOperation.java
```

**Expected: all 3 grep hits return results, first grep returns nothing.**

***

### Layer 4 — Live Server Tool Registry ✅ (Already Verified)

```http
GET http://localhost:8080/api/v1/tools
X-ArcClaw-API-Key: {{validKey}}
```

**Expected response — with both keys = `none`:**
```json
[
  "delegate_to_agent",
  "sandbox_exec",
  "manage_schedule",
  "github",
  "check_list",
  "schedule_task",
  "ingest_document",
  "add_mcp_server",
  "memory_search"
]
```

**Verify:**
- `brave_web_search` — **absent** ✅ (`isEnabled()` gating works)
- `serper_web_search` — **absent** ✅ (`isEnabled()` gating works)
- `check_list` — **present as lowercase** ✅ (`name()` returns `ToolCapability.CHECK_LIST.toolName()`)
- `add_mcp_server` — **present** ✅

***

### Layer 5 — Enable Serper + Re-verify

```bash
# In .env, set real key:
ARCCLAW_SERPER_API_KEY=your-real-key
```

Restart dev server, then:

```http
GET http://localhost:8080/api/v1/tools
X-ArcClaw-API-Key: {{validKey}}
```

**Expected:** `"serper_web_search"` now appears in the list.

***

### Layer 6 — Live Serper Search (End-to-End)

Send via Telegram or CLI with `researcher` role:

```
"What is the latest version of Quarkus?"
```

**Verify in logs:**
```powershell
grep "Serper search:" .arcclaw.log
grep "TOOL_EXECUTED.*Serper" .arcclaw.log
```

**Expected log lines:**
```
INFO  Serper search: What is the latest version of Quarkus?
INFO  TOOL_EXECUTED | serper_web_search | session-xxx
```

***

### Pass Criteria Summary

| Layer | Gate | Status |
|---|---|---|
| 1 — Unit tests | 18/18 pass | ✅ Verified |
| 2 — Compile | Clean, 0 errors | ✅ Verified |
| 3 — Grep checks | No stale refs | ✅ Verified |
| 4 — Tool registry live | Correct names, search tools absent | ✅ Verified |
| 5 — Serper key enable | `serper_web_search` appears | Run when needed |
| 6 — Live search call | Audit log shows `TOOL_EXECUTED` | Run when needed |

Layers 1–4 are **done and verified**. Layers 5–6 are **on-demand** — run when you plug in a real Serper key.