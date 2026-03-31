## Phase 79 — Testing Guide (All Layers)

### Pre-Flight — Build the Full Stack

```powershell
# Terminal 1 — build everything (UI dist + backend)
./mvnw install -DskipTests --no-transfer-progress

# Terminal 2 — start Quarkus (serves UI statically from META-INF/resources)
cd arcclaw-app && ../mvnw quarkus:dev
```

**Expected startup logs:**
```
ChannelPluginRegistry: 2 channel(s) registered: websocket, telegram
arcclaw started in ~4s. Listening on: http://0.0.0.0:8080
```

***

### Layer 1 — Static Asset Serving (Quarkus)

```http
### 1.1 — index.html served at root
GET http://localhost:8080/
# Expected: HTTP 200, Content-Type: text/html
# Body contains: <arc-shell></arc-shell>

### 1.2 — JS bundle served
GET http://localhost:8080/assets/index-<hash>.js
# Expected: HTTP 200, Content-Type: application/javascript

### 1.3 — tokens.css served
GET http://localhost:8080/src/styles/tokens.css
# OR after Vite build: GET http://localhost:8080/assets/tokens-<hash>.css
# Expected: HTTP 200, contains --color-bg-base
```

***

### Layer 2 — Vite Dev Server (Frontend Dev Mode)

```powershell
# Terminal 3 — run Vite dev server (keep Quarkus running on 8080)
cd arcclaw-ui && npm run dev
# Expected: VITE ready on http://localhost:3000
```

**Verify proxy config works:**
```http
### 2.1 — REST proxied through Vite → Quarkus
GET http://localhost:3000/api/v1/agents
X-ArcClaw-API-Key: key-internal-changeme
# Expected: HTTP 200, [] (or active agents if any)

### 2.2 — CORS preflight from port 3000
OPTIONS http://localhost:8080/api/v1/agents
Origin: http://localhost:3000
Access-Control-Request-Headers: X-ArcClaw-API-Key
# Expected: HTTP 200
# Response headers contain:
#   Access-Control-Allow-Origin: http://localhost:3000
#   Access-Control-Allow-Headers: X-ArcClaw-API-Key,Content-Type,Accept
```

***

### Layer 3 — Onboarding Flow (`arc-onboarding`)

Open `http://localhost:3000` in browser.

**3.1 — Invalid key rejection:**
- Enter any random string as API key → click "Connect"
- Expected: Red error — "Invalid API key"
- Expected: No redirect

**3.2 — Network failure:**
- Stop Quarkus → enter any key → click "Connect"
- Expected: Error — "Cannot reach ArcClaw on port 8080"
- Restart Quarkus

**3.3 — Valid key accepted:**
- Enter `key-internal-changeme` → click "Connect"
- Expected: Spinner while testing → green success → redirect to `#/chat`
- Expected: API key **not** in `localStorage` — verify in DevTools → Application → Storage:
```javascript
// Browser DevTools console
localStorage.getItem('apiKey')  // Must return null
sessionStorage.getItem('apiKey') // Must return null
```

***

### Layer 4 — Hash Routing (`arc-shell`)

With valid key set, test each route directly:

| URL | Expected Component | Guard |
|---|---|---|
| `http://localhost:3000/#/chat` | `arc-chat-interface` | ✅ |
| `http://localhost:3000/#/agents` | `arc-agent-status` | ✅ |
| `http://localhost:3000/#/hitl` | `arc-hitl-panel` | ✅ |
| `http://localhost:3000/#/skills` | `arc-skills-panel` | ✅ |
| `http://localhost:3000/#/onboarding` | `arc-onboarding` | ✅ |

**Guard test — navigate without key:**
```javascript
// DevTools console — clear the in-memory key
document.querySelector('arc-shell')._apiKey = ''
window.location.hash = '#/agents'
// Expected: redirected to #/onboarding
```

***

