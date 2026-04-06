# PROMPT — PHASE 79: arcclaw-ui — Lit Frontend Module (Tier A)

Role: Senior Frontend Engineer + TypeScript Architect
Task: Phase 79 — Build the `arcclaw-ui` browser frontend as a standalone Maven module
using Vite 6, Lit 3, and TypeScript 5. This is the only source of browser UI for ArcClaw.
It replaces all previous references to HTMX, vanilla JS, or Qute-rendered HTML in the
browser layer. The Quarkus backend (Phases 77–78) serves only JSON + SSE + WebSocket.

**Prerequisites:** (I tested manually - working fine , proceed)
- Phase 78 must be complete and running (`./mvnw quarkus:dev` on port 8080).
- `/api/v1/agents`, `/api/v1/hitl`, `/api/v1/chat/stream`, `/ws/chat` must be reachable.

---

## Architecture Context
Refer @arcclaw-app/src/main/resources/application.yaml (CORS origins, API base path)
Refer @Lit-4.md (component specs and design rationale)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentRoleEnum.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AutonomyLevel.java

**Design principles (enforced throughout):**
- One SSE connection owned by `arc-shell`; all child components receive data via `@property()`.
- No `localStorage` for secrets. API key held in `@state()` on `arc-shell` (in-memory only).
- Shadow DOM on all components — no style leakage.
- Hash routing: `#/agents`, `#/hitl`, `#/skills`, `#/chat`, `#/onboarding`.
- Backend serves JSON only — frontend owns all rendering.
- No Redux, Zustand, or external state library. Lit `@state()` + Context API only.

---

## Module Structure

**New Maven module:** `arcclaw-ui`

```
arcclaw/
├── arcclaw-core/
├── arcclaw-app/         ← Quarkus backend, port 8080
├── arcclaw-ui/          ← NEW: Lit frontend, port 3000 (Vite dev server)
│   ├── pom.xml
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.ts
│       ├── types/
│       │   ├── agent.ts
│       │   ├── hitl.ts
│       │   └── chat.ts
│       ├── api/
│       │   ├── agents.ts
│       │   ├── hitl.ts
│       │   └── chat.ts
│       └── components/
│           ├── arc-shell.ts
│           ├── arc-agent-grid.ts
│           ├── arc-agent-card.ts
│           ├── arc-hitl-panel.ts
│           ├── arc-chat.ts
│           ├── arc-skill-manager.ts
│           ├── arc-provider-switcher.ts
│           └── arc-login.ts
```

---

## Maven Module Setup

**`arcclaw-ui/pom.xml`:**
```xml
<parent>
    <groupId>com.arcclaw</groupId>
    <artifactId>arcclaw-parent</artifactId>
    <version><!-- inherit --></version>
</parent>
<artifactId>arcclaw-ui</artifactId>
<packaging>pom</packaging>  <!-- No Java sources -->

<build>
    <plugins>
        <plugin>
            <groupId>com.github.eirslett</groupId>
            <artifactId>frontend-maven-plugin</artifactId>
            <version>1.15.0</version>
            <configuration>
                <nodeVersion>v22.11.0</nodeVersion>
                <npmVersion>10.9.0</npmVersion>
                <workingDirectory>${project.basedir}</workingDirectory>
            </configuration>
            <executions>
                <execution>
                    <id>install-node-npm</id>
                    <goals><goal>install-node-and-npm</goal></goals>
                </execution>
                <execution>
                    <id>npm-install</id>
                    <goals><goal>npm</goal></goals>
                    <configuration><arguments>install</arguments></configuration>
                </execution>
                <execution>
                    <id>npm-build</id>
                    <goals><goal>npm</goal></goals>
                    <phase>package</phase>
                    <configuration><arguments>run build</arguments></configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**Root `pom.xml`:** Add `arcclaw-ui` to `<modules>` after `arcclaw-app`.

---

## package.json

```json
{
  "name": "arcclaw-ui",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "lit": "^3.2.0"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "typescript": "^5.6.0",
    "vite": "^6.0.0"
  }
}
```

---

## vite.config.ts

```typescript
import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
  },
});
```

---

## index.html

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>ArcClaw</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: system-ui, sans-serif; background: #0d1117; color: #c9d1d9; height: 100vh; }
  </style>
</head>
<body>
  <arc-shell></arc-shell>
  <script type="module" src="/src/main.ts"></script>
</body>
</html>
```

---

## src/main.ts

```typescript
// Register all custom elements — importing them registers them with the browser.
import './components/arc-shell';
import './components/arc-agent-grid';
import './components/arc-agent-card';
import './components/arc-hitl-panel';
import './components/arc-chat';
import './components/arc-skill-manager';
import './components/arc-provider-switcher';
import './components/arc-login';
```

