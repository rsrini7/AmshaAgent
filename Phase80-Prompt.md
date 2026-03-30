# PROMPT — PHASE 80: arcclaw-onboarding — Guided Setup Wizard (Tier A)

Role: Senior Java Architect & Senior Java Developer + Frontend TypeScript Engineer
Task: Phase 80 — Implement the guided first-run onboarding wizard for ArcClaw as two
coordinated deliverables:
  (A) New Maven module `arcclaw-onboarding` — pure JSON REST API (no Qute, no HTMX).
  (B) New Lit component `arc-onboarding.ts` added to `arcclaw-ui` (Phase 79 must be done).
All secrets collected by the wizard go through `SecretVault` — never plain YAML or .env.
This is a strict improvement over JavaClaw's wizard which writes plaintext to application.yaml.

**Prerequisites:** Phase 78 (backend), Phase 79 (`arcclaw-ui` scaffold) must be complete.

***

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§8 Sandbox and security rules — vault ownership)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/security/SecretVault.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/security/VaultConstants.java
Refer @arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelFactory.java
Refer @arcclaw-app/src/main/java/com/arcclaw/app/mcp/McpConnectResource.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/skill/SkillRegistry.java

***

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every JAX-RS resource must have at least one `@QuarkusTest` integration test.
2. All secrets MUST be written to `SecretVault` — never to `application.yaml` or `.env`.
3. The wizard MUST be idempotent: re-running overwrites previous vault entries.
4. Wizard step state is stored server-side in a `@SessionScoped` CDI bean.
5. All POST endpoints return JSON (not HTML fragments — this is not HTMX).
6. All existing tests must remain green.
7. Test commands: `./mvnw clean test -pl arcclaw-onboarding`

***

## Part A — arcclaw-onboarding Maven Module

### Module Setup

**New module:** `arcclaw-onboarding`

`pom.xml`:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-core`, Quarkus RESTEasy Reactive, Quarkus CDI.
- Do NOT include Quarkus Qute — all rendering is done by the Lit frontend.
- Include `jandex-maven-plugin` and `beans.xml` with `bean-discovery-mode="annotated"`.

**Root `pom.xml`:** Add `arcclaw-onboarding` to `<modules>`.
**`arcclaw-app/pom.xml`:** Add `arcclaw-onboarding` as a dependency.
**Package root:** `com.arcclaw.onboarding`

***

### Component C1 — OnboardingStepProvider SPI

**File:** `com.arcclaw.onboarding.spi.OnboardingStepProvider`

```java
/**
 * SPI for contributing additional steps to the onboarding wizard.
 * All @ApplicationScoped implementations are discovered via CDI Instance<T>.
 * TelegramOnboardingProvider and BraveOnboardingProvider will implement this in future phases.
 */
public interface OnboardingStepProvider {
    String stepId();           // e.g. "telegram", "brave"
    int order();               // display order after core steps 1-5
    String title();            // human-readable step title for progress bar
    boolean isConfigured();    // true if this plugin is already fully set up
}
```

No implementations in Phase 80. The interface is the deliverable.

***

### Component C2 — OnboardingSession

**File:** `com.arcclaw.onboarding.OnboardingSession`

```java
@SessionScoped
public class OnboardingSession implements Serializable {

    public enum Step {
        WELCOME(1), PROVIDER(2), CREDENTIALS(3), SYSTEM_PROMPT(4), MCP(5), COMPLETE(6);
        public final int number;
        Step(int n) { this.number = n; }
        public Step next() {
            Step[] vals = values();
            int idx = ordinal() + 1;
            return idx < vals.length ? vals[idx] : COMPLETE;
        }
        public boolean isAfter(Step other) { return this.number > other.number; }
    }

    private Step currentStep = Step.WELCOME;
    private String selectedProvider;  // "openai" | "groq" | "ollama" | "anthropic" | "claude-code"
    private String selectedModel;
    private boolean credentialsValidated = false;

    // Getters + setters for all fields.

