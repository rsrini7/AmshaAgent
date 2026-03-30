Here is the complete Phase 77 testing strategy, structured from fastest to most thorough.

## Testing Phase 77 Changes

### Layer 1 — Unit Tests (No Server, No DB, ~30 sec)

Run only the Phase 77 test classes in the three affected modules:

```powershell
# From repo root — run all 3 modules that have P77 changes
./mvnw test `
  -pl arcclaw-core,arcclaw-mcp,arcclaw-app `
  -Dtest="SystemEnvironmentContextEnricherTest,CheckListToolTest,ClaudeCodeOAuthTokenExtractorTest,BraveWebSearchToolTest,SerperWebSearchToolTest,McpSelfRegisterToolTest" `
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **18 tests | Failures: 0 | Errors: 0**

| Test Class | Module | Expected |
|---|---|---|
| `SystemEnvironmentContextEnricherTest` | `arcclaw-core` | 2 pass |
| `CheckListToolTest` | `arcclaw-core` | 5 pass |
| `BraveWebSearchToolTest` | `arcclaw-core` | 5 pass |
| `SerperWebSearchToolTest` | `arcclaw-core` | 5 pass (or 6 with count test) |
| `ClaudeCodeOAuthTokenExtractorTest` | `arcclaw-app` | 5 pass |
| `McpSelfRegisterToolTest` | `arcclaw-mcp` | 4 pass |

***

### Layer 2 — Full Module Build + Compile Check (~2 min)

Catches compile errors that unit tests miss — specifically the `addEnumProperty` 3-arg issue (I1) and the stale `WEB_SEARCH` rename (I2):

```powershell
./mvnw clean compile test-compile `
  -pl arcclaw-core,arcclaw-mcp,arcclaw-app `
  --no-transfer-progress
```

If this is green, all enum references and method signatures are valid.

***

### Layer 3 — Stale Reference Grep (Before Committing)

```powershell
# Check for stale WEB_SEARCH (non-renamed) references
grep -rn "ToolCapability\.WEB_SEARCH[^_B]" `
  arcclaw-core/src arcclaw-app/src

# Confirm both new tools ARE referenced correctly
grep -rn "WEB_SEARCH_BRAVE\|WEB_SEARCH_SERPER" `
  arcclaw-core/src arcclaw-app/src

# Confirm NETWORK_OUTBOUND exists in the enum
grep -n "NETWORK_OUTBOUND" `
  arcclaw-core/src/main/java/com/arcclaw/core/security/ToolOperation.java

# Confirm VaultConstants has both new keys
grep -n "CLAUDE_CODE_OAUTH\|SERPER_API_KEY" `
  arcclaw-core/src/main/java/com/arcclaw/core/security/VaultConstants.java

# Confirm isEnabled() filtering is in ToolRegistry (3 locations)
grep -n "isEnabled" `
  arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolRegistry.java
```

***

### Layer 4 — ToolRegistry CDI Smoke Test (~3 min)

This verifies that `isEnabled()` filtering works at the CDI container level — that disabled tools (`brave_web_search` and `serper_web_search` with `api-key=none`) are **absent** from `listTools()` at startup. Add this test or run it if it already exists:

```powershell
# Run any existing ToolRegistry-level integration tests
./mvnw test -pl arcclaw-core `
  -Dtest="ToolRegistryTest" `
  -Dsurefire.failIfNoSpecifiedTests=false
```

If no `ToolRegistryTest` exists, manually verify after startup (Layer 5).

***

### Layer 5 — Runtime Smoke Test (Full Server, ~1 min startup)

Start the server with both keys unset (`none`) to verify `isEnabled()` works end-to-end:

```powershell
# Start in dev mode — both search tools should be absent from tool list
./mvnw quarkus:dev -pl arcclaw-app

# In another terminal — check tool registry via SystemInfo endpoint
curl -s -H "X-ArcClaw-API-Key: key-dev-changeme" \
  http://localhost:8080/api/v1/system/info | jq '.tools'

# brave_web_search and serper_web_search must NOT appear
# CheckListTool, add_mcp_server MUST appear
```

Then enable Serper only and verify it appears:

```powershell
# In .env, set:
# ARCCLAW_SERPER_API_KEY=your-real-key

# Restart, then re-check — serper_web_search MUST now appear
curl -s -H "X-ArcClaw-API-Key: key-dev-changeme" \
  http://localhost:8080/api/v1/system/info | jq '.tools'
```

***

### Layer 6 — Live API Smoke Test (Real Keys, Optional)

Only run after setting real keys in `.env`:

```powershell
# Test Serper via agent (Telegram or CLI)
./mvnw quarkus:dev -pl arcclaw-app

# CLI one-shot
./mvnw quarkus:run -pl arcclaw-app \
  -Dquarkus.args="ask 'What is Quarkus 3.27 release date?' --role researcher"

# The agent should invoke serper_web_search and return Google results
# Check logs for: "Serper search: What is Quarkus 3.27 release date?"
# Check audit: curl .../api/v1/audit | grep TOOL_EXECUTED
```

***

### Quick Reference — Run Order

```
Layer 1  →  18 unit tests green          (always run)
Layer 2  →  compile clean                (always run)
Layer 3  →  grep checks pass             (before git push)
Layer 4  →  ToolRegistry CDI test        (if test exists)
Layer 5  →  server starts, tools gated   (before merging)
Layer 6  →  live Serper call works       (after setting real key)
```

Layers 1–3 are mandatory before declaring Phase 77 closed. Layer 5 is the final gate before starting Phase 78.