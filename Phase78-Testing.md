## Phase 78 — Final Testing Guide (All 8 Layers)

### Layer 1 — Unit Tests (~1 min)

```powershell
./mvnw test `
  -pl arcclaw-channel-api,arcclaw-channel-web,arcclaw-app,arcclaw-core `
  -Dtest="WebReplyRendererTest,WebChannelPluginDiscoveryTest,AgentStatusResourceTest,AgentStateMachineRunnerTest" `
  --no-transfer-progress
```

**Expected: All pass | Failures: 0 | Errors: 0** ✅ Verified

***

### Layer 2 — Compile + Index Check (~2 min)

```powershell
./mvnw clean compile test-compile `
  -pl arcclaw-channel-api,arcclaw-channel-web,arcclaw-app,arcclaw-core `
  --no-transfer-progress
```

Verify `AgentStateChangedEvent` 4-arg constructor used at all call sites. Any missed call site fails here. ✅ Verified

***

### Layer 3 — Startup Health Checks

Start dev server, then verify:

```http
### 3.1 — Tools intact (no regressions)
GET http://localhost:8080/api/v1/tools
X-ArcClaw-API-Key: {{validKey}}
# Expected: existing tools list, HTTP 200

### 3.2 — Agents empty on cold start (in-memory store wiped on restart)
GET http://localhost:8080/api/v1/agents
X-ArcClaw-API-Key: {{validKey}}
# Expected: []

### 3.3 — HITL empty on cold start
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: {{validKey}}
# Expected: []
```

**Startup log to verify:**
```
Zombie recovery: no orphaned IN_PROGRESS checkpoints found.  ← clean start
ChannelPluginRegistry: 2 channel(s) registered: websocket, telegram
arcclaw started in ~4s
```

✅ Verified

***

### Layer 4 — SSE Stream (open first, keep alive)

```bash
# Terminal 1 — open BEFORE sending any WebSocket message
curl -N \
  -H "X-ArcClaw-API-Key: key-internal-changeme" \
  -H "Accept: text/event-stream" \
  http://localhost:8080/api/v1/chat/stream
```

**Expected immediately:**
```
data:{"eventType":"heartbeat","agentName":"system","payload":"keep-alive"}
```

Heartbeat fires every 20 seconds — confirms SSE pipe is alive. ✅ Verified

> **Note:** This endpoint is `GET` only. `POST` returns `405 Method Not Allowed` — that is correct behaviour.

***

### Layer 5 — WebSocket Connectivity

```bash
# Terminal 2
wscat -c ws://localhost:8080/ws/chat
```

**Expected connected event:**
```json
{
  "type": "connected",
  "sessionId": "<arcclaw-uuid>",
  "platformId": "<connection-uuid>"
}
```

Key verification — **single session** in server logs:
```
SessionRegistry: created session <uuid> for [WEBSOCKET/<platformId>/...]
WebSocket opened: connId=... platformId=... arcclawSession=...   ← same UUID
```

No second `SessionRegistry: created session` line when first message arrives. ✅ Verified

***

### Layer 6 — Agent Run + SSE Events

Send a simple message on the WebSocket:

```json
{"type":"message","text":"Hello ArcClaw"}
```

**Expected SSE events in Terminal 1:**
```
data:{"eventType":"status_change","agentName":"coordinator","payload":"active : Hello ArcClaw"}
data:{"eventType":"status_change","agentName":"coordinator","payload":"idle : Hello ArcClaw"}
```

**Expected `GET /agents` during run:**
```json
[{
  "agentName": "coordinator",
  "role": "coordinator",
  "status": "active",
  "currentTask": "Hello ArcClaw",
  "autonomyLevel": "supervised",
  "tokenCount": 0
}]
```

**Expected WebSocket reply:**
```json
{
  "type": "message",
  "role": "assistant",
  "text": "Hello! How can I help you today?..."
}
```

**Classifier log to verify (no NPE, no blocked thread):**
```
CLASSIFIER invoked, SLA=437ms, role=coordinator   ← <500ms is healthy
```

✅ Verified

***

### Layer 7 — Full HITL Loop via WebSocket

**Step 1 — Trigger approval:**
```json
{"type":"message","text":"List the public repositories of GitHub user rsrini7 using the github tool."}
```

**Expected approval prompt back on WebSocket:**
```json
{
  "type": "message",
  "role": "assistant",
  "text": "⏸ Approval needed for tool: github\n\n📋 Params:\n{\"action\":\"repo\",\"command\":\"list\",\"target\":\"rsrini7\"}\n\nReply /approve or /deny"
}
```

**Expected server logs:**
```
STATE-MACHINE Pausing session <uuid> for human approval of tool github
Stamped channel info on checkpoint session=<uuid> channel=websocket platformId=<uuid>
```

**Step 2 — Poll HITL (VS Code REST Client):**
```http
# @name hitlPending
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: {{validKey}}
# Expected: [{taskId, toolName:"github", params:{...}, agentName:"researcher"}]
```

**Step 3 — Approve on same WebSocket connection:**
```json
{"type":"message","text":"/approve"}
```

**Expected logs:**
```
CommandDispatchHandler: command='/approve' session=<uuid>
ApproveReflex: User approved pending tool execution for session: <uuid>
AgentLoop: Resuming session <uuid> executing pending tool github
ToolRegistry: Invoking tool 'github'
CHECKPOINT_COMPLETED
APPROVAL_GRANTED
```

**Expected WebSocket reply:**
```json
{
  "type": "message",
  "role": "assistant",
  "text": "Here are the public repositories of GitHub user rsrini7:..."
}
```

**Step 4 — Verify HITL cleared:**
```http
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: {{validKey}}
# Expected: []
```

✅ Verified

***

### Layer 7B — HITL Reject Path

```json
{"type":"message","text":"List the public repositories of GitHub user rsrini7 using the github tool."}
```

Wait for approval prompt, then:

```json
{"type":"message","text":"/deny Not needed right now"}
```

**Expected:**
```
DenyReflex triggered for session <uuid>
CHECKPOINT status → FAILED or DENIED
```

**Expected WebSocket reply:**
```json
{
  "type": "message",
  "role": "assistant",
  "text": "Action cancelled: Not needed right now"
}
```

***

### Layer 7C — HITL via REST UI (HitlResource)

For approvals triggered from the Lit UI dashboard (not WebSocket `/approve`):

```http
# @name hitlPending
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: {{validKey}}

