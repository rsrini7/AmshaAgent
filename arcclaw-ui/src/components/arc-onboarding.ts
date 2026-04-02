import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient, getOnboardingComplete } from '../api/client';
import { McpServerRequest } from '../api/types';

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {
  private _client: ArcClient = new ArcClient('/api/v1');

  @state() private _currentStep = 1;
  @state() private _loading = false;
  @state() private _error = '';

  // Step 2
  @state() private _provider = 'groq';
  @state() private _model = '';

  // Step 3
  @state() private _apiKey = '';

  // Step 4
  @state() private _systemPrompt = 'You are ArcClaw, a helpful AI assistant.';

  // Step 5
  @state() private _mcpServers: McpServerRequest[] = [];
  @state() private _showAddMcp = false;
  @state() private _newMcp: Partial<McpServerRequest> = { transport: 'sse' };
  @state() private _validationOk = false;

  private readonly DEFAULT_MODELS: Record<string, string> = {
    groq: 'meta-llama/llama-4-scout-17b-16e-instruct',
    openai: 'gpt-4o-mini',
    ollama: 'llama3.2',
    anthropic: 'claude-haiku-4-5',
    'claude-code': 'claude-sonnet-4-5',
    openrouter: 'openrouter/free',
  };

  static styles = css`
  :host {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    background: var(--bg-base);
    font-family: var(--font-family);
    padding: var(--space-6) var(--space-4);
  }

  /* ── Wizard card ──────────────────────────────────────── */
  .wizard-card {
    width: 100%;
    max-width: 480px;
    background: var(--bg-surface);
    border: 1px solid var(--border-card);
    border-radius: var(--radius-xl);
    box-shadow: var(--shadow-lg), var(--shadow-glow);
    overflow: hidden;
    position: relative;
    animation: card-in 0.4s cubic-bezier(0.16,1,0.3,1) both;
  }

  [data-theme="dark"] .wizard-card,
  :host-context([data-theme="dark"]) .wizard-card {
    background: linear-gradient(145deg, #13151f 0%, #111318 100%);
    border-color: rgba(255,255,255,0.06);
  }

  @keyframes card-in {
    from { opacity: 0; transform: translateY(20px) scale(0.98); }
    to   { opacity: 1; transform: translateY(0)   scale(1); }
  }

  /* Gradient top line */
  .wizard-card::before {
    content: "";
    position: absolute; top: 0; left: 0; right: 0; height: 2px;
    background: linear-gradient(90deg,
      var(--accent) 0%, var(--accent-secondary) 100%);
  }

  /* ── Header ───────────────────────────────────────────── */
  .wizard-header {
    padding: var(--space-8) var(--space-8) var(--space-6);
    text-align: center;
  }

  .brand-mark {
    width: 44px; height: 44px;
    margin: 0 auto var(--space-4);
    background: linear-gradient(135deg,
      var(--accent) 0%, var(--accent-secondary) 100%);
    border-radius: var(--radius-md);
    display: flex; align-items: center; justify-content: center;
    box-shadow: 0 8px 24px var(--accent-glow);
  }

  .brand-mark svg { color: #fff; }

  .wizard-title {
    font-size: var(--font-size-2xl);
    font-weight: var(--font-weight-bold);
    color: var(--text-primary);
    letter-spacing: -0.02em;
    line-height: 1.2;
    margin-bottom: var(--space-2);
  }

  .wizard-subtitle {
    font-size: var(--font-size-sm);
    color: var(--text-muted);
    line-height: 1.5;
  }

  /* ── Progress stepper ─────────────────────────────────── */
  .stepper {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0;
    padding: 0 var(--space-8) var(--space-6);
  }

  .step-dot-wrap {
    display: flex; align-items: center; flex: 1;
  }
  .step-dot-wrap:last-child { flex: none; }

  .step-dot {
    width: 28px; height: 28px; flex-shrink: 0;
    border-radius: var(--radius-full);
    border: 2px solid var(--border);
    background: var(--bg-elevated);
    display: flex; align-items: center; justify-content: center;
    font-size: var(--font-size-xs);
    font-weight: var(--font-weight-semi);
    color: var(--text-muted);
    transition: all var(--transition);
    position: relative; z-index: 1;
  }

  .step-dot.active {
    border-color: var(--accent);
    background: var(--accent);
    color: #fff;
    box-shadow: 0 0 0 4px var(--accent-glow);
  }

  .step-dot.done {
    border-color: var(--accent-secondary);
    background: var(--accent-secondary);
    color: #fff;
  }

  .step-connector {
    flex: 1; height: 2px;
    background: var(--border);
    margin: 0 -1px;
    transition: background var(--transition-slow);
  }

  .step-connector.done {
    background: linear-gradient(90deg,
      var(--accent-secondary) 0%, var(--accent) 100%);
  }

  /* ── Body ─────────────────────────────────────────────── */
  .wizard-body {
    padding: 0 var(--space-8) var(--space-8);
    animation: step-in 0.28s cubic-bezier(0.16,1,0.3,1) both;
  }

  @keyframes step-in {
    from { opacity: 0; transform: translateX(16px); }
    to   { opacity: 1; transform: translateX(0); }
  }

  /* ── Labels ───────────────────────────────────────────── */
  .field-label {
    display: block;
    font-size: var(--font-size-xs);
    font-weight: var(--font-weight-semi);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--text-muted);
    margin-bottom: var(--space-3);
  }

  /* ── Provider cards (replaces native <select>) ────────── */
  .provider-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-3);
    margin-bottom: var(--space-6);
  }

  .provider-card {
    display: flex; align-items: center; gap: var(--space-3);
    padding: var(--space-4);
    background: var(--bg-elevated);
    border: 1.5px solid var(--border-card);
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: all var(--transition);
    position: relative; overflow: hidden;
  }

  .provider-card:hover {
    border-color: var(--accent);
    background: var(--accent-subtle);
    transform: translateY(-1px);
    box-shadow: var(--shadow-sm);
  }

  .provider-card.selected {
    border-color: var(--accent);
    background: var(--accent-subtle);
    box-shadow: 0 0 0 3px var(--accent-glow);
  }

  .provider-card.selected::after {
    content: "✓";
    position: absolute; top: 6px; right: 8px;
    font-size: var(--font-size-xs);
    color: var(--accent);
    font-weight: var(--font-weight-bold);
  }

  .provider-icon {
    width: 32px; height: 32px; flex-shrink: 0;
    border-radius: var(--radius-sm);
    display: flex; align-items: center; justify-content: center;
    font-size: 16px; font-weight: var(--font-weight-bold);
  }

  .provider-icon svg { width: 18px; height: 18px; }

  .provider-name {
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    color: var(--text-primary);
  }

  .provider-tag {
    font-size: 10px;
    color: var(--text-muted);
    margin-top: 1px;
  }

  .field-input, .field-textarea, .field-select {
    width: 100%;
    padding: var(--space-3) var(--space-4);
    background: var(--bg-elevated);
    border: 1.5px solid var(--border);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    font-family: var(--font-family);
    font-size: var(--font-size-sm);
    transition: border-color var(--transition), box-shadow var(--transition),
                background var(--transition);
    outline: none;
    -webkit-appearance: none;
  }

  .input-shell {
    position: relative;
    width: 100%;
  }

  .input-shell .field-input {
    width: 100%;
    padding-right: 4.25rem !important;
  }

  .input-trailing-btn {
    position: absolute;
    top: 50%;
    right: 0.75rem;
    transform: translateY(-50%);
    width: 2.5rem;
    height: 2.5rem;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--border);
    border-radius: 0.75rem;
    background: color-mix(in srgb, var(--bg-elevated) 88%, transparent);
    color: var(--text-muted);
    cursor: pointer;
    transition:
      color var(--transition),
      border-color var(--transition),
      background var(--transition),
      box-shadow var(--transition);
    backdrop-filter: blur(8px);
  }

  .input-trailing-btn:hover {
    color: var(--text-primary);
    border-color: var(--accent);
    background: var(--accent-subtle);
    box-shadow: 0 0 0 3px var(--accent-glow);
  }

  .input-trailing-btn:focus-visible {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-glow);
  }

  .input-trailing-btn svg {
    width: 1rem;
    height: 1rem;
    pointer-events: none;
  }

  .field-input:focus, .field-textarea:focus, .field-select:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-glow);
    background: var(--bg-surface);
  }

  .field-input::placeholder, .field-textarea::placeholder {
    color: var(--text-faint);
  }

  .field-textarea {
    min-height: 100px; resize: vertical;
    line-height: 1.6;
  }

  /* ── Model selector (still a <select> but styled) ─────── */
  .model-select-wrap {
    position: relative; margin-top: var(--space-5);
  }

  .model-select-wrap::after {
    content: "";
    position: absolute; right: 14px; top: 50%;
    transform: translateY(-50%);
    width: 0; height: 0;
    border-left: 4px solid transparent;
    border-right: 4px solid transparent;
    border-top: 5px solid var(--text-muted);
    pointer-events: none;
  }

  /* ── Status messages ──────────────────────────────────── */
  .status-msg {
    display: flex; align-items: center; gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    margin-top: var(--space-4);
    animation: step-in 0.2s ease both;
  }

  .status-msg.success {
    background: var(--success-bg);
    color: var(--success);
    border: 1px solid rgba(34,197,94,0.2);
  }

  .status-msg.error {
    background: var(--error-bg);
    color: var(--error);
    border: 1px solid rgba(239,68,68,0.2);
  }

  .status-msg.loading {
    background: var(--accent-subtle);
    color: var(--accent);
    border: 1px solid var(--border-focus);
  }

  /* ── MCP server list ──────────────────────────────────── */
  .mcp-list {
    display: flex; flex-direction: column;
    gap: var(--space-2); margin-bottom: var(--space-4);
  }

  .mcp-item {
    display: flex; align-items: center; gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    background: var(--bg-elevated);
    border: 1px solid var(--border-card);
    border-radius: var(--radius-md);
    animation: step-in 0.2s ease both;
  }

  .mcp-item-name {
    flex: 1;
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    color: var(--text-primary);
  }

  .mcp-item-tag {
    font-size: var(--font-size-xs);
    color: var(--text-muted);
    background: var(--bg-base);
    padding: 2px 8px;
    border-radius: var(--radius-full);
    border: 1px solid var(--border);
  }

  .mcp-remove {
    width: 24px; height: 24px;
    border-radius: var(--radius-full);
    border: 1px solid var(--border);
    background: transparent;
    color: var(--text-muted);
    cursor: pointer; font-size: 14px;
    display: flex; align-items: center; justify-content: center;
    transition: all var(--transition);
    flex-shrink: 0;
  }

  .mcp-remove:hover {
    background: var(--error-bg);
    color: var(--error);
    border-color: rgba(239,68,68,0.3);
  }

  .add-mcp-btn {
    width: 100%;
    padding: var(--space-3);
    background: transparent;
    border: 1.5px dashed var(--border);
    border-radius: var(--radius-md);
    color: var(--text-muted);
    font-size: var(--font-size-sm);
    cursor: pointer;
    transition: all var(--transition);
  }

  .add-mcp-btn:hover {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--accent-subtle);
  }

  /* ── Action buttons ───────────────────────────────────── */
  .btn-row {
    display: flex; gap: var(--space-3);
    margin-top: var(--space-6);
  }

  .btn-primary {
    flex: 1;
    padding: var(--space-3) var(--space-6);
    background: linear-gradient(135deg,
      var(--accent) 0%, color-mix(in srgb, var(--accent) 70%,
      var(--accent-secondary) 30%) 100%);
    border: none;
    border-radius: var(--radius-md);
    color: #fff;
    font-family: var(--font-family);
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-semi);
    cursor: pointer;
    transition: all var(--transition);
    position: relative; overflow: hidden;
    box-shadow: 0 4px 12px var(--accent-glow);
  }

  .btn-primary:hover {
    transform: translateY(-1px);
    box-shadow: 0 8px 20px var(--accent-glow);
    filter: brightness(1.1);
  }

  .btn-primary:active {
    transform: translateY(0);
    filter: brightness(0.95);
  }

  .btn-primary:disabled {
    opacity: 0.45; cursor: not-allowed;
    transform: none; filter: none;
    box-shadow: none;
  }

  /* Shimmer on primary btn */
  .btn-primary::after {
    content: "";
    position: absolute; inset: 0;
    background: linear-gradient(105deg,
      transparent 30%, rgba(255,255,255,0.12) 50%, transparent 70%);
    transform: translateX(-100%);
    transition: transform 0.5s ease;
  }

  .btn-primary:hover::after { transform: translateX(100%); }

  .btn-ghost {
    padding: var(--space-3) var(--space-5);
    background: transparent;
    border: 1.5px solid var(--border);
    border-radius: var(--radius-md);
    color: var(--text-muted);
    font-family: var(--font-family);
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    cursor: pointer;
    transition: all var(--transition);
  }

  .btn-ghost:hover {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--accent-subtle);
  }

  /* ── Loading spinner ──────────────────────────────────── */
  .spinner {
    width: 14px; height: 14px;
    border: 2px solid rgba(255,255,255,0.3);
    border-top-color: #fff;
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
    flex-shrink: 0;
  }

  @keyframes spin { to { transform: rotate(360deg); } }

  /* ── Welcome step ─────────────────────────────────────── */
  .welcome-features {
    display: flex; flex-direction: column;
    gap: var(--space-3); margin: var(--space-6) 0;
  }

  .feature-row {
    display: flex; align-items: center; gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    background: var(--bg-elevated);
    border: 1px solid var(--border-card);
    border-radius: var(--radius-md);
    font-size: var(--font-size-sm);
    color: var(--text-primary);
  }

  .feature-dot {
    width: 8px; height: 8px; flex-shrink: 0;
    border-radius: var(--radius-full);
    background: linear-gradient(135deg,
      var(--accent), var(--accent-secondary));
  }

  /* ── Complete step ────────────────────────────────────── */
  .complete-icon {
    width: 64px; height: 64px; margin: 0 auto var(--space-4);
    background: var(--success-bg);
    border-radius: var(--radius-full);
    display: flex; align-items: center; justify-content: center;
    font-size: 28px;
    border: 2px solid rgba(34,197,94,0.2);
    animation: pulse-success 1.5s ease infinite alternate;
  }

  @keyframes pulse-success {
    from { box-shadow: 0 0 0 0 var(--success-bg); }
    to   { box-shadow: 0 0 0 12px transparent; }
  }
`;


  async connectedCallback() {
    super.connectedCallback();
    await this._fetchStatus();
  }

  private async _fetchStatus() {
    this._loading = true;
    try {
      const status = await this._client.onboardingStatus();
      this._currentStep = status.step;
      if (status.model) this._model = status.model;
    } catch (e: any) {
      this._error = `Setup Error: ${e.message}`;
    } finally {
      this._loading = false;
    }
  }

  private async _next(body?: object) {
    this._loading = true;
    this._error = '';
    try {
      const res = await this._client.onboardingStep(this._currentStep, body);
      
      // Step 3 specific validation feedback
      if (this._currentStep === 3 && res.valid === false) {
        this._error = res.error || 'Invalid API Key';
        return; // Don't advance if invalid
      }

      this._currentStep = res.nextStep;
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _finish() {
    try {
      const result = await getOnboardingComplete(this._client.baseUrl);
      this.dispatchEvent(new CustomEvent('api-key-set', {
        bubbles: true, composed: true,
        detail: { key: result.serviceKey }
      }));
    } catch (e: any) {
      this._error = `Finalizing Error: ${e.message}`;
    }
  }

  private _onProviderChange(val: string) {
    this._provider = val;
    this._model = this.DEFAULT_MODELS[val] || '';
    this._validationOk = false;
    this._error = '';
  }

  private _back() {
    if (this._currentStep > 1) {
      this._currentStep--;
      this._error = '';
      this._loading = false;
    }
  }

  private _validateKey() {
    this._next({ apiKey: this._apiKey });
  }

  private _addMcp() {
    if (this._newMcp.name && this._newMcp.transport) {
      this._mcpServers = [...this._mcpServers, this._newMcp as McpServerRequest];
      this._newMcp = { transport: 'sse' };
      this._showAddMcp = false;
    }
  }

  private _removeMcp(index: number) {
    this._mcpServers = this._mcpServers.filter((_, i) => i !== index);
  }

  render() {
    return html`
      <div class="wizard-card ${this._currentStep === 5 ? 'wide' : ''}">
        <div class="wizard-header">
          <div class="brand-mark">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
            </svg>
          </div>
          <h1 class="wizard-title">ArcClaw</h1>
          <p class="wizard-subtitle">AI Agent Infrastructure</p>
        </div>

        <div class="stepper">
          ${[1, 2, 3, 4, 5].map(s => html`
            <div class="step-dot-wrap">
              <div class="step-dot ${this._currentStep === s ? 'active' : this._currentStep > s ? 'done' : ''}">
                ${this._currentStep > s ? '✓' : s}
              </div>
              ${s < 5 ? html`<div class="step-connector ${this._currentStep > s ? 'done' : ''}"></div>` : ''}
            </div>
          `)}
        </div>

        ${this._error ? html`<div class="status-msg error">${this._error}</div>` : ''}

        <div class="wizard-body">
          ${this._renderStep()}
        </div>
      </div>
    `;
  }

  private _renderStep() {
    switch (this._currentStep) {
      case 1: return this._step1();
      case 2: return this._step2();
      case 3: return this._step3();
      case 4: return this._step4();
      case 5: return this._step5();
      case 6: return this._step6();
      default: return this._step1();
    }
  }

  private _step6() {
    return html`
      <div style="text-align:center; padding:var(--space-4) 0">
        <div class="complete-icon">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none"
               stroke="#22c55e" stroke-width="3">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </div>
        <h2 class="wizard-title" style="margin-top:var(--space-4)">Setup Complete!</h2>
        <p class="wizard-subtitle" style="margin-bottom:var(--space-8)">
          Your AI infrastructure is ready. You can now start
          chatting with your agent and assigning tasks.
        </p>

        <div class="btn-row">
          <button class="btn-primary" @click="${this._finish}">
            Go to Dashboard →
          </button>
        </div>
      </div>
    `;
  }

  private _step1() {
    return html`
      <div style="text-align:center; padding: var(--space-2) 0
                  var(--space-4)">
        <p style="font-size:var(--font-size-sm); color:var(--text-muted);
                  line-height:1.6; max-width:36ch; margin:0 auto">
          Configure your AI agent infrastructure in 4 quick steps.
        </p>
      </div>

      <div class="welcome-features">
        <div class="feature-row">
          <div class="feature-dot"></div>
          Choose your LLM provider and model
        </div>
        <div class="feature-row">
          <div class="feature-dot"></div>
          Validate your API credentials securely
        </div>
        <div class="feature-row">
          <div class="feature-dot"></div>
          Define your agent's system behaviour
        </div>
        <div class="feature-row">
          <div class="feature-dot"></div>
          Optionally connect MCP tool servers
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-primary" @click="${() => this._next()}">
          Get Started →
        </button>
      </div>
    `;
  }

  private _step2() {
    const providers = [
      { id:'groq',        name:'Groq',        tag:'Fast inference',  icon:'G',  color:'#f55036' },
      { id:'openai',      name:'OpenAI',       tag:'GPT-4o series',   icon:'O',  color:'#10a37f' },
      { id:'ollama',      name:'Ollama',       tag:'Local / private', icon:'🦙', color:'#666' },
      { id:'anthropic',   name:'Anthropic',    tag:'Claude series',   icon:'A',  color:'#cc785c' },
      { id:'claude-code', name:'Claude Code',  tag:'CLI tool',        icon:'C',  color:'#8b5cf6' },
      { id:'openrouter',  name:'OpenRouter',   tag:'Unified API',     icon:'R',  color:'#6561f5' },
    ];
    return html`
      <span class="field-label">Provider</span>
      <div class="provider-grid">
        ${providers.map(p => html`
          <div
            class="provider-card ${this._provider === p.id ? 'selected' : ''}"
            @click="${() => this._onProviderChange(p.id)}"
            role="radio"
            aria-checked="${this._provider === p.id}"
            tabindex="0"
            @keydown="${(e: KeyboardEvent) =>
              (e.key==='Enter'||e.key===' ') && this._onProviderChange(p.id)}"
          >
            <div class="provider-icon"
                 style="background:${p.color}22;color:${p.color}">
              ${p.icon}
            </div>
            <div>
              <div class="provider-name">${p.name}</div>
              <div class="provider-tag">${p.tag}</div>
            </div>
          </div>
        `)}
      </div>
      <div class="model-select-wrap">
        <span class="field-label">Model</span>
        <input
          class="field-input"
          type="text"
          placeholder="${this._provider === 'groq' ? 'meta-llama/llama-4-scout-17b-16e-instruct' :
                         this._provider === 'openai' ? 'gpt-4o-mini' :
                         this._provider === 'ollama' ? 'llama3.2' :
                         this._provider === 'anthropic' ? 'claude-haiku-4-5' :
                         this._provider === 'openrouter' ? 'openrouter/free' :
                         'claude-sonnet-4-5'}"
          .value="${this._model}"
          @input="${(e:any) => this._model = e.target.value}"
        />
      </div>
      <div class="btn-row">
        <button class="btn-primary"
          ?disabled="${!this._provider}"
          @click="${() => this._next({ provider: this._provider, model: this._model })}">Continue →</button>
      </div>
    `;
  }

  private _step3() {
    return html`
      <div style="margin-bottom: var(--space-5)">
        <p style="font-size: var(--font-size-sm);
                  color: var(--text-muted);
                  line-height: 1.6;
                  margin-bottom: var(--space-5)">
          Provide your key for
          <strong style="color:var(--text-primary);
                         font-weight:var(--font-weight-semi)">
            ${this._provider}
          </strong>.
          We'll validate it live now.
        </p>

        <label class="field-label"
               for="api-key-input">
          ${this._provider} API Key
        </label>

        <div class="input-shell">
          <input
            id="api-key-input"
            class="field-input"
            type="password"
            autocomplete="off"
            spellcheck="false"
            placeholder="sk-••••••••••••••••••••••••••••••••"
            .value="${this._apiKey ?? ''}"
            @input="${(e: any) => this._apiKey = e.target.value}"
            @keydown="${(e: KeyboardEvent) =>
              e.key === 'Enter' && this._validateKey()}"
            style="font-family: 'Courier New', monospace;
                   letter-spacing: 0.04em"
          />
          <button
            type="button"
            class="input-trailing-btn"
            @click="${(e: Event) => {
              const inp = (e.currentTarget as HTMLElement)
                .previousElementSibling as HTMLInputElement;
              inp.type = inp.type === 'password' ? 'text' : 'password';
            }}"
            aria-label="Toggle key visibility"
          >
            <svg width="16" height="16" viewBox="0 0 24 24"
                 fill="none" stroke="currentColor" stroke-width="2">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
              <circle cx="12" cy="12" r="3"/>
            </svg>
          </button>
        </div>

        ${this._provider === 'ollama' ? html`
          <p style="margin-top:var(--space-2);
                    font-size:var(--font-size-xs);
                    color:var(--text-muted)">
            💡 Ollama runs locally — enter any value (e.g. <code>local</code>)
          </p>
        ` : ''}

        ${this._provider === 'claude-code' ? html`
          <p style="margin-top:var(--space-2);
                    font-size:var(--font-size-xs);
                    color:var(--text-muted)">
            💡 Claude Code uses the CLI — no API key required
          </p>
        ` : ''}
      </div>

      ${this._error ? html`
        <div class="status-msg ${this._validationOk ? 'success' : 'error'}">
          ${this._validationOk ? html`
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="2.5">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
          ` : html`
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="2.5">
              <circle cx="12" cy="12" r="10"/>
              <line x1="15" y1="9" x2="9" y2="15"/>
              <line x1="9" y1="9" x2="15" y2="15"/>
            </svg>
          `}
          ${this._error}
        </div>
      ` : ''}

      <div class="btn-row" style="margin-top:var(--space-5)">
        <button class="btn-ghost" @click="${this._back}">
          ← Back
        </button>
        <button
          class="btn-primary"
          ?disabled="${this._loading ||
                      (!this._apiKey?.trim() &&
                       this._provider !== 'claude-code')}"
          @click="${this._validateKey}"
        >
          ${this._loading ? html`
            <span style="display:flex;align-items:center;
                         gap:var(--space-2);justify-content:center">
              <span class="spinner"></span> Validating…
            </span>
          ` : 'Validate Key →'}
        </button>
      </div>
    `;
  }

  private _step4() {
    return html`
      <p style="font-size:var(--font-size-sm); color:var(--text-muted);
                line-height:1.6; margin-bottom:var(--space-5)">
        The core instruction that defines how your ArcClaw
        agents behave. You can refine this later from settings.
      </p>

      <label class="field-label" for="system-prompt">
        System Instructions
      </label>
      <textarea
        id="system-prompt"
        class="field-textarea"
        rows="5"
        placeholder="You are ArcClaw, a helpful AI assistant. You help users by..."
        .value="${this._systemPrompt ?? 'You are ArcClaw, a helpful AI assistant.'}"
        @input="${(e: any) => this._systemPrompt = e.target.value}"
      ></textarea>

      <p style="margin-top:var(--space-2); font-size:var(--font-size-xs);
                color:var(--text-muted); line-height:1.5">
        💡 Be specific about tone, constraints, and what the agent
        should or shouldn't do.
      </p>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">
          ← Back
        </button>
        <button class="btn-primary"
          ?disabled="${!this._systemPrompt?.trim()}"
          @click="${() => this._next({ systemPrompt: this._systemPrompt })}">
          Save & Continue →
        </button>
      </div>
    `;
  }

  private _step5() {
    return html`
      <p style="font-size:var(--font-size-sm); color:var(--text-muted);
                line-height:1.6; margin-bottom:var(--space-5)">
        Connect external tool servers (MCP) to extend your agents
        with web search, code execution, databases, and more.
        <strong style="color:var(--text-primary)">Optional</strong>
        — skip if you're not sure yet.
      </p>

      <!-- Registered MCP list -->
      ${this._mcpServers?.length ? html`
        <div class="mcp-list">
          ${this._mcpServers.map((s: any, i: number) => html`
            <div class="mcp-item">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                   stroke="var(--accent-secondary)" stroke-width="2"
                   style="flex-shrink:0">
                <path d="M5 12h14M12 5l7 7-7 7"/>
              </svg>
              <span class="mcp-item-name">${s.name}</span>
              <span class="mcp-item-tag">${s.transport}</span>
              <button class="mcp-remove"
                @click="${() => this._removeMcp(i)}"
                aria-label="Remove ${s.name}">×</button>
            </div>
          `)}
        </div>
      ` : html`
        <div style="text-align:center; padding:var(--space-6) 0;
                    color:var(--text-muted); font-size:var(--font-size-sm)">
          No MCP servers added yet
        </div>
      `}

      <!-- Add MCP form (collapsible) -->
      ${this._showAddMcp ? html`
        <div style="background:var(--bg-elevated);
                    border:1px solid var(--border-card);
                    border-radius:var(--radius-md);
                    padding:var(--space-4);
                    margin-bottom:var(--space-3);
                    animation: step-in 0.2s ease both">

          <label class="field-label">Server Name</label>
          <input class="field-input" type="text"
            placeholder="e.g. brave-search"
            .value="${this._newMcp?.name ?? ''}"
            @input="${(e:any) => this._newMcp = {...this._newMcp, name: e.target.value}}"
            style="margin-bottom:var(--space-3)"/>

          <label class="field-label">Transport</label>
          <div style="display:grid; grid-template-columns:1fr 1fr;
                      gap:var(--space-2); margin-bottom:var(--space-3)">
            ${['sse','stdio'].map(t => html`
              <div class="provider-card
                          ${this._newMcp?.transport === t ? 'selected' : ''}"
                   @click="${() =>
                     this._newMcp = {...this._newMcp, transport: t as any}}">
                <span style="font-size:var(--font-size-sm);
                             font-weight:var(--font-weight-medium);
                             color:var(--text-primary)">${t === 'sse' ? 'http' : t}</span>
              </div>
            `)}
          </div>

          ${this._newMcp?.transport === 'sse' ? html`
            <label class="field-label">URL</label>
            <input class="field-input" type="url"
              placeholder="http://localhost:3100/mcp"
              .value="${this._newMcp?.url ?? ''}"
              @input="${(e:any) =>
                this._newMcp = {...this._newMcp, url: e.target.value}}"
              style="margin-bottom:var(--space-3)"/>
          ` : html`
            <label class="field-label">Command</label>
            <input class="field-input" type="text"
              placeholder="npx -y @brave/search-mcp"
              .value="${this._newMcp?.command ?? ''}"
              @input="${(e:any) =>
                this._newMcp = {...this._newMcp, command: e.target.value}}"
              style="margin-bottom:var(--space-3)"/>
          `}

          <div class="btn-row" style="margin-top:var(--space-2)">
            <button class="btn-ghost"
              @click="${() => this._showAddMcp = false}">
              Cancel
            </button>
            <button class="btn-primary"
              ?disabled="${!this._newMcp?.name?.trim() ||
                           !this._newMcp?.transport}"
              @click="${this._addMcp}">
              Add Server
            </button>
          </div>
        </div>
      ` : html`
        <button class="add-mcp-btn"
          @click="${() => this._showAddMcp = true}">
          + Add MCP Server
        </button>
      `}

      <div class="btn-row" style="margin-top:var(--space-5)">
        <button class="btn-ghost" @click="${this._back}">
          ← Back
        </button>
        <button class="btn-ghost"
          style="color:var(--text-muted)"
          @click="${this._finish}">
          Skip
        </button>
        <button class="btn-primary"
          ?disabled="${this._loading}"
          @click="${() => this._next({ mcpServers: this._mcpServers })}">
          ${this._loading ? html`
            <span style="display:flex;align-items:center;
                         gap:var(--space-2);justify-content:center">
              <span class="spinner"></span> Finishing…
            </span>
          ` : 'Finish Setup ✓'}
        </button>
      </div>
    `;
  }
}
