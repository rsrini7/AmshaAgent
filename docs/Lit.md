Picking up right after the Quarkus SSE integration snippet — now let's get into the concrete backend additions ArcClaw needs, the module structure, and the full Lit component specification built around your actual codebase.

***

## New REST Endpoints to Add

ArcClaw already has `/api/v1/skills` and `/api/v1/mcp/*`. The UI layer needs these additions in `arcclaw-app`:

```java
// New: AgentStatusResource.java
@Path("agents") @RunOnVirtualThread
public class AgentStatusResource {
    
    @GET                                    // list all agents + current state
    @Produces(MediaType.APPLICATION_JSON)
    public List<AgentStatusDto> listAgents() { ... }

    @GET @Path("events")                    // SSE stream: live state changes
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<AgentEvent> agentStream() { ... }
}

// New: HitlResource.java  
@Path("hitl") @RunOnVirtualThread
public class HitlResource {
    
    @GET                                    // pending approvals waiting for human
    public List<PendingApprovalDto> listPending() { ... }

    @POST @Path("{taskId}/approve")
    public Response approve(@PathParam("taskId") String taskId) { ... }

    @POST @Path("{taskId}/reject")
    public Response reject(@PathParam("taskId") String taskId, String reason) { ... }
}

// New: ChatStreamResource.java
@Path("chat")
public class ChatStreamResource {
    
    @POST @Path("stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)       // token-by-token streaming
    public Multi<String> streamChat(ChatRequest req) { ... }
}
```

All three fit naturally on top of your existing `WorkspaceContext.run()` + `ScopedValue` infrastructure — the session binding pattern is already in place from your Telegram channel.

***

## Module Structure: `arcclaw-ui`

Don't embed the frontend into `arcclaw-app`'s `META-INF/resources`. The CORS config in `application.yaml` already points to `localhost:3000`, which confirms the intent for a **separate module**. Add it as a sibling Maven module:

```
arcclaw/
├── arcclaw-core/
├── arcclaw-app/          ← Quarkus backend, port 8080
├── arcclaw-ui/           ← NEW: Lit frontend, port 3000
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.ts
│       ├── components/
│       │   ├── arc-shell.ts
│       │   ├── arc-agent-grid.ts
│       │   ├── arc-agent-card.ts
│       │   ├── arc-hitl-panel.ts
│       │   ├── arc-chat.ts
│       │   ├── arc-skill-manager.ts
│       │   └── arc-provider-switcher.ts
│       └── api/
│           ├── agents.ts
│           ├── hitl.ts
│           └── chat.ts
```

The `arcclaw-ui` module is pure Vite + Lit + TypeScript — no Java, no Maven plugins for the frontend beyond a `frontend-maven-plugin` exec if you want a single `mvn package` to produce everything.

***

## Lit Component Specs (Mapped to ArcClaw Domain)

Here's the spec for each component tied directly to your agent personas and existing backend:

### `arc-agent-card` — One per agent persona
```typescript
@customElement('arc-agent-card')
export class ArcAgentCard extends LitElement {
  @property() agentName: string;    // 'Vishnu', 'Saraswati', etc.
  @property() role: string;         // 'coordinator', 'researcher'...
  @property() status: 'idle' | 'active' | 'waiting_approval' | 'error';
  @property() currentTask?: string;
  @property() autonomyLevel: 'SUPERVISED' | 'SEMI_AUTONOMOUS' | 'AUTONOMOUS';
  @property() tokenCount: number;

  // Shadow DOM: no style leakage between agent cards
  static styles = css`...`;
}
```

This maps to your `AgentRoleEnum` + `AutonomyLevel` enums directly. The `waiting_approval` status ties into your HITL pre-executable tools config (`arcclaw.hitl.pre-executable-tools`).