    /**
     * Returns true only if target.number <= currentStep.number + 1.
     * Prevents URL-hacking to skip steps.
     */
    public boolean canProceedTo(Step target) {
        return target.number <= currentStep.number + 1;
    }

    public void advance() { this.currentStep = currentStep.next(); }
}
```

***

### Component C3 — OnboardingResource (JSON REST controller)

**File:** `com.arcclaw.onboarding.OnboardingResource`

Base path: `/onboarding`
All responses: `application/json`
All POST bodies: `application/json`

```
GET  /onboarding/status          → { step, stepName, provider, model, credentialsOk }
POST /onboarding/step/1          → advance to step 2; returns { nextStep: 2 }
POST /onboarding/step/2          → body: { provider, model }; save to session; returns { nextStep: 3 }
POST /onboarding/step/3          → body: { apiKey }; validate + store in vault; returns { nextStep: 4, valid: true/false }
POST /onboarding/step/4          → body: { systemPrompt }; store in vault; returns { nextStep: 5 }
POST /onboarding/step/5          → body: { mcpServers: [{name,transport,url?,...}] }; register each; returns { nextStep: 6, registered: [names] }
GET  /onboarding/complete        → { complete: true, chatUrl: "/#/chat", pluginSteps: [{stepId, title, isConfigured}] }
```

**All POST handlers MUST:**
1. Call `session.canProceedTo(targetStep)` — return HTTP 403 if false.
2. Perform the action (vault write, validation, etc.).
3. Call `session.advance()`.
4. Return JSON with `nextStep` number.

**Step 2 — Provider defaults (model pre-population):**
```java
private static final Map<String, String> DEFAULT_MODELS = Map.of(
    "groq",        "llama-3.1-8b-instant",
    "openai",      "gpt-4o-mini",
    "ollama",      "llama3.2",
    "anthropic",   "claude-haiku-4-5",
    "claude-code", "claude-sonnet-4-5"
);
```

**Step 3 — Credentials validation:**
```java
// 1. Write apiKey to vault: VaultConstants.LLM_API_KEY (or the relevant constant).
// 2. Create a test ChatModel via LlmModelFactory using session.selectedProvider + session.selectedModel.
// 3. Send a minimal test prompt: "Reply with the single word: ok"
// 4. If response contains "ok" (case-insensitive) → credentialsValidated = true, return { nextStep: 4, valid: true }.
// 5. If LLM call throws any exception → do NOT advance; return { nextStep: 3, valid: false, error: "..." }.
// 6. For claude-code provider: skip API key write (OAuth token handled by ClaudeCodeProviderFactory).
//    Advance directly after token detection succeeds.
// Timeout the test call at 15 seconds. Use a virtual thread via @RunOnVirtualThread.
```

**Step 4 — System prompt:**
```java
// Store body.systemPrompt in vault under VaultConstants.SYSTEM_PROMPT_KEY (create if missing).
// If body.systemPrompt is blank → use default: "You are ArcClaw, a helpful AI assistant."
// Return { nextStep: 5 }.
```

**Step 5 — MCP registration:**
```java
// For each item in body.mcpServers:
//   Build McpServerConfig and call mcpClientService.connect(cfg).
//   Collect server names of successfully connected servers.
//   Log failures but do not abort — partial success is OK.
// Return { nextStep: 6, registered: ["server1", "server2"] }.
// Empty mcpServers list is valid (user skips MCP setup).
```

**GET /onboarding/complete:**
```java
// Return:
// {
//   "complete": true,
//   "chatUrl": "/#/chat",
//   "provider": session.selectedProvider,
//   "model": session.selectedModel,
//   "pluginSteps": [ { stepId, title, isConfigured } for each OnboardingStepProvider ]
// }
// Discover OnboardingStepProvider implementations via @Inject Instance<OnboardingStepProvider>.
```

***

### Component C4 — OnboardingResource DTOs

**File:** `com.arcclaw.onboarding.dto.*`

```java
public record OnboardingStatusDto(
    int step,
    String stepName,
    String provider,
    String model,
    boolean credentialsOk
) {}

