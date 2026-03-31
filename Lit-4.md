# Lit-4.md — ArcClaw UI Component Specification
## Phase 79 Frontend Design Reference

> **Scope:** This document is the single source of truth for the `arcclaw-ui` Lit 3 + TypeScript 5
> frontend module. All component shapes, API contracts, event buses, and routing rules are derived
> from the Phase 78 backend (Quarkus 3.27.2, port 8080).
> Do not implement any shape not listed here without updating this document first.

---

## 1. Module Structure

```
arcclaw-ui/
├── pom.xml                          # Maven module, wraps frontend-maven-plugin
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html                       # Single entry point — mounts <arc-shell>
└── src/
    ├── main.ts                      # Registers all custom elements
    ├── api/
    │   ├── client.ts                # Typed fetch wrapper (injects X-ArcClaw-API-Key)
    │   ├── sse.ts                   # SSE connection manager
    │   ├── websocket.ts             # WebSocket connection manager
    │   └── types.ts                 # All API DTOs as TypeScript interfaces
    ├── context/
    │   └── arc-context.ts           # Lit Context API — ApiKey, SessionId, SseStatus
    ├── components/
    │   ├── arc-shell.ts             # Root shell — router + SSE owner
    │   ├── arc-onboarding.ts        # API key entry + connection test
    │   ├── arc-nav.ts               # Sidebar navigation
    │   ├── arc-connection-badge.ts  # SSE/WS live status indicator
    │   ├── arc-chat-interface.ts    # WebSocket chat panel
    │   ├── arc-agent-status.ts      # Agent lifecycle status grid
    │   ├── arc-hitl-panel.ts        # Pending approvals list
    │   └── arc-skills-panel.ts      # Registered tools/skills list
    └── styles/
        └── tokens.css               # Design tokens (imported in each component)
```

---

## 2. API Contracts

All shapes are derived from Phase 78 Java records. Do not invent fields.

### 2.1 REST DTOs

```typescript
// src/api/types.ts

export interface AgentStatusDto {
  agentName: string;        // composite key "sessionId:role" — display role part only
  role: string;
  status: 'active' | 'idle' | 'waiting_approval' | 'error';
  currentTask: string | null;
  autonomyLevel: 'readonly' | 'background' | 'supervised' | 'full';
  tokenCount: number;       // Phase 78: always 0 — Phase 79 wires real value
}

export interface PendingApprovalDto {
  taskId: string;
  toolName: string;
  params: Record<string, unknown>;
  requestedAt: string;      // ISO-8601 instant
  agentName: string;
}

export interface ToolDto {
  name: string;
  description: string;
  operationType: string;    // NETWORK_OUTBOUND | READ_ONLY | etc.
  autonomyRequired: string;
}

export interface ApprovalResultDto {
  status: 'approved' | 'rejected';
  taskId: string;
  reason?: string;
}
```

### 2.2 WebSocket Message Shapes

**Endpoint:** `ws://localhost:8080/ws/chat`

```typescript
// Outbound (UI → server)
export interface WsOutboundMessage {
  type: 'message';
  text: string;             // plain text or /approve | /deny <reason>
}

// Inbound — on connection established
export interface WsConnectedEvent {
  type: 'connected';
  sessionId: string;        // ArcClaw session UUID — use for REST/HITL calls
  platformId: string;       // WebSocket connection UUID — internal reference only
}

// Inbound — agent reply
export interface WsAssistantMessage {
  type: 'message';
  role: 'assistant';
  text: string;             // Markdown formatted
}

// Discriminated union for all inbound frames
export type WsInboundFrame = WsConnectedEvent | WsAssistantMessage;
```

### 2.3 SSE Event Shapes

**Endpoint:** `GET /api/v1/chat/stream`
**Headers:** `Accept: text/event-stream`, `X-ArcClaw-API-Key: <key>`

```typescript
export interface AgentEvent {
  eventType: 'status_change' | 'heartbeat';
  agentName: string;        // agent role name | 'system' for heartbeat
  payload: string;          // "active : <task>" | "idle : <task>" | "keep-alive"
}
```

**Parsed payload helper:**
```typescript
// payload format: "<status> : <task>" or "<status>"
export function parseAgentPayload(payload: string): { status: string; task: string | null } {
  const sep = payload.indexOf(' : ');
  if (sep === -1) return { status: payload.trim(), task: null };
  return { status: payload.slice(0, sep).trim(), task: payload.slice(sep + 3).trim() };
}
```