###

# Uses taskId extracted from Step 1 — no Content-Type, no body
POST http://localhost:8080/api/v1/hitl/{{hitlPending.response.body.[0].taskId}}/approve
X-ArcClaw-API-Key: {{validKey}}
# Expected: {"status":"approved","taskId":"<id>"}

###

# Reject with reason
POST http://localhost:8080/api/v1/hitl/{{hitlPending.response.body.[0].taskId}}/reject
Content-Type: text/plain
X-ArcClaw-API-Key: {{validKey}}

Not needed right now
# Expected: {"status":"rejected","taskId":"<id>","reason":"Not needed right now"}
```

> **Note:** HITL REST only works for WebSocket-originated sessions (`channel=websocket`). Sessions from the legacy REST sessions endpoint (`channel=rest_api`) get the synthetic `platformId=rest:api` guard applied and will resume but cannot push a reply to a sender.

***

### Layer 8 — Telegram Non-Regression

Send any message via Telegram (e.g. "How r u"), then verify:

```powershell
# No CDI ambiguity errors
grep "AmbiguousResolution" arcclaw.log
# Expected: no output

# No channel registration failure
grep "ChannelPluginRegistry" arcclaw.log
# Expected: 2 channel(s) registered: websocket, telegram
```

**Expected Telegram logs:**
```
AllowlistHandler     → passes (no AmbiguousResolutionException)  ✅
CLASSIFIER SLA       → <500ms on Groq                            ✅
Agent FINISH         → iteration 1                               ✅
CHECKPOINT_COMPLETED ✅
TELEGRAM-SEND        → reply delivered                           ✅
```

✅ Verified

***

### Pass Criteria

| Layer | Gate | Status |
|---|---|---|
| 1 — Unit tests | All 4 test classes pass | ✅ Verified |
| 2 — Compile | Clean, `AgentStateChangedEvent` 4-arg everywhere | ✅ Verified |
| 3 — Startup health | Tools/agents/hitl endpoints respond, zombie recovery clean | ✅ Verified |
| 4 — SSE stream | `GET` returns heartbeat every 20s | ✅ Verified |
| 5 — WebSocket connect | Single session UUID, `connected` event with `sessionId` + `platformId` | ✅ Verified |
| 6 — Agent run | SSE `status_change` fires, `GET /agents` non-empty during run | ✅ Verified |
| 7 — HITL WebSocket | `/approve` resumes agent, tool executes, reply delivered | ✅ Verified |
| 7B — HITL reject | `/deny` cancels correctly | Run now |
| 7C — HITL REST | `POST /hitl/{id}/approve` returns `200`, no body required | Run now |
| 8 — Telegram | No `AmbiguousResolutionException`, reply delivered | ✅ Verified |