public record Step2Request(String provider, String model) {}
public record Step3Request(String apiKey) {}
public record Step4Request(String systemPrompt) {}

public record McpServerRequest(
    String name,
    String transport,   // "http" | "stdio"
    String url,         // nullable for stdio
    String command,     // nullable for http
    String args,        // nullable
    String env          // nullable, key=value lines
) {}
public record Step5Request(List<McpServerRequest> mcpServers) {}

public record StepResponse(int nextStep) {}
public record Step3Response(int nextStep, boolean valid, String error) {}
public record Step5Response(int nextStep, List<String> registered) {}
public record CompleteResponse(boolean complete, String chatUrl, String provider, String model, List<PluginStepDto> pluginSteps) {}
public record PluginStepDto(String stepId, String title, boolean isConfigured) {}
```

***

### Phase 80 Tests (Part A)

**`OnboardingSessionTest`** (unit test):
- Initial step is WELCOME (1).
- `canProceedTo(PROVIDER)` returns true from WELCOME.
- `canProceedTo(CREDENTIALS)` returns false from WELCOME (skip attempt).
- `advance()` moves to next step correctly.
- `advance()` from COMPLETE stays at COMPLETE.

**`OnboardingResourceTest`** (`@QuarkusTest`):
- `GET /onboarding/status` → 200, step=1.
- `POST /onboarding/step/2` before step/1 → 403.
- `POST /onboarding/step/1` → 200, nextStep=2.
- `POST /onboarding/step/2` with `{"provider":"groq","model":"llama-3.1-8b-instant"}` → 200, nextStep=3.
- `POST /onboarding/step/3` with blank apiKey for claude-code provider → handled gracefully.
- `POST /onboarding/step/5` with empty mcpServers → 200, registered=[].
- `GET /onboarding/complete` → complete=true.

***

## Part B — arc-onboarding Lit Component (in arcclaw-ui)

**New file:** `arcclaw-ui/src/components/arc-onboarding.ts`

**Also register in:** `arcclaw-ui/src/main.ts` — add `import './components/arc-onboarding';`
**Also render in:** `arc-shell.ts` — the `#/onboarding` route already renders `<arc-provider-switcher>`;
replace it with `<arc-onboarding>`.

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface OnboardingStatus {
  step: number;
  stepName: string;
  provider: string | null;
  model: string | null;
  credentialsOk: boolean;
}

const PROVIDER_DEFAULTS: Record<string, string> = {
  groq: 'llama-3.1-8b-instant',
  openai: 'gpt-4o-mini',
  ollama: 'llama3.2',
  anthropic: 'claude-haiku-4-5',
  'claude-code': 'claude-sonnet-4-5',
};