---

## 3. Hash Routing

`arc-shell` owns the router. No external router library.

| Hash | Component Rendered | Guard |
|---|---|---|
| `#/onboarding` | `<arc-onboarding>` | None — always accessible |
| `#/chat` | `<arc-chat-interface>` | Requires API key |
| `#/agents` | `<arc-agent-status>` | Requires API key |
| `#/hitl` | `<arc-hitl-panel>` | Requires API key |
| `#/skills` | `<arc-skills-panel>` | Requires API key |
| `` (empty) | Redirect → `#/onboarding` if no key, else `#/chat` | — |

**Routing logic in `arc-shell`:**
```typescript
private _route(): string {
  return window.location.hash || '#/onboarding';
}
// Listen: window.addEventListener('hashchange', () => this.requestUpdate())
```

---

## 4. Component Specifications

### 4.1 `<arc-shell>` — Root Application Shell

**Tag:** `arc-shell`
**File:** `src/components/arc-shell.ts`
**Scope:** Singleton. Mounted once in `index.html`.

**Responsibilities:**
- Owns the single SSE connection (created after API key is set, torn down on key clear)
- Owns WebSocket session state (`sessionId`, `platformId`)
- Distributes `AgentStatusDto[]` and `AgentEvent[]` to child components via `@property()`
- Implements hash router
- Provides `ArcContext` (API key, session, SSE status) to descendants

**State (`@state()`):**
```typescript
@state() private _apiKey: string = '';
@state() private _sessionId: string = '';
@state() private _platformId: string = '';
@state() private _agents: AgentStatusDto[] = [];
@state() private _pendingApprovals: PendingApprovalDto[] = [];
@state() private _sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';
@state() private _route: string = window.location.hash || '#/onboarding';
```

**SSE Lifecycle:**
```typescript
// Start SSE after API key confirmed
private _startSse(): void {
  // EventSource does not support custom headers — use fetch + ReadableStream
  // or pass key as query param: /api/v1/chat/stream?key=...
  // Preferred: query param approach for SSE
}
// On status_change event → update _agents array from AgentEvent
// On heartbeat → update _sseStatus timestamp only (no re-render needed)
```

**Polling:** `GET /api/v1/hitl` every **10 seconds** while SSE is connected to keep `_pendingApprovals` fresh. Stop polling when no API key.

**Template skeleton:**
```typescript
render() {
  return html`
    <arc-nav .route=${this._route} .sseStatus=${this._sseStatus}></arc-nav>
    <main>
      ${this._renderRoute()}
    </main>
  `;
}

private _renderRoute() {
  if (!this._apiKey && this._route !== '#/onboarding') {
    window.location.hash = '#/onboarding';
    return nothing;
  }
  switch (this._route) {
    case '#/chat':    return html`<arc-chat-interface .apiKey=${this._apiKey} .sessionId=${this._sessionId} @session-ready=${this._onSessionReady}></arc-chat-interface>`;
    case '#/agents':  return html`<arc-agent-status .agents=${this._agents}></arc-agent-status>`;
    case '#/hitl':    return html`<arc-hitl-panel .apiKey=${this._apiKey} .approvals=${this._pendingApprovals} @approval-action=${this._onApprovalAction}></arc-hitl-panel>`;
    case '#/skills':  return html`<arc-skills-panel .apiKey=${this._apiKey}></arc-skills-panel>`;
    default:          return html`<arc-onboarding @api-key-set=${this._onApiKeySet}></arc-onboarding>`;
  }
}
```

---

### 4.2 `<arc-onboarding>` — API Key Entry

**Tag:** `arc-onboarding`
**File:** `src/components/arc-onboarding.ts`

**Purpose:** First-run screen. Collects API key, tests connection, emits to shell.

**State:**
```typescript
@state() private _inputKey: string = '';
@state() private _testing: boolean = false;
@state() private _error: string = '';
@state() private _success: boolean = false;
```

**Connection test:** `GET /api/v1/agents` with the entered key.
- `200` → emit `api-key-set`, redirect to `#/chat`
- `401/403` → show "Invalid API key"
- Network error → show "Cannot reach ArcClaw on port 8080"

**Emits:**
```typescript
// CustomEvent on successful key validation
this.dispatchEvent(new CustomEvent('api-key-set', {
  detail: { apiKey: this._inputKey },
  bubbles: true, composed: true
}));
```

