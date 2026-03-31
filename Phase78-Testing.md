## Phase 78 — Testing Guide

### Layer 1 — Unit Tests (~1 min)

```powershell
./mvnw test `
  -pl arcclaw-channel-api,arcclaw-channel-web,arcclaw-app,arcclaw-core `
  -Dtest="WebReplyRendererTest,WebChannelPluginDiscoveryTest,AgentStatusResourceTest,AgentStateMachineRunnerTest" `
  --no-transfer-progress
```

| Test Class | Module | Coverage |
|---|---|---|
| `WebReplyRendererTest` | `arcclaw-channel-web` | All `ReplyNode` variants render correctly |
| `WebChannelPluginDiscoveryTest` | `arcclaw-channel-web` | CDI discovers `WebChannelPlugin`, all 5 components wired |
| `AgentStatusResourceTest` | `arcclaw-app` | `GET /api/v1/agents` returns correct shape |
| `AgentStateMachineRunnerTest` | `arcclaw-core` | `AgentStateChangedEvent` fires with `sessionId` |

**Expected: All pass | Failures: 0 | Errors: 0**

***

### Layer 2 — Compile + Index Check (~2 min)

```powershell
./mvnw clean compile test-compile `
  -pl arcclaw-channel-api,arcclaw-channel-web,arcclaw-app,arcclaw-core `
  --no-transfer-progress
```

Verify `AgentStateChangedEvent` record change propagated cleanly — any call site that used the old 3-arg constructor will fail here.

***

### Layer 3 — Live Server REST Checks

Start dev server, then:

```http
### 1. Confirm Web channel registered
GET http://localhost:8080/api/v1/tools
X-ArcClaw-API-Key: {{validKey}}
# Expected: existing 9 tools still present (no regressions)

### 2. Agent status endpoint (empty on cold start)
GET http://localhost:8080/api/v1/agents
X-ArcClaw-API-Key: {{validKey}}
# Expected: []

### 3. HITL pending approvals (empty on cold start)
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: {{validKey}}
# Expected: []
```

***

### Layer 4 — WebSocket Connectivity

Use any WebSocket client (Postman, `wscat`, or browser console):

```bash
# wscat (npm install -g wscat)
npx wscat -c ws://localhost:8080/ws/chat
```

**Expected response on connect:**
```json
{"type":"connected","sessionId":"<uuid>"}
```

Then send a message:
```json
{"type":"message","text":"Hello ArcClaw"}
```

**Expected:** Agent pipeline picks up the message, logs show:
```
StateMachine starting session=<uuid> goal=Hello ArcClaw
```

***

### Layer 5 — SSE Stream Verification

```bash
# Terminal 1 — open SSE stream
curl -N -H "X-ArcClaw-API-Key: key-internal-changeme" \
  http://localhost:8080/api/v1/chat/stream
```

```bash
# Terminal 2 — trigger an agent via Telegram or WebSocket
# Send: "What is the capital of France?"
```

**Expected SSE events in Terminal 1:**
```
data: {"eventType":"status_change","agentName":"researcher","payload":"active : What is the capital of France?"}
data: {"eventType":"heartbeat","agentName":"system","payload":"keep-alive"}
data: {"eventType":"status_change","agentName":"researcher","payload":"idle : What is the capital of France?"}
```

***

### Layer 6 — Agent Status During Execution

While an agent is running (between SSE `active` and `idle` events):

```http
GET http://localhost:8080/api/v1/agents
X-ArcClaw-API-Key: {{validKey}}
```

**Expected mid-execution:**
```json
[
  {
    "agentName": "researcher",
    "role": "researcher",
    "status": "active",
    "currentTask": "What is the capital of France?",
    "autonomyLevel": "supervised",
    "tokenCount": 0
  }
]
```

***

### Layer 7 — HITL via UI REST (Web-channel approval flow)

Trigger a tool that requires SUPERVISED approval via WebSocket, then:

```http
### List pending approvals
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: {{validKey}}
# Expected: [{taskId, toolName, params, requestedAt, agentName}]

### Approve via REST (simulates Lit UI button click)
POST http://localhost:8080/api/v1/hitl/{{taskId}}/approve
X-ArcClaw-API-Key: {{validKey}}
# Expected: {"status":"approved","taskId":"..."}

### Verify agent resumed
# Check logs:
# ApproveReflex] User approved pending tool execution for session: ...
# StateMachine starting session=... (resumed)
```

**Verify rejection path too:**
```http
POST http://localhost:8080/api/v1/hitl/{{taskId}}/reject
Content-Type: text/plain
X-ArcClaw-API-Key: {{validKey}}

Not needed right now
# Expected: {"status":"rejected","taskId":"..."}
```

***

### Layer 8 — Multi-Channel Non-Regression (Telegram still works)

```bash
# Send via Telegram: "ping"
# Verify: Telegram still responds correctly
# Verify in logs: no AmbiguousResolutionException from AllowlistHandler
grep "AmbiguousResolution" .arcclaw.log
# Expected: no output
```

***

### Pass Criteria Summary

| Layer | Gate | Status |
|---|---|---|
| 1 — Unit tests | All pass | ✅ Verified |
| 2 — Compile | Clean, `AgentStateChangedEvent` 4-arg used everywhere | ✅ Verified |
| 3 — REST health | Tools intact, agents/hitl return `[]` on cold start | Run now |
| 4 — WebSocket connect | `{"type":"connected","sessionId":"..."}` returned | Run now |
| 5 — SSE stream | `status_change` + `heartbeat` events emitted | Run now |
| 6 — Agent status live | Correct `status`/`autonomyLevel` during execution | Run now |
| 7 — HITL REST approve/reject | Reflex triggered, agent resumes | Run now |
| 8 — Telegram non-regression | No `AmbiguousResolutionException` | Run now |

Layers 1–2 are **done and verified**. Layers 3–8 are live integration — run in order, each layer builds on the previous.