### `arc-hitl-panel` — Human-in-the-loop approvals
```typescript
@customElement('arc-hitl-panel')
export class ArcHitlPanel extends LitElement {
  @state() pendingApprovals: PendingApprovalDto[] = [];
  
  // Polls GET /api/v1/hitl on mount, refreshes via SSE event
  // approve() → POST /api/v1/hitl/{id}/approve
  // reject(reason) → POST /api/v1/hitl/{id}/reject
}
```

This is the **most critical component** in ArcClaw's UI and has no equivalent in JavaClaw — their model has no approval workflows.

### `arc-chat` — Streaming LLM responses
```typescript
@customElement('arc-chat')
export class ArcChat extends LitElement {
  @state() messages: ChatMessage[] = [];
  @state() streaming = false;
  private _reader?: ReadableStreamDefaultReader;

  async _sendMessage(text: string) {
    const res = await fetch('/api/v1/chat/stream', {
      method: 'POST', headers: { 'Authorization': `Bearer ${apiKey}` },
      body: JSON.stringify({ input: text, sessionId: this.sessionId })
    });
    // Consume SSE token-by-token and append to the last message
    this._reader = res.body!.getReader();
    // ... streaming render loop
  }
}
```

Unlike JavaClaw's WebSocket chat (which requires a persistent connection), using SSE here for streaming tokens is simpler and aligns with your existing `Multi<String>` reactive streams in Quarkus.

### `arc-shell` — Top-level orchestrator
```typescript
@customElement('arc-shell')
export class ArcShell extends LitElement {
  private _sse?: EventSource;

  connectedCallback() {
    super.connectedCallback();
    // Single SSE connection for the whole app — agent events, HITL triggers
    this._sse = new EventSource('/api/v1/agents/events', {
      headers: { 'Authorization': `Bearer ${this._apiKey}` }
    });
    this._sse.addEventListener('agent-state', e => this._handleAgentUpdate(e));
    this._sse.addEventListener('hitl-pending', e => this._handleHitl(e));
  }
}
```

One SSE connection at the shell level — child components receive data via `@property()` bindings, not each maintaining their own connections. This is a critical design difference from JavaClaw where each htmx element polls independently.

***

## Key Divergences from JavaClaw's Approach (Continued)

Building on the previous table, here are the **implementation-level** divergences to enforce in `arcclaw-ui`:

- **No server-rendered HTML** — ArcClaw's Quarkus backend serves only JSON + SSE. The UI is a full SPA with hash routing (`#/agents`, `#/hitl`, `#/skills`, `#/chat`)
- **One SSE pipe, fan-out via properties** — JavaClaw polls multiple htmx fragments; ArcClaw fans one SSE stream down from `arc-shell` to all child components via Lit's reactive property binding
- **Approval dialog is a first-class component** — Not an afterthought `<div>`. `arc-hitl-panel` is a persistent sidebar slot in the shell layout, with a badge count visible at all times
- **Agent cards use `autonomyLevel` as a visual affordance** — A `SUPERVISED` Brahma card should look visually different from an `AUTONOMOUS` one (badge variant, border treatment) — this reflects your actual config model
- **API key passed via in-memory JS variable** — Never `localStorage` (your CORS + sandboxing posture), initialized from a login prompt on first load and held in a `@state()` on `arc-shell`

***

## Recommended Tech Stack for `arcclaw-ui`

| Concern | Choice | Reason |
|---|---|---|
| Build | **Vite 6** | Zero-config, ESM-first, instant HMR |
| Language | **TypeScript 5.x** | Your agents have rich type hierarchies |
| Components | **Lit 3.x** | ~5KB, standards-based, Shadow DOM |
| CSS | **Plain CSS vars** (Nexus tokens) | No Tailwind overhead in web components |
| Icons | **Phosphor Icons (ESM)** | Tree-shakeable, Lit-friendly |
| State | **Lit `@state()` + Context API** | No Redux/Zustand needed at this scale |
| API layer | `fetch` + typed `async/await` wrappers | Thin wrapper in `src/api/*.ts` |

Your backend already handles all the heavy state in SQLite + WorkspaceScope. The frontend is intentionally thin — display, user input, and approval actions only.