**UX rules:**
- API key input: `type="password"` with toggle show/hide
- Test button shows spinner while `_testing`
- Do not persist key to `localStorage` — memory only

---

### 4.3 `<arc-nav>` — Sidebar Navigation

**Tag:** `arc-nav`
**File:** `src/components/arc-nav.ts`

**Properties:**
```typescript
@property() route: string = '#/chat';
@property() sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';
```

**Nav items:**

| Label | Hash | Icon (unicode fallback) |
|---|---|---|
| Chat | `#/chat` | 💬 |
| Agents | `#/agents` | 🤖 |
| Approvals | `#/hitl` | ✋ |
| Skills | `#/skills` | 🔧 |

**Active state:** compare `route` property to each link's hash.

**Connection badge:** renders `<arc-connection-badge>` in the nav footer with `sseStatus` passed as property.

---

### 4.4 `<arc-connection-badge>` — Live Status Indicator

**Tag:** `arc-connection-badge`
**File:** `src/components/arc-connection-badge.ts`

**Properties:**
```typescript
@property() status: 'connected' | 'disconnected' | 'error' = 'disconnected';
@property() lastHeartbeat: number = 0;   // Date.now() of last heartbeat
```

**Rendering:**
| Status | Color | Label |
|---|---|---|
| `connected` | `#22c55e` (green) | ● Live |
| `disconnected` | `#94a3b8` (slate) | ○ Offline |
| `error` | `#ef4444` (red) | ● Error |

No external icon library — use CSS `border-radius: 50%` dot.

---

### 4.5 `<arc-chat-interface>` — WebSocket Chat Panel

**Tag:** `arc-chat-interface`
**File:** `src/components/arc-chat-interface.ts`

**Properties:**
```typescript
@property() apiKey: string = '';
@property() sessionId: string = '';    // passed in from arc-shell after connect
```

**State:**
```typescript
@state() private _messages: ChatMessage[] = [];
@state() private _input: string = '';
@state() private _wsStatus: 'connecting' | 'open' | 'closed' | 'error' = 'closed';
@state() private _awaitingApproval: boolean = false;
@state() private _approvalPrompt: string = '';
```

**Internal type:**
```typescript
interface ChatMessage {
  id: string;             // crypto.randomUUID()
  role: 'user' | 'assistant' | 'system';
  text: string;
  timestamp: number;
  isApprovalPrompt?: boolean;
}
```

**WebSocket lifecycle:**
```typescript
// Connect on connectedCallback if apiKey is set
// On 'connected' frame → emit 'session-ready' event with sessionId + platformId
// On 'message' frame → push to _messages
// Detect approval prompt: text.includes('⏸ Approval needed')
//   → set _awaitingApproval = true, _approvalPrompt = text
```

**Emits:**
```typescript
this.dispatchEvent(new CustomEvent('session-ready', {
  detail: { sessionId, platformId },
  bubbles: true, composed: true
}));
```

**Approval UX:**
When `_awaitingApproval` is true, render inline approve/deny buttons **instead of** the text input:
```typescript
// Approve button → sends {"type":"message","text":"/approve"}
// Deny button → shows reason input → sends {"type":"message","text":"/deny <reason>"}
// After action → _awaitingApproval = false, re-show text input
```

**Message rendering:**
- User messages: right-aligned, `background: var(--color-user-bubble)`
- Assistant messages: left-aligned, `background: var(--color-assistant-bubble)`
- Approval prompts: full-width, `background: var(--color-warning-bg)`, amber border
- Render Markdown: use `marked` (lightweight, no external CDN — bundled via npm)

**Scroll:** auto-scroll to bottom on new message using:
```typescript
private _scrollToBottom() {
  this.shadowRoot?.querySelector('.messages')
    ?.scrollTo({ top: 999999, behavior: 'smooth' });
}
```

---

### 4.6 `<arc-agent-status>` — Agent Lifecycle Grid

**Tag:** `arc-agent-status`
**File:** `src/components/arc-agent-status.ts`

**Properties:**
```typescript
@property({ type: Array }) agents: AgentStatusDto[] = [];
```

**Display name logic:** `agentName` from API is `"sessionId:role"` composite key. Strip prefix:
```typescript
const displayName = (name: string) => name.includes(':') ? name.split(':')[1] : name;
```