### Layer 5 — SSE Connection (`arc-connection-badge`)

```bash
# Verify SSE fires from Quarkus — Terminal 4
curl -N \
  -H "X-ArcClaw-API-Key: key-internal-changeme" \
  -H "Accept: text/event-stream" \
  http://localhost:8080/api/v1/chat/stream
# Expected every 20s:
# data:{"eventType":"heartbeat","agentName":"system","payload":"keep-alive"}
```

**In browser (on `#/chat` or any route with nav visible):**
- `arc-connection-badge` should show **● Live** in green within 2 seconds of page load
- Disconnect Quarkus → badge changes to **○ Offline** within 5 seconds
- Restart Quarkus → badge recovers to **● Live**

***

### Layer 6 — WebSocket Auth (`WebChatEndpoint`)

**6.1 — `require-auth=false` (default dev mode):**
```bash
wscat -c ws://localhost:8080/ws/chat
# Expected: {"type":"connected","sessionId":"<uuid>","platformId":"<uuid>"}
```

**6.2 — `require-auth=true` with invalid key:**
```bash
# Temporarily set require-auth=true in application.yaml (or env var)
wscat -c ws://localhost:8080/ws/chat \
  -H "X-ArcClaw-API-Key: invalid-key"
# Expected: {"type":"error","text":"Invalid API key"} then connection closed
```

**6.3 — `require-auth=true` with valid key:**
```bash
wscat -c ws://localhost:8080/ws/chat \
  -H "X-ArcClaw-API-Key: key-internal-changeme"
# Expected: {"type":"connected","sessionId":"<uuid>","platformId":"<uuid>"}
```

***

### Layer 7 — Chat Interface (`arc-chat-interface`)

Navigate to `http://localhost:3000/#/chat`.

**7.1 — Connection established:**
- Expected: WS `connected` frame received → `session-ready` event fires → `arc-shell._sessionId` populated
- Verify in DevTools console:
```javascript
document.querySelector('arc-shell')._sessionId  // non-empty UUID
```

**7.2 — Send message, verify rendering:**
- Type "Hello ArcClaw" → press Enter or Send button
- Expected: User message appears right-aligned with user bubble colour
- Expected: Assistant reply appears left-aligned with assistant bubble colour
- Expected: Markdown rendered (try `**bold**` in message)

**7.3 — Auto-scroll:**
- Send 10+ messages to fill the viewport
- Expected: Panel auto-scrolls to latest message on each new reply

**7.4 — Approval prompt renders inline:**
- Send: "List the public repositories of GitHub user rsrini7 using the github tool."
- Expected: Approval card renders **full-width** with amber border (not a regular bubble)
- Expected: Text input is **hidden**, replaced by Approve + Deny buttons
- Click **Approve** → tool executes → reply arrives → text input reappears

**7.5 — Deny flow:**
- Trigger another approval prompt
- Click **Deny** → reason input expands → type "Not needed" → confirm
- Expected: "Action cancelled: Not needed" reply from agent

***

### Layer 8 — Agent Status (`arc-agent-status`)

Navigate to `#/agents` while a message is being processed:

**8.1 — Active agent during run:**
```
Expected card shows:
  Role: researcher (not "sessionId:researcher")     ← display name strip
  Status: ⚡ Active  (blue badge)
  Current task: "List the public repos..." (truncated at 60 chars)
  Autonomy: ✋ Supervised
  Tokens: — (0 shows as dash in Phase 79)
```

**8.2 — Agent idle after completion:**
- Status badge changes to **✓ Idle** (green)

**8.3 — Empty state:**
- On cold start with no messages sent
- Expected: "No agents active. Send a message in Chat to start a session."

**8.4 — SSE event updates agents list:**
- Send message → navigate to `#/agents` — must update without page refresh
- SSE `status_change` event → `arc-shell._agents` updated → `arc-agent-status` re-renders

***

### Layer 9 — HITL Panel (`arc-hitl-panel`)