---

## src/types/agent.ts

```typescript
export type AgentStatus = 'idle' | 'active' | 'waiting_approval' | 'error';
export type AutonomyLevel = 'SUPERVISED' | 'SEMI_AUTONOMOUS' | 'AUTONOMOUS';

export interface AgentStatusDto {
  agentName: string;
  role: string;
  status: AgentStatus;
  currentTask: string | null;
  autonomyLevel: AutonomyLevel;
  tokenCount: number;
}

export interface AgentEvent {
  eventType: 'agent-state' | 'hitl-pending' | 'checklist-update';
  agentName: string | null;
  payload: string;  // JSON string — parse based on eventType
}
```

---

## src/types/hitl.ts

```typescript
export interface PendingApprovalDto {
  taskId: string;
  toolName: string;
  params: Record<string, unknown>;
  requestedAt: string;  // ISO-8601
  agentName: string;
}
```

---

## src/types/chat.ts

```typescript
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  timestamp: number;
}

export interface ChatRequest {
  input: string;
  sessionId: string | null;
}
```

---

## src/api/agents.ts

```typescript
export async function fetchAgents(apiKey: string): Promise<AgentStatusDto[]> {
  const res = await fetch('/api/v1/agents', {
    headers: { 'Authorization': `Bearer ${apiKey}` },
  });
  if (!res.ok) throw new Error(`agents fetch failed: ${res.status}`);
  return res.json();
}

export function openAgentEventSource(apiKey: string): EventSource {
  // EventSource does not support custom headers in the browser.
  // Pass API key as a query param: /api/v1/agents/events?token=<key>
  // The backend must accept ?token= as an alternative to Authorization header.
  // Document this requirement in a comment — backend adjustment is a follow-up.
  return new EventSource(`/api/v1/agents/events?token=${encodeURIComponent(apiKey)}`);
}
```

---

## src/api/hitl.ts

```typescript
import type { PendingApprovalDto } from '../types/hitl';

export async function fetchPendingApprovals(apiKey: string): Promise<PendingApprovalDto[]> {
  const res = await fetch('/api/v1/hitl', {
    headers: { 'Authorization': `Bearer ${apiKey}` },
  });
  if (!res.ok) throw new Error(`hitl fetch failed: ${res.status}`);
  return res.json();
}

export async function approveTask(taskId: string, apiKey: string): Promise<void> {
  const res = await fetch(`/api/v1/hitl/${taskId}/approve`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${apiKey}` },
  });
  if (!res.ok) throw new Error(`approve failed: ${res.status}`);
}

export async function rejectTask(taskId: string, reason: string, apiKey: string): Promise<void> {
  const res = await fetch(`/api/v1/hitl/${taskId}/reject`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'text/plain',
    },
    body: reason,
  });
  if (!res.ok) throw new Error(`reject failed: ${res.status}`);
}
```

---

## src/api/chat.ts

```typescript
import type { ChatRequest } from '../types/chat';

/**
 * Opens an SSE stream for a chat message.
 * Returns a ReadableStream<string> of token chunks.
 * The stream ends with a JSON chunk: {"type":"done","sessionId":"<id>"}
 */