**Status badge colours:**
| Status | Background | Label |
|---|---|---|
| `active` | `#3b82f6` (blue) | ⚡ Active |
| `idle` | `#22c55e` (green) | ✓ Idle |
| `waiting_approval` | `#f59e0b` (amber) | ✋ Awaiting |
| `error` | `#ef4444` (red) | ✗ Error |

**Empty state:** When `agents.length === 0`, show:
```
No agents active. Send a message in Chat to start a session.
```

**Grid layout:** CSS Grid, 2 columns on desktop, 1 on mobile. Each card shows:
- Agent role name (display name, not composite key)
- Status badge
- Current task (truncated to 60 chars with `...`)
- Autonomy level chip
- Token count (shows `—` when 0 in Phase 79)

---

### 4.7 `<arc-hitl-panel>` — Human-in-the-Loop Approvals

**Tag:** `arc-hitl-panel`
**File:** `src/components/arc-hitl-panel.ts`

**Properties:**
```typescript
@property() apiKey: string = '';
@property({ type: Array }) approvals: PendingApprovalDto[] = [];
```

**Emits:**
```typescript
// After successful approve or reject REST call
this.dispatchEvent(new CustomEvent('approval-action', {
  detail: { taskId, action: 'approved' | 'rejected' },
  bubbles: true, composed: true
}));
// arc-shell listens and refreshes _pendingApprovals
```

**Per-approval card renders:**
- Tool name (bold)
- Agent name
- Params: formatted as `key: value` list (not raw JSON)
- `requestedAt` — formatted as relative time ("2 minutes ago")
- **Approve** button (green) → `POST /api/v1/hitl/{taskId}/approve` — no body, no Content-Type
- **Deny** button (red) → expands inline reason input → `POST /api/v1/hitl/{taskId}/reject` with `Content-Type: text/plain` body

**Loading state:** Show spinner on the clicked button while request is in-flight. Disable both buttons.

**Error state:** Show inline error message below the card. Do not dismiss the card on error.

**Empty state:**
```
✓ No pending approvals. All agents running autonomously.
```

---

### 4.8 `<arc-skills-panel>` — Registered Tools

**Tag:** `arc-skills-panel`
**File:** `src/components/arc-skills-panel.ts`

**Properties:**
```typescript
@property() apiKey: string = '';
```

**State:**
```typescript
@state() private _tools: ToolDto[] = [];
@state() private _loading: boolean = false;
@state() private _error: string = '';
```

**Data source:** `GET /api/v1/tools` — fetched on `connectedCallback`.

**Renders:** Simple list of tool cards, each showing:
- Tool name
- Description
- Operation type badge (colour-coded: `NETWORK_OUTBOUND` = blue, `READ_ONLY` = green)
- Autonomy required chip

---

## 5. Context API

Single context shared across all components without prop drilling for global state.

```typescript
// src/context/arc-context.ts
import { createContext } from '@lit/context';

export interface ArcAppContext {
  apiKey: string;
  sessionId: string;
  sseStatus: 'connected' | 'disconnected' | 'error';
}

export const arcContext = createContext<ArcAppContext>('arc-app-context');
```

`arc-shell` is the **provider**. All components may consume `apiKey` and `sseStatus` from context instead of requiring them as `@property()` where convenient. However, data arrays (`agents`, `approvals`) are always passed as `@property()` — never via context (context is for scalars only).

---

## 6. Design Tokens

```css
/* src/styles/tokens.css */
:host {
  /* Palette */
  --color-bg-base:            #0f172a;   /* slate-900 */
  --color-bg-surface:         #1e293b;   /* slate-800 */
  --color-bg-elevated:        #334155;   /* slate-700 */
  --color-border:             #475569;   /* slate-600 */
  --color-text-primary:       #f1f5f9;   /* slate-100 */
  --color-text-secondary:     #94a3b8;   /* slate-400 */
  --color-accent:             #6366f1;   /* indigo-500 */
  --color-accent-hover:       #818cf8;   /* indigo-400 */
  --color-success:            #22c55e;   /* green-500 */
  --color-warning:            #f59e0b;   /* amber-500 */
  --color-error:              #ef4444;   /* red-500 */
  --color-info:               #3b82f6;   /* blue-500 */

  /* Chat bubbles */
  --color-user-bubble:        #312e81;   /* indigo-900 */
  --color-assistant-bubble:   #1e293b;   /* slate-800 */
  --color-warning-bg:         #451a03;   /* amber-950 */

  /* Typography */
  --font-family:              'Inter', system-ui, sans-serif;
  --font-size-sm:             0.875rem;
  --font-size-base:           1rem;
  --font-size-lg:             1.125rem;
  --font-weight-normal:       400;
  --font-weight-medium:       500;
  --font-weight-bold:         700;

  /* Spacing */
  --space-1:  0.25rem;
  --space-2:  0.5rem;
  --space-3:  0.75rem;
  --space-4:  1rem;
  --space-6:  1.5rem;
  --space-8:  2rem;

  /* Radius */
  --radius-sm:  0.25rem;
  --radius-md:  0.5rem;
  --radius-lg:  0.75rem;
  --radius-full: 9999px;

  /* Shadows */
  --shadow-sm:  0 1px 2px rgb(0 0 0 / 0.4);
  --shadow-md:  0 4px 6px rgb(0 0 0 / 0.4);
}
```