Navigate to `#/hitl`.

**9.1 — Empty state (no pending approvals):**
```
Expected: "✓ No pending approvals. All agents running autonomously."
```

**9.2 — Approval card populated:**
- Trigger tool approval via chat (send github repo request)
- Navigate to `#/hitl` (or it auto-updates via 10s poll)
- Expected card shows:
  - Tool name: **github** (bold)
  - Params formatted as `action: repo`, `command: list`, `target: rsrini7`
  - `requestedAt` as relative time: "X minutes ago"
  - **Approve** (green) + **Deny** (red) buttons

**9.3 — Approve via HITL panel (REST path):**
```http
# Confirm REST path also works independently
GET http://localhost:8080/api/v1/hitl
X-ArcClaw-API-Key: key-internal-changeme
# Returns taskId

POST http://localhost:8080/api/v1/hitl/{{taskId}}/approve
X-ArcClaw-API-Key: key-internal-changeme
# Expected: {"status":"approved","taskId":"<id>"}
```

**9.4 — Loading state:**
- Click Approve → both buttons disabled + spinner on clicked button during request

**9.5 — HITL panel clears after action:**
- After approve or deny → card disappears → empty state shown

***

### Layer 10 — Skills Panel (`arc-skills-panel`)

Navigate to `#/skills`.

```
Expected: Tool cards rendered, each showing:
  - Tool name (e.g. "github", "web-search")
  - Description
  - Operation badge: NETWORK_OUTBOUND (blue) | READ_ONLY (green)
  - Autonomy chip

Loading state: spinner shown while fetch is in-flight
Error state: if /api/v1/tools returns error → inline error message
```

***

### Layer 11 — Production Build Verification

```powershell
# Build UI dist
cd arcclaw-ui && npm run build
# Expected: dist/ folder created, no TypeScript errors, no build warnings

# Check dist contents
ls arcclaw-ui/dist/
# Expected: index.html, assets/index-<hash>.js, assets/index-<hash>.css

# Rebuild Quarkus to pick up new dist
./mvnw package -pl arcclaw-app -DskipTests
# Verify dist assets were copied to jar
jar tf arcclaw-app/target/quarkus-app/quarkus/generated-bytecode.jar | grep META-INF/resources
# Expected: META-INF/resources/index.html, META-INF/resources/assets/...
```

***

### Layer 12 — Telegram Non-Regression

```powershell
grep "AmbiguousResolution" arcclaw-app/target/quarkus.log
# Expected: no output

grep "ChannelPluginRegistry" arcclaw-app/target/quarkus.log
# Expected: 2 channel(s) registered: websocket, telegram
```

Send a Telegram message → verify reply delivered. No regressions from adding `arcclaw-ui` module.

***

### Pass Criteria

| Layer | Gate | Status |
|---|---|---|
| 1 — Static assets | `index.html`, JS, CSS served from Quarkus port 8080 | Run now |
| 2 — Vite proxy | REST + CORS work via port 3000 | Run now |
| 3 — Onboarding | Invalid key rejected; valid key → `#/chat`; no localStorage | Run now |
| 4 — Routing | All 5 routes render correct component; guard redirects work | Run now |
| 5 — SSE badge | `● Live` shows; recovers after Quarkus restart | Run now |
| 6 — WS auth | `require-auth=true` rejects invalid keys at handshake | Run now |
| 7 — Chat UI | Messages render, markdown works, approval inline UX works | Run now |
| 8 — Agent status | Display name stripped, status badges correct, SSE-driven updates | Run now |
| 9 — HITL panel | Cards populate, approve/deny via UI, clears after action | Run now |
| 10 — Skills panel | Tools list renders, loading/error states work | Run now |
| 11 — Prod build | `npm run build` clean, assets copied to Quarkus jar | Run now |
| 12 — Telegram | No regressions, 2 channels registered | Run now |