const STEP_TITLES = ['Welcome', 'Provider', 'Credentials', 'System Prompt', 'MCP', 'Complete'];

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {
  @property({ type: String }) apiKey = '';

  @state() private _status: OnboardingStatus | null = null;
  @state() private _loading = false;
  @state() private _error = '';

  // Step 2 fields
  @state() private _provider = 'groq';
  @state() private _model = PROVIDER_DEFAULTS['groq'];

  // Step 3 fields
  @state() private _apiKeyInput = '';
  @state() private _credError = '';

  // Step 4 fields
  @state() private _systemPrompt = '';

  // Step 5 fields
  @state() private _mcpName = '';
  @state() private _mcpTransport: 'http' | 'stdio' = 'http';
  @state() private _mcpUrl = '';
  @state() private _mcpCommand = '';
  @state() private _mcpServers: Array<{ name: string; transport: string; url?: string; command?: string }> = [];

  static styles = css`
    :host { display: block; max-width: 600px; margin: 0 auto; }
    h2 { color: #c9d1d9; margin-bottom: 1.5rem; font-size: 1.1rem; }
    .progress { display: flex; gap: 0.5rem; margin-bottom: 2rem; }
    .step-dot {
      flex: 1; height: 4px; border-radius: 2px; background: #21262d;
      transition: background 0.3s;
    }
    .step-dot.done    { background: #3fb950; }
    .step-dot.current { background: #58a6ff; }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 1.5rem;
    }
    .step-title { color: #58a6ff; font-size: 1rem; font-weight: 600; margin-bottom: 1rem; }
    label { display: block; color: #8b949e; font-size: 0.8rem; margin-bottom: 0.3rem; margin-top: 0.75rem; }
    input, select, textarea {
      width: 100%; background: #0d1117; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; padding: 0.5rem 0.75rem; font-size: 0.875rem; font-family: inherit;
    }
    textarea { resize: vertical; min-height: 100px; }
    .radio-group { display: flex; flex-direction: column; gap: 0.5rem; margin-top: 0.5rem; }
    .radio-option { display: flex; align-items: center; gap: 0.5rem; cursor: pointer; color: #c9d1d9; font-size: 0.875rem; }
    .actions { display: flex; justify-content: flex-end; margin-top: 1.5rem; }
    .btn {
      background: #238636; border: none; border-radius: 6px; color: white;
      cursor: pointer; padding: 0.5rem 1.5rem; font-size: 0.9rem;
    }
    .btn:disabled { background: #21262d; color: #484f58; cursor: not-allowed; }
    .btn-secondary {
      background: transparent; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; cursor: pointer; padding: 0.5rem 1rem; font-size: 0.875rem;
      margin-right: 0.5rem;
    }
    .error { color: #f85149; font-size: 0.8rem; margin-top: 0.5rem; }
    .success { color: #3fb950; font-size: 0.8rem; margin-top: 0.5rem; }
    .mcp-list { margin-top: 0.5rem; display: flex; flex-direction: column; gap: 0.3rem; }
    .mcp-item {
      background: #0d1117; border-radius: 6px; padding: 0.4rem 0.75rem;
      font-size: 0.8rem; color: #c9d1d9; display: flex; gap: 0.5rem;
    }
    .complete-box { text-align: center; padding: 1rem 0; }
    .complete-box .check { font-size: 3rem; }
    .complete-box p { color: #8b949e; margin: 0.5rem 0; }
    .complete-box a { color: #58a6ff; text-decoration: none; }
  `;

  async connectedCallback() {
    super.connectedCallback();
    await this._fetchStatus();
  }

  private async _fetchStatus() {
    try {
      const res = await fetch('/onboarding/status', {
        headers: { 'Authorization': `Bearer ${this.apiKey}` },
      });
      if (res.ok) {
        this._status = await res.json();
        if (this._status?.provider) this._provider = this._status.provider;
        if (this._status?.model)    this._model    = this._status.model;
      }
    } catch { this._error = 'Failed to load onboarding status.'; }
  }

  private async _post(url: string, body: unknown): Promise<unknown> {
    this._loading = true;
    this._error = '';
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${this.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
      await this._fetchStatus();
      return json;
    } catch (e: unknown) {
      this._error = e instanceof Error ? e.message : String(e);
      return null;
    } finally {
      this._loading = false;
    }
  }

  private _renderProgress(currentStep: number) {
    return html`
      <div class="progress">
        ${STEP_TITLES.map((_, i) => html`
          <div class="step-dot ${i + 1 < currentStep ? 'done' : i + 1 === currentStep ? 'current' : ''}"></div>
        `)}
      </div>
    `;
  }

  // ── Step renderers ─────────────────────────────────────────────────────────

  private _step1() {
    return html`
      <div class="step-title">Welcome to ArcClaw</div>
      <p style="color:#8b949e;line-height:1.6;font-size:0.9rem">
        This wizard will guide you through configuring your LLM provider, API credentials,
        system prompt, and optional MCP servers. All secrets are stored in ArcClaw's encrypted
        vault — never in plain text files.
      </p>
      <div class="actions">
        <button class="btn" @click="${() => this._post('/onboarding/step/1', {})}">
          Get Started →
        </button>
      </div>
    `;
  }

  private _step2() {
    return html`
      <div class="step-title">Choose LLM Provider</div>
      <div class="radio-group">
        ${Object.keys(PROVIDER_DEFAULTS).map(p => html`
          <label class="radio-option">
            <input type="radio" name="provider" .value="${p}"
              ?checked="${this._provider === p}"
              @change="${() => { this._provider = p; this._model = PROVIDER_DEFAULTS[p]; }}"
            />
            ${p === 'claude-code' ? `${p} (uses local OAuth — no API key needed)` : p}
          </label>
        `)}
      </div>
      <label>Model Name</label>
      <input type="text" .value="${this._model}"
        @input="${(e: InputEvent) => this._model = (e.target as HTMLInputElement).value}"
      />
      <div class="actions">
        <button class="btn" ?disabled="${this._loading}"
          @click="${() => this._post('/onboarding/step/2', { provider: this._provider, model: this._model })}">
          Next →
        </button>
      </div>
    `;
  }

  private _step3() {
    const isClaudeCode = this._provider === 'claude-code';
    return html`
      <div class="step-title">Credentials</div>
      ${isClaudeCode
        ? html`<p style="color:#8b949e;font-size:0.875rem">
            Using Claude Code OAuth. ArcClaw will read your local
            <code style="color:#c9d1d9">~/.claude/credentials.json</code> automatically.
            Click Next to validate.
          </p>`
        : html`
          <label>API Key for <strong style="color:#c9d1d9">${this._provider}</strong></label>
          <input type="password" placeholder="sk-..." .value="${this._apiKeyInput}"
            @input="${(e: InputEvent) => this._apiKeyInput = (e.target as HTMLInputElement).value}"
          />
        `}
      ${this._credError ? html`<div class="error">${this._credError}</div>` : ''}
      <div class="actions">
        <button class="btn" ?disabled="${this._loading || (!isClaudeCode && !this._apiKeyInput.trim())}"
          @click="${async () => {
            const res = await this._post('/onboarding/step/3', { apiKey: this._apiKeyInput }) as { valid: boolean; error?: string } | null;
            if (res && !res.valid) {
              this._credError = res.error ?? 'Validation failed. Check your API key.';
              await this._fetchStatus(); // stay on step 3
            } else {
              this._credError = '';
            }
          }}">
          ${this._loading ? 'Validating…' : 'Validate & Next →'}
        </button>
      </div>
    `;
  }

  private _step4() {
    return html`
      <div class="step-title">System Prompt</div>
      <p style="color:#8b949e;font-size:0.875rem;margin-bottom:0.75rem">
        Define your agent's persona and behavior. Leave blank for the default.
      </p>
      <textarea .value="${this._systemPrompt}"
        placeholder="You are ArcClaw, a helpful AI assistant…"
        @input="${(e: InputEvent) => this._systemPrompt = (e.target as HTMLTextAreaElement).value}"
      ></textarea>
      <div class="actions">
        <button class="btn" ?disabled="${this._loading}"
          @click="${() => this._post('/onboarding/step/4', { systemPrompt: this._systemPrompt })}">
          Next →
        </button>
      </div>
    `;
  }

  private _step5() {
    return html`
      <div class="step-title">MCP Servers (Optional)</div>
      <p style="color:#8b949e;font-size:0.875rem;margin-bottom:0.75rem">
        Add external Model Context Protocol servers for your agents to use. You can skip this.
      </p>
      <label>Transport</label>
      <select @change="${(e: Event) => this._mcpTransport = (e.target as HTMLSelectElement).value as 'http' | 'stdio'}">
        <option value="http">HTTP</option>
        <option value="stdio">stdio</option>
      </select>
      <label>Name</label>
      <input type="text" placeholder="my-mcp-server" .value="${this._mcpName}"
        @input="${(e: InputEvent) => this._mcpName = (e.target as HTMLInputElement).value}"
      />
      ${this._mcpTransport === 'http' ? html`
        <label>URL</label>
        <input type="text" placeholder="https://mcp.example.com" .value="${this._mcpUrl}"
          @input="${(e: InputEvent) => this._mcpUrl = (e.target as HTMLInputElement).value}"
        />
      ` : html`
        <label>Command</label>
        <input type="text" placeholder="npx mcp-server" .value="${this._mcpCommand}"
          @input="${(e: InputEvent) => this._mcpCommand = (e.target as HTMLInputElement).value}"
        />
      `}
      <button class="btn-secondary" style="margin-top:0.75rem"
        @click="${() => {
          if (!this._mcpName.trim()) return;
          this._mcpServers = [...this._mcpServers, {
            name: this._mcpName, transport: this._mcpTransport,
            url: this._mcpUrl || undefined, command: this._mcpCommand || undefined,
          }];
          this._mcpName = ''; this._mcpUrl = ''; this._mcpCommand = '';
        }}">
        + Add Server
      </button>
      <div class="mcp-list">
        ${this._mcpServers.map(s => html`<div class="mcp-item">🔌 ${s.name} (${s.transport})</div>`)}
      </div>
      <div class="actions">
        <button class="btn-secondary" @click="${() => this._post('/onboarding/step/5', { mcpServers: [] })}">
          Skip
        </button>
        <button class="btn" ?disabled="${this._loading}"
          @click="${() => this._post('/onboarding/step/5', { mcpServers: this._mcpServers })}">
          ${this._mcpServers.length > 0 ? 'Register & Finish' : 'Finish'}
        </button>
      </div>
    `;
  }

  private _stepComplete() {
    return html`
      <div class="complete-box">
        <div class="check">✅</div>
        <h3 style="color:#3fb950;margin:0.75rem 0">ArcClaw is Ready!</h3>
        <p>Provider: <strong style="color:#c9d1d9">${this._status?.provider}</strong>
           &nbsp;·&nbsp; Model: <strong style="color:#c9d1d9">${this._status?.model}</strong></p>
        <p style="margin-top:1rem">
          <a href="#/chat">→ Start chatting</a>
          &nbsp;|&nbsp;
          <a href="#/agents">→ View agents</a>
        </p>
      </div>
    `;
  }

  render() {
    if (!this._status) return html`<p style="color:#8b949e">Loading…</p>`;
    const step = this._status.step;

    return html`
      <h2>Setup Wizard</h2>
      <div class="card">
        ${this._renderProgress(step)}
        ${this._error ? html`<div class="error">${this._error}</div>` : ''}
        ${step === 1 ? this._step1() : ''}
        ${step === 2 ? this._step2() : ''}
        ${step === 3 ? this._step3() : ''}
        ${step === 4 ? this._step4() : ''}
        ${step === 5 ? this._step5() : ''}
        ${step === 6 ? this._stepComplete() : ''}
      </div>
    `;
  }
}
```

***

## Phase 80 Delivery Checklist
- [ ] `arcclaw-onboarding` module created: `pom.xml`, `beans.xml`, jandex plugin, no Qute
- [ ] `OnboardingStepProvider` SPI interface created
- [ ] `OnboardingSession` `@SessionScoped` bean with `canProceedTo()` + `advance()`
- [ ] `OnboardingResource` at `/onboarding` — all 7 endpoints return JSON
- [ ] Step 3 validates credentials via LLM test call with 15s timeout
- [ ] Step 5 registers MCP servers via `McpClientService` — partial failure is non-fatal
- [ ] All secrets written to `SecretVault` — zero writes to `.env` or `application.yaml`
- [ ] `arc-onboarding.ts` Lit component added to `arcclaw-ui`, registered in `main.ts`
- [ ] `arc-shell.ts` `#/onboarding` route updated to render `<arc-onboarding>`
- [ ] All `@QuarkusTest` integration tests green: `./mvnw clean test -pl arcclaw-onboarding`
- [ ] `npm test` still green in `arcclaw-ui`
- [ ] All existing tests still pass: `./mvnw clean test`