---

## 7. Custom Events Reference

All events bubble and are composed (cross shadow DOM boundary).

| Event Name | Fired By | Payload | Consumed By |
|---|---|---|---|
| `api-key-set` | `arc-onboarding` | `{ apiKey: string }` | `arc-shell` |
| `session-ready` | `arc-chat-interface` | `{ sessionId: string, platformId: string }` | `arc-shell` |
| `approval-action` | `arc-hitl-panel` | `{ taskId: string, action: 'approved' \| 'rejected' }` | `arc-shell` |

---

## 8. API Client

```typescript
// src/api/client.ts
export class ArcClient {
  constructor(
    private readonly baseUrl: string,
    private readonly apiKey: string
  ) {}

  private headers(): HeadersInit {
    return {
      'X-ArcClaw-API-Key': this.apiKey,
      'Content-Type': 'application/json',
    };
  }

  async getAgents(): Promise<AgentStatusDto[]> {
    const r = await fetch(`${this.baseUrl}/agents`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /agents failed: ${r.status}`);
    return r.json();
  }

  async getHitl(): Promise<PendingApprovalDto[]> {
    const r = await fetch(`${this.baseUrl}/hitl`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /hitl failed: ${r.status}`);
    return r.json();
  }

  async approveHitl(taskId: string): Promise<ApprovalResultDto> {
    // No Content-Type, no body — approved via path only
    const r = await fetch(`${this.baseUrl}/hitl/${taskId}/approve`, {
      method: 'POST',
      headers: { 'X-ArcClaw-API-Key': this.apiKey },
    });
    if (!r.ok) throw new Error(`POST /hitl/${taskId}/approve failed: ${r.status}`);
    return r.json();
  }

  async rejectHitl(taskId: string, reason: string): Promise<ApprovalResultDto> {
    const r = await fetch(`${this.baseUrl}/hitl/${taskId}/reject`, {
      method: 'POST',
      headers: {
        'X-ArcClaw-API-Key': this.apiKey,
        'Content-Type': 'text/plain',
      },
      body: reason || 'Rejected by user',
    });
    if (!r.ok) throw new Error(`POST /hitl/${taskId}/reject failed: ${r.status}`);
    return r.json();
  }

  async getTools(): Promise<ToolDto[]> {
    const r = await fetch(`${this.baseUrl}/tools`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /tools failed: ${r.status}`);
    return r.json();
  }
}
```

---

## 9. SSE Connection Manager

```typescript
// src/api/sse.ts
// EventSource does not support custom headers.
// Strategy: pass API key as query param — backend must allow ?key= as alternative.
// If backend does not support query param auth, use fetch + ReadableStream decoder.

export class SseManager {
  private _reader: ReadableStreamDefaultReader<string> | null = null;

  constructor(
    private readonly url: string,
    private readonly apiKey: string,
    private readonly onEvent: (event: AgentEvent) => void,
    private readonly onStatusChange: (status: 'connected' | 'disconnected' | 'error') => void
  ) {}

  async connect(): Promise<void> {
    try {
      const response = await fetch(this.url, {
        headers: {
          'Accept': 'text/event-stream',
          'X-ArcClaw-API-Key': this.apiKey,
        },
      });
      if (!response.ok || !response.body) {
        this.onStatusChange('error');
        return;
      }
      this.onStatusChange('connected');
      this._reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      this._readLoop();
    } catch {
      this.onStatusChange('error');
    }
  }