export async function streamChat(
  req: ChatRequest,
  apiKey: string,
  onToken: (token: string) => void,
  onDone: (sessionId: string) => void,
  onError: (message: string) => void,
): Promise<void> {
  const res = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(req),
  });

  if (!res.ok || !res.body) {
    onError(`Stream failed: ${res.status}`);
    return;
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += value;

    // SSE lines start with "data: "
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';

    for (const line of lines) {
      if (!line.startsWith('data: ')) continue;
      const data = line.slice(6).trim();
      if (!data) continue;
      try {
        const parsed = JSON.parse(data);
        if (parsed.type === 'done') {
          onDone(parsed.sessionId);
        } else if (parsed.type === 'error') {
          onError(parsed.message);
        } else {
          onToken(data);  // raw token text
        }
      } catch {
        onToken(data);  // not JSON → raw token
      }
    }
  }
}
```

---

## src/components/arc-login.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';

/**
 * Login prompt shown when no API key is present in arc-shell.
 * Fires a 'login' CustomEvent with detail: { apiKey: string }.
 * API key is NEVER written to localStorage or sessionStorage.
 */
@customElement('arc-login')
export class ArcLogin extends LitElement {
  @state() private _key = '';

  static styles = css`
    :host {
      display: flex; align-items: center; justify-content: center;
      height: 100vh; background: #0d1117;
    }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 12px;
      padding: 2rem; width: 420px; display: flex; flex-direction: column; gap: 1rem;
    }
    h1 { color: #58a6ff; font-size: 1.4rem; margin: 0; }
    p { color: #8b949e; font-size: 0.875rem; line-height: 1.5; }
    input {
      background: #0d1117; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; padding: 0.5rem 0.75rem; font-size: 0.9rem; width: 100%;
    }
    button {
      background: #238636; border: none; border-radius: 6px; color: white;
      cursor: pointer; padding: 0.5rem 1rem; font-size: 0.9rem;
    }
    button:hover { background: #2ea043; }
  `;

  private _submit() {
    if (!this._key.trim()) return;
    this.dispatchEvent(new CustomEvent('login', { detail: { apiKey: this._key.trim() }, bubbles: true, composed: true }));
  }

  render() {
    return html`
      <div class="card">
        <h1>🦅 ArcClaw</h1>
        <p>Enter your ArcClaw API key to continue. The key is stored in memory only and
           cleared when you close the browser tab.</p>
        <input
          type="password"
          placeholder="arcclaw-key-..."
          .value="${this._key}"
          @input="${(e: InputEvent) => this._key = (e.target as HTMLInputElement).value}"
          @keydown="${(e: KeyboardEvent) => e.key === 'Enter' && this._submit()}"
        />
        <button @click="${this._submit}">Connect</button>
      </div>
    `;
  }
}
```

---

## src/components/arc-shell.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { AgentStatusDto, AgentEvent } from '../types/agent';
import type { PendingApprovalDto } from '../types/hitl';
import { fetchAgents, openAgentEventSource } from '../api/agents';
import { fetchPendingApprovals } from '../api/hitl';

type Route = 'agents' | 'hitl' | 'chat' | 'skills' | 'onboarding';

/**
 * Top-level application shell.
 * Owns: API key (in-memory), single SSE connection, routing state,
 * agent list, and pending HITL approvals.
 * Fans data down to child components via @property() bindings.
 */
@customElement('arc-shell')
export class ArcShell extends LitElement {
  @state() private _apiKey: string | null = null;
  @state() private _route: Route = 'agents';
  @state() private _agents: AgentStatusDto[] = [];
  @state() private _pendingApprovals: PendingApprovalDto[] = [];
  @state() private _sseError = false;
  private _sse: EventSource | null = null;

  static styles = css`
    :host { display: flex; height: 100vh; overflow: hidden; }
    nav {
      width: 200px; background: #161b22; border-right: 1px solid #30363d;
      display: flex; flex-direction: column; padding: 1rem 0;
    }
    nav a {
      color: #8b949e; padding: 0.6rem 1.25rem; text-decoration: none;
      font-size: 0.9rem; cursor: pointer; border-left: 3px solid transparent;
      display: flex; align-items: center; gap: 0.5rem;
    }
    nav a.active { color: #58a6ff; border-left-color: #58a6ff; background: #0d1117; }
    nav a:hover:not(.active) { background: #1c2128; color: #c9d1d9; }
    .badge {
      background: #da3633; color: white; border-radius: 999px;
      font-size: 0.7rem; padding: 0 6px; min-width: 18px; text-align: center;
    }
    main { flex: 1; overflow-y: auto; padding: 1.5rem; }
    .logo { color: #58a6ff; font-size: 1.1rem; font-weight: 600; padding: 0.5rem 1.25rem 1rem; }
    .sse-error {
      background: #da3633; color: white; font-size: 0.8rem;
      padding: 0.3rem 1rem; text-align: center;
    }
  `;

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this._onHashChange.bind(this));
    this._onHashChange();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._sse?.close();
    window.removeEventListener('hashchange', this._onHashChange.bind(this));
  }

  private _onHashChange() {
    const hash = window.location.hash.replace('#/', '') as Route;
    const validRoutes: Route[] = ['agents', 'hitl', 'chat', 'skills', 'onboarding'];
    this._route = validRoutes.includes(hash) ? hash : 'agents';
  }

  private async _onLogin(e: CustomEvent<{ apiKey: string }>) {
    this._apiKey = e.detail.apiKey;
    await this._loadInitialData();
    this._connectSSE();
  }

  private async _loadInitialData() {
    if (!this._apiKey) return;
    try {
      [this._agents, this._pendingApprovals] = await Promise.all([
        fetchAgents(this._apiKey),
        fetchPendingApprovals(this._apiKey),
      ]);
    } catch (err) {
      console.error('Initial data load failed:', err);
    }
  }

  private _connectSSE() {
    if (!this._apiKey) return;
    this._sse = openAgentEventSource(this._apiKey);
    this._sse.addEventListener('agent-state', (e: MessageEvent) => {
      this._handleAgentEvent(JSON.parse(e.data) as AgentEvent);
    });
    this._sse.addEventListener('hitl-pending', async () => {
      // Re-fetch full list on any HITL change — simple and reliable.
      if (this._apiKey) {
        this._pendingApprovals = await fetchPendingApprovals(this._apiKey);
      }
    });
    this._sse.onerror = () => { this._sseError = true; };
  }

  private _handleAgentEvent(event: AgentEvent) {
    this._sseError = false;
    if (event.eventType === 'agent-state' && event.agentName) {
      const payload = JSON.parse(event.payload);
      this._agents = this._agents.map(a =>
        a.agentName === event.agentName ? { ...a, ...payload } : a
      );
    }
  }

  private _navigate(route: Route) {
    window.location.hash = `#/${route}`;
    this._route = route;
  }

  render() {
    if (!this._apiKey) {
      return html`<arc-login @login="${this._onLogin}"></arc-login>`;
    }

    const hitlCount = this._pendingApprovals.length;

    return html`
      <nav>
        <div class="logo">🦅 ArcClaw
        Continuing the updated prompts document from exactly where it was cut off — inside `arc-shell.ts` render method: 

***

````markdown
        </div>
        ${this._sseError ? html`<div class="sse-error">⚠ Live updates disconnected</div>` : ''}
        <a class="${this._route === 'agents' ? 'active' : ''}" @click="${() => this._navigate('agents')}">
          🤖 Agents
        </a>
        <a class="${this._route === 'hitl' ? 'active' : ''}" @click="${() => this._navigate('hitl')}">
          ✋ Approvals
          ${hitlCount > 0 ? html`<span class="badge">${hitlCount}</span>` : ''}
        </a>
        <a class="${this._route === 'chat' ? 'active' : ''}" @click="${() => this._navigate('chat')}">
          💬 Chat
        </a>
        <a class="${this._route === 'skills' ? 'active' : ''}" @click="${() => this._navigate('skills')}">
          🛠 Skills
        </a>
        <a class="${this._route === 'onboarding' ? 'active' : ''}" @click="${() => this._navigate('onboarding')}">
          ⚙ Setup
        </a>
      </nav>
      <main>
        ${this._route === 'agents'    ? html`<arc-agent-grid .agents="${this._agents}" .apiKey="${this._apiKey}"></arc-agent-grid>` : ''}
        ${this._route === 'hitl'      ? html`<arc-hitl-panel .approvals="${this._pendingApprovals}" .apiKey="${this._apiKey}" @approval-action="${this._loadInitialData.bind(this)}"></arc-hitl-panel>` : ''}
        ${this._route === 'chat'      ? html`<arc-chat .apiKey="${this._apiKey}"></arc-chat>` : ''}
        ${this._route === 'skills'    ? html`<arc-skill-manager .apiKey="${this._apiKey}"></arc-skill-manager>` : ''}
        ${this._route === 'onboarding'? html`<arc-provider-switcher .apiKey="${this._apiKey}"></arc-provider-switcher>` : ''}
      </main>
    `;
  }
}
```

***

## src/components/arc-agent-card.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { AgentStatusDto, AgentStatus, AutonomyLevel } from '../types/agent';

/**
 * Displays one agent persona card.
 * Maps directly to AgentRoleEnum + AutonomyLevel in the backend.
 * Visual affordances:
 *   - SUPERVISED  → amber border, lock icon badge
 *   - SEMI_AUTONOMOUS → blue border
 *   - AUTONOMOUS  → green border, lightning badge
 */
@customElement('arc-agent-card')
export class ArcAgentCard extends LitElement {
  @property({ type: Object }) agent!: AgentStatusDto;

  static styles = css`
    :host { display: block; }
    .card {
      background: #161b22; border-radius: 10px; padding: 1rem;
      border-left: 4px solid var(--autonomy-color, #30363d);
      display: flex; flex-direction: column; gap: 0.5rem;
    }
    .header { display: flex; align-items: center; gap: 0.5rem; }
    .name { font-weight: 600; font-size: 1rem; color: #c9d1d9; }
    .role { font-size: 0.75rem; color: #8b949e; text-transform: uppercase; letter-spacing: 0.05em; }
    .status-badge {
      font-size: 0.7rem; padding: 2px 8px; border-radius: 999px;
      font-weight: 600; text-transform: uppercase;
    }
    .status-idle       { background: #21262d; color: #8b949e; }
    .status-active     { background: #1f6feb33; color: #58a6ff; }
    .status-waiting    { background: #9e6a0333; color: #d29922; }
    .status-error      { background: #da363333; color: #f85149; }
    .task { font-size: 0.8rem; color: #8b949e; font-style: italic; min-height: 1.2em; }
    .tokens { font-size: 0.75rem; color: #484f58; }
    .autonomy-icon { font-size: 0.9rem; }
  `;

  private _autonomyColor(level: AutonomyLevel): string {
    return { SUPERVISED: '#d29922', SEMI_AUTONOMOUS: '#58a6ff', AUTONOMOUS: '#3fb950' }[level] ?? '#30363d';
  }

  private _autonomyIcon(level: AutonomyLevel): string {
    return { SUPERVISED: '🔒', SEMI_AUTONOMOUS: '🔵', AUTONOMOUS: '⚡' }[level] ?? '';
  }

  private _statusClass(status: AgentStatus): string {
    return { idle: 'status-idle', active: 'status-active', waiting_approval: 'status-waiting', error: 'status-error' }[status] ?? 'status-idle';
  }

  render() {
    const { agentName, role, status, currentTask, autonomyLevel, tokenCount } = this.agent;
    return html`
      <div class="card" style="--autonomy-color: ${this._autonomyColor(autonomyLevel)}">
        <div class="header">
          <span class="name">${agentName}</span>
          <span class="autonomy-icon">${this._autonomyIcon(autonomyLevel)}</span>
          <span class="status-badge ${this._statusClass(status)}">${status.replace('_', ' ')}</span>
        </div>
        <div class="role">${role}</div>
        <div class="task">${currentTask ?? 'No active task'}</div>
        <div class="tokens">~${tokenCount.toLocaleString()} tokens</div>
      </div>
    `;
  }
}
```

***

## src/components/arc-agent-grid.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { AgentStatusDto } from '../types/agent';
import './arc-agent-card';

@customElement('arc-agent-grid')
export class ArcAgentGrid extends LitElement {
  @property({ type: Array }) agents: AgentStatusDto[] = [];
  @property({ type: String }) apiKey = '';

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 1rem;
    }
    .empty { color: #8b949e; font-style: italic; }
  `;

  render() {
    return html`
      <h2>Agent Status</h2>
      ${this.agents.length === 0
        ? html`<p class="empty">No agents running. Start a session via Chat.</p>`
        : html`
          <div class="grid">
            ${this.agents.map(a => html`<arc-agent-card .agent="${a}"></arc-agent-card>`)}
          </div>`
      }
    `;
  }
}
```

***

## src/components/arc-hitl-panel.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { PendingApprovalDto } from '../types/hitl';
import { approveTask, rejectTask } from '../api/hitl';

/**
 * Human-in-the-loop approval panel.
 * This is the most critical component in ArcClaw UI — no equivalent in JavaClaw.
 * Displayed as a persistent view under #/hitl route.
 * Fires 'approval-action' event upward after any approve/reject so arc-shell
 * can re-fetch the pending list.
 */
@customElement('arc-hitl-panel')
export class ArcHitlPanel extends LitElement {
  @property({ type: Array }) approvals: PendingApprovalDto[] = [];
  @property({ type: String }) apiKey = '';
  @state() private _processing = new Set<string>();
  @state() private _rejectReason: Record<string, string> = {};

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .empty { color: #8b949e; font-style: italic; }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 10px;
      padding: 1rem; margin-bottom: 1rem;
    }
    .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
    .tool-name { color: #f0883e; font-weight: 600; font-size: 0.95rem; }
    .agent-name { color: #8b949e; font-size: 0.8rem; }
    .requested-at { color: #484f58; font-size: 0.75rem; }
    .params {
      background: #0d1117; border-radius: 6px; padding: 0.75rem;
      font-family: monospace; font-size: 0.8rem; color: #c9d1d9;
      white-space: pre-wrap; margin-bottom: 0.75rem; max-height: 200px; overflow-y: auto;
    }
    .actions { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
    .btn-approve {
      background: #238636; border: none; border-radius: 6px; color: white;
      cursor: pointer; padding: 0.4rem 1rem; font-size: 0.85rem;
    }
    .btn-approve:hover { background: #2ea043; }
    .btn-reject {
      background: transparent; border: 1px solid #da3633; border-radius: 6px;
      color: #f85149; cursor: pointer; padding: 0.4rem 1rem; font-size: 0.85rem;
    }
    .btn-reject:hover { background: #da363322; }
    .reason-input {
      flex: 1; min-width: 180px;
      background: #0d1117; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; padding: 0.4rem 0.6rem; font-size: 0.8rem;
    }
    .spinner { color: #8b949e; font-size: 0.85rem; }
  `;

  private async _approve(taskId: string) {
    this._processing = new Set(this._processing).add(taskId);
    try {
      await approveTask(taskId, this.apiKey);
      this.dispatchEvent(new CustomEvent('approval-action', { bubbles: true, composed: true }));
    } catch (e) {
      console.error('approve failed', e);
    } finally {
      const next = new Set(this._processing);
      next.delete(taskId);
      this._processing = next;
    }
  }

  private async _reject(taskId: string) {
    const reason = this._rejectReason[taskId] ?? '';
    this._processing = new Set(this._processing).add(taskId);
    try {
      await rejectTask(taskId, reason, this.apiKey);
      this.dispatchEvent(new CustomEvent('approval-action', { bubbles: true, composed: true }));
    } catch (e) {
      console.error('reject failed', e);
    } finally {
      const next = new Set(this._processing);
      next.delete(taskId);
      this._processing = next;
    }
  }

  render() {
    return html`
      <h2>Pending Approvals (${this.approvals.length})</h2>
      ${this.approvals.length === 0
        ? html`<p class="empty">No approvals waiting. Agents are running freely.</p>`
        : this.approvals.map(a => html`
          <div class="card">
            <div class="card-header">
              <span class="tool-name">🔧 ${a.toolName}</span>
              <span class="agent-name">by ${a.agentName}</span>
              <span class="requested-at">${new Date(a.requestedAt).toLocaleTimeString()}</span>
            </div>
            <pre class="params">${JSON.stringify(a.params, null, 2)}</pre>
            <div class="actions">
              ${this._processing.has(a.taskId)
                ? html`<span class="spinner">Processing…</span>`
                : html`
                  <input
                    class="reason-input"
                    type="text"
                    placeholder="Rejection reason (optional)"
                    .value="${this._rejectReason[a.taskId] ?? ''}"
                    @input="${(e: InputEvent) => {
                      this._rejectReason = { ...this._rejectReason, [a.taskId]: (e.target as HTMLInputElement).value };
                    }}"
                  />
                  <button class="btn-approve" @click="${() => this._approve(a.taskId)}">✓ Approve</button>
                  <button class="btn-reject"  @click="${() => this._reject(a.taskId)}">✗ Reject</button>
                `}
            </div>
          </div>
        `)
      }
    `;
  }
}
```

***

## src/components/arc-chat.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { ChatMessage } from '../types/chat';
import { streamChat } from '../api/chat';

/**
 * Real-time chat with the ArcClaw agent cluster.
 * Uses SSE token streaming via POST /api/v1/chat/stream.
 * Unlike JavaClaw's WebSocket approach, SSE is simpler and aligns with
 * the existing Multi<String> reactive streams in Quarkus.
 */
@customElement('arc-chat')
export class ArcChat extends LitElement {
  @property({ type: String }) apiKey = '';
  @state() private _messages: ChatMessage[] = [];
  @state() private _input = '';
  @state() private _streaming = false;
  @state() private _sessionId: string | null = null;
  @state() private _streamingText = '';

  static styles = css`
    :host { display: flex; flex-direction: column; height: calc(100vh - 6rem); }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; flex-shrink: 0; }
    .messages {
      flex: 1; overflow-y: auto; display: flex; flex-direction: column;
      gap: 0.75rem; padding-bottom: 1rem;
    }
    .msg { max-width: 80%; padding: 0.6rem 0.9rem; border-radius: 10px; font-size: 0.9rem; line-height: 1.5; }
    .msg-user {
      align-self: flex-end; background: #1f6feb; color: white;
      border-bottom-right-radius: 2px;
    }
    .msg-assistant {
      align-self: flex-start; background: #161b22; color: #c9d1d9;
      border: 1px solid #30363d; border-bottom-left-radius: 2px;
    }
    .msg-streaming {
      align-self: flex-start; background: #161b22; color: #c9d1d9;
      border: 1px solid #58a6ff44; border-bottom-left-radius: 2px;
      font-style: italic;
    }
    .typing { display: flex; gap: 4px; padding: 0.5rem; }
    .typing span {
      width: 8px; height: 8px; background: #58a6ff; border-radius: 50%;
      animation: bounce 1s infinite;
    }
    .typing span:nth-child(2) { animation-delay: 0.2s; }
    .typing span:nth-child(3) { animation-delay: 0.4s; }
    @keyframes bounce { 0%, 60%, 100% { transform: translateY(0); } 30% { transform: translateY(-6px); } }
    .input-row {
      display: flex; gap: 0.5rem; flex-shrink: 0;
      border-top: 1px solid #30363d; padding-top: 1rem;
    }
    textarea {
      flex: 1; background: #161b22; border: 1px solid #30363d; border-radius: 8px;
      color: #c9d1d9; padding: 0.5rem 0.75rem; font-size: 0.9rem; resize: none;
      font-family: inherit; min-height: 44px; max-height: 120px;
    }
    button {
      background: #238636; border: none; border-radius: 8px; color: white;
      cursor: pointer; padding: 0.5rem 1.25rem; font-size: 0.9rem; align-self: flex-end;
    }
    button:disabled { background: #21262d; color: #484f58; cursor: not-allowed; }
    pre, code { font-family: 'Fira Code', monospace; font-size: 0.82rem; }
    pre { background: #0d1117; padding: 0.75rem; border-radius: 6px; overflow-x: auto; }
  `;

  private async _send() {
    const text = this._input.trim();
    if (!text || this._streaming) return;

    this._messages = [...this._messages, {
      id: crypto.randomUUID(), role: 'user', text, timestamp: Date.now(),
    }];
    this._input = '';
    this._streaming = true;
    this._streamingText = '';

    await streamChat(
      { input: text, sessionId: this._sessionId },
      this.apiKey,
      (token) => { this._streamingText += token; },
      (sessionId) => {
        this._sessionId = sessionId;
        this._messages = [...this._messages, {
          id: crypto.randomUUID(), role: 'assistant', text: this._streamingText, timestamp: Date.now(),
        }];
        this._streamingText = '';
        this._streaming = false;
        this._scrollBottom();
      },
      (errMsg) => {
        this._messages = [...this._messages, {
          id: crypto.randomUUID(), role: 'assistant',
          text: `⚠ Error: ${errMsg}`, timestamp: Date.now(),
        }];
        this._streaming = false;
        this._streamingText = '';
      },
    );
  }

  private _scrollBottom() {
    this.updateComplete.then(() => {
      const el = this.shadowRoot?.querySelector('.messages');
      if (el) el.scrollTop = el.scrollHeight;
    });
  }

  render() {
    return html`
      <h2>Chat</h2>
      <div class="messages">
        ${this._messages.map(m => html`
          <div class="msg ${m.role === 'user' ? 'msg-user' : 'msg-assistant'}">
            ${m.text}
          </div>
        `)}
        ${this._streaming && this._streamingText
          ? html`<div class="msg msg-streaming">${this._streamingText}▌</div>`
          : ''}
        ${this._streaming && !this._streamingText
          ? html`<div class="msg msg-assistant"><div class="typing"><span></span><span></span><span></span></div></div>`
          : ''}
      </div>
      <div class="input-row">
        <textarea
          placeholder="Ask ArcClaw anything…"
          .value="${this._input}"
          @input="${(e: InputEvent) => this._input = (e.target as HTMLTextAreaElement).value}"
          @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._send(); } }}"
        ></textarea>
        <button ?disabled="${this._streaming || !this._input.trim()}" @click="${this._send}">
          ${this._streaming ? '…' : 'Send'}
        </button>
      </div>
    `;
  }
}
```

***

## src/components/arc-skill-manager.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface Skill {
  name: string;
  description: string;
  enabled: boolean;
}

/**
 * Read-only skill viewer backed by GET /api/v1/skills.
 * Phase 79 scope: display only. Enable/disable is Phase 80+.
 */
@customElement('arc-skill-manager')
export class ArcSkillManager extends LitElement {
  @property({ type: String }) apiKey = '';
  @state() private _skills: Skill[] = [];
  @state() private _loading = true;
  @state() private _error = '';

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .list { display: flex; flex-direction: column; gap: 0.5rem; }
    .skill {
      background: #161b22; border: 1px solid #30363d; border-radius: 8px;
      padding: 0.75rem 1rem; display: flex; align-items: center; gap: 1rem;
    }
    .skill-name { color: #c9d1d9; font-weight: 500; flex: 1; }
    .skill-desc { color: #8b949e; font-size: 0.8rem; flex: 2; }
    .pill { font-size: 0.7rem; padding: 2px 8px; border-radius: 999px; }
    .pill-on  { background: #1f6feb33; color: #58a6ff; }
    .pill-off { background: #21262d; color: #8b949e; }
    .error { color: #f85149; font-size: 0.875rem; }
    .loading { color: #8b949e; font-style: italic; }
  `;

  async connectedCallback() {
    super.connectedCallback();
    await this._load();
  }

  private async _load() {
    this._loading = true;
    this._error = '';
    try {
      const res = await fetch('/api/v1/skills', {
        headers: { 'Authorization': `Bearer ${this.apiKey}` },
      });
      if (!res.ok) throw new Error(`${res.status}`);
      this._skills = await res.json();
    } catch (e: unknown) {
      this._error = `Failed to load skills: ${e instanceof Error ? e.message : e}`;
    } finally {
      this._loading = false;
    }
  }

  render() {
    if (this._loading) return html`<p class="loading">Loading skills…</p>`;
    if (this._error)   return html`<p class="error">${this._error}</p>`;
    return html`
      <h2>Skills (${this._skills.length})</h2>
      <div class="list">
        ${this._skills.map(s => html`
          <div class="skill">
            <span class="skill-name">${s.name}</span>
            <span class="skill-desc">${s.description}</span>
            <span class="pill ${s.enabled ? 'pill-on' : 'pill-off'}">${s.enabled ? 'enabled' : 'disabled'}</span>
          </div>
        `)}
      </div>
    `;
  }
}
```

***

## src/components/arc-provider-switcher.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface ProviderStatus {
  providerId: string;
  modelName: string;
  active: boolean;
}

/**
 * Shows active LLM provider and model. Links to /onboarding for reconfiguration.
 * Phase 79 scope: display + navigate to onboarding. Switching live is Phase 80.
 */
@customElement('arc-provider-switcher')
export class ArcProviderSwitcher extends LitElement {
  @property({ type: String }) apiKey = '';
  @state() private _provider: ProviderStatus | null = null;

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 10px;
      padding: 1.25rem; display: flex; flex-direction: column; gap: 0.75rem;
      max-width: 420px;
    }
    .provider-id { color: #58a6ff; font-weight: 600; font-size: 1rem; }
    .model-name  { color: #c9d1d9; font-size: 0.875rem; }
    .label       { color: #8b949e; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.05em; }
    .btn {
      background: #21262d; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; cursor: pointer; padding: 0.4rem 1rem; font-size: 0.85rem;
      text-align: center;
    }
    .btn:hover { background: #1c2128; }
    a { color: #58a6ff; text-decoration: none; }
  `;

  async connectedCallback() {
    super.connectedCallback();
    try {
      const res = await fetch('/api/v1/provider/active', {
        headers: { 'Authorization': `Bearer ${this.apiKey}` },
      });
      if (res.ok) this._provider = await res.json();
    } catch { /* optional endpoint — fail silently */ }
  }

  render() {
    return html`
      <h2>LLM Provider</h2>
      <div class="card">
        ${this._provider ? html`
          <div>
            <div class="label">Provider</div>
            <div class="provider-id">${this._provider.providerId}</div>
          </div>
          <div>
            <div class="label">Model</div>
            <div class="model-name">${this._provider.modelName}</div>
          </div>
        ` : html`<p style="color:#8b949e">Provider info unavailable.</p>`}
        <a href="#/onboarding">
          <div class="btn">⚙ Reconfigure via Setup Wizard →</div>
        </a>
      </div>
    `;
  }
}
```

***

## Phase 79 Tests

All tests live in `arcclaw-ui/src/__tests__/` and use Vitest + `@web/test-runner` or
`@open-wc/testing`. Add to `package.json`:

```json
"devDependencies": {
  "vitest": "^2.0.0",
  "@open-wc/testing": "^4.0.0",
  "@web/test-runner": "^0.19.0"
},
"scripts": {
  "test": "vitest run"
}
```

**`arc-agent-card.test.ts`:**
- Render with `status='idle'` → contains class `status-idle`.
- Render with `status='waiting_approval'` → contains class `status-waiting` and amber border color.
- Render with `autonomyLevel='AUTONOMOUS'` → contains ⚡ icon.
- Render with `autonomyLevel='SUPERVISED'` → contains 🔒 icon.

**`arc-hitl-panel.test.ts`:**
- Empty approvals array → renders "No approvals waiting" text.
- One approval → renders tool name, agent name, params JSON.
- Click Approve → `approveTask` API called with correct taskId.
- Click Reject with reason → `rejectTask` API called with reason string.
- Click Reject with blank reason → `rejectTask` still called (backend defaults the message).
- `approval-action` CustomEvent is fired after approve.

**`arc-login.test.ts`:**
- Renders password input and Connect button.
- Enter key in input → `login` CustomEvent fired with `apiKey` detail.
- Empty key → `login` NOT fired.

**`arc-chat.test.ts`:**
- Initial render → no messages, typing area visible.
- Enter with blank input → `streamChat` NOT called.
- Shift+Enter → does NOT submit (adds newline only).

***

## Phase 79 Delivery Checklist
- [ ] `arcclaw-ui` module added to root `pom.xml`
- [ ] `frontend-maven-plugin` configured — `mvn package` runs `npm install && npm run build`
- [ ] All 7 components implemented: `arc-shell`, `arc-agent-card`, `arc-agent-grid`, `arc-hitl-panel`, `arc-chat`, `arc-skill-manager`, `arc-provider-switcher`
- [ ] `arc-login` implemented — no localStorage usage anywhere
- [ ] `src/api/*.ts` wrappers typed against DTO records from Phase 78
- [ ] Vite proxy configured: `/api` → `http://localhost:8080`, `/ws` → `ws://localhost:8080`
- [ ] Hash routing working: `#/agents`, `#/hitl`, `#/chat`, `#/skills`, `#/onboarding`
- [ ] Single SSE connection owned by `arc-shell`, fanned out to children via `@property()`
- [ ] All component tests green: `npm test`
- [ ] `npm run build` produces `dist/` without errors
- [ ] `mvn clean package -pl arcclaw-ui` succeeds