  private async _readLoop(): Promise<void> {
    let buffer = '';
    while (true) {
      const { done, value } = await this._reader!.read();
      if (done) { this.onStatusChange('disconnected'); break; }
      buffer += value;
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const event: AgentEvent = JSON.parse(line.slice(5).trim());
            this.onEvent(event);
          } catch { /* malformed frame — ignore */ }
        }
      }
    }
  }

  disconnect(): void {
    this._reader?.cancel();
    this._reader = null;
    this.onStatusChange('disconnected');
  }
}
```

---

## 10. WebSocket Connection Manager

```typescript
// src/api/websocket.ts
export class WsManager {
  private _ws: WebSocket | null = null;

  constructor(
    private readonly url: string,
    private readonly onFrame: (frame: WsInboundFrame) => void,
    private readonly onStatusChange: (status: 'connecting' | 'open' | 'closed' | 'error') => void
  ) {}

  connect(): void {
    this._ws = new WebSocket(this.url);
    this.onStatusChange('connecting');

    this._ws.onopen = () => this.onStatusChange('open');
    this._ws.onclose = () => this.onStatusChange('closed');
    this._ws.onerror = () => this.onStatusChange('error');
    this._ws.onmessage = (e: MessageEvent) => {
      try {
        const frame: WsInboundFrame = JSON.parse(e.data);
        this.onFrame(frame);
      } catch { /* ignore malformed */ }
    };
  }

  send(msg: WsOutboundMessage): void {
    if (this._ws?.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify(msg));
    }
  }

  disconnect(): void {
    this._ws?.close();
    this._ws = null;
  }
}
```

---

## 11. Maven Module Integration

```xml
<!-- arcclaw-ui/pom.xml (skeleton) -->
<build>
  <plugins>
    <plugin>
      <groupId>com.github.eirslett</groupId>
      <artifactId>frontend-maven-plugin</artifactId>
      <version>1.15.0</version>
      <configuration>
        <nodeVersion>v22.12.0</nodeVersion>
        <workingDirectory>${project.basedir}</workingDirectory>
      </configuration>
      <executions>
        <execution><id>install-node-npm</id><goals><goal>install-node-and-npm</goal></goals></execution>
        <execution><id>npm-install</id><goals><goal>npm</goal></goals><configuration><arguments>install</arguments></configuration></execution>
        <execution><id>vite-build</id><goals><goal>npm</goal></goals><configuration><arguments>run build</arguments></configuration></execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

`arcclaw-app/pom.xml` depends on `arcclaw-ui` and copies `dist/` into `META-INF/resources/` via the Maven Resources Plugin so Quarkus serves the built assets statically.

---

## 12. CORS Requirement (Backend Reminder)

The Vite dev server runs on `http://localhost:5173`. Add to `arcclaw-app/src/main/resources/application.yaml`:

```yaml
quarkus:
  http:
    cors:
      origins: "http://localhost:5173,http://localhost:8080"
      headers: "X-ArcClaw-API-Key,Content-Type,Accept"
      methods: "GET,POST,OPTIONS"
```

For the SSE fetch approach (Section 9), CORS must allow `X-ArcClaw-API-Key` in `Access-Control-Allow-Headers`.

---

## 13. Phase 79 Implementation Order

Implement in this sequence — each step is independently testable:

1. `arcclaw-ui` Maven module scaffold (pom.xml, package.json, vite.config.ts, tsconfig.json)
2. `src/api/types.ts` — all DTO interfaces
3. `src/api/client.ts` — ArcClient
4. `src/api/sse.ts` — SseManager
5. `src/api/websocket.ts` — WsManager
6. `src/styles/tokens.css`
7. `arc-connection-badge` (simplest component — no API calls)
8. `arc-onboarding` (API key flow — testable standalone)
9. `arc-nav` (static, depends only on route + sseStatus props)
10. `arc-agent-status` (pure render — no API calls, receives props)
11. `arc-hitl-panel` (REST calls — approve/reject)
12. `arc-skills-panel` (single GET on mount)
13. `arc-chat-interface` (WebSocket — most complex)
14. `arc-shell` (wires everything together — router + SSE + polling)
15. CORS config in `arcclaw-app`
16. Maven build integration + static asset serving

---

*Document version: Phase 79 / 2026-03-31*
*Backend target: Quarkus 3.27.2 / Phase 78*
*Frontend stack: Vite 6, Lit 3, TypeScript 5, Node 22*
