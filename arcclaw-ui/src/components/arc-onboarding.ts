import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';
import { McpServerRequest } from '../api/types';
import { ONBOARDING_COMPLETE_SENTINEL } from '../types/onboarding';

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

  private readonly DEFAULT_MODELS: Record<string, string> = {
    groq: 'llama-3.1-8b-instant',
    openai: 'gpt-4o-mini',
    ollama: 'llama3.2',
    anthropic: 'claude-haiku-4-5',
    'claude-code': 'claude-sonnet-4-5',
  };

  static styles = css`
    :host {
      display: flex; align-items: center; justify-content: center;
      height: 100vh; background: var(--bg-base);
      font-family: 'Inter', sans-serif;
    }
    .card {
      background: var(--bg-surface); border: 1px solid var(--border); 
      border-radius: 24px; padding: 48px; 
      width: 420px; display: flex; flex-direction: column; gap: 24px;
      box-shadow: 0 20px 50px rgba(0,0,0,0.5);
      position: relative; overflow: hidden;
      transition: width 0.3s ease;
    }
    .card.wide {
      width: 560px;
    }
    .card::before {
      content: '⬡';
      position: absolute; top: -30px; right: -30px;
      font-size: 15rem; color: var(--accent); opacity: 0.03;
      pointer-events: none;
    }
    .progress-bar {
      display: flex; gap: 8px; justify-content: center; margin-bottom: 8px;
    }
    .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--bg-elevated); border: 1px solid var(--border);
      transition: background 0.3s, border-color 0.3s;
    }
    .dot.active {
      background: var(--accent); border-color: var(--accent);
      box-shadow: 0 0 10px var(--accent-glow);
    }
    .logo-section { display: flex; align-items: center; gap: 12px; }
    .logo-icon { font-size: 28px; color: var(--accent); }
    h1 { 
      font-size: 24px; font-weight: 700; margin: 0;
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      -webkit-background-clip: text; -webkit-text-fill-color: transparent;
    }
    h2 { font-size: 18px; margin: 0; color: var(--text-primary); }
    p { color: var(--text-muted); font-size: 14px; line-height: 1.6; margin: 0; }
    
    .error { 
      color: #ef4444; font-size: 12px; background: rgba(239, 68, 68, 0.1); 
      padding: 10px; border-radius: 8px; border: 1px solid rgba(239, 68, 68, 0.2);
    }

    .form-group { display: flex; flex-direction: column; gap: 8px; }
    label { font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.1em; }
    
    input, select, textarea {
      background: var(--bg-elevated); border: 1px solid var(--border); 
      border-radius: 12px; color: var(--text-primary); 
      padding: 14px 16px; font-size: 15px; width: 100%;
      font-family: inherit; outline: none; transition: var(--transition);
      box-sizing: border-box;
    }
    input:focus, select:focus, textarea:focus { 
      border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); 
    }
    textarea { min-height: 100px; resize: vertical; }

    .actions { display: flex; gap: 12px; margin-top: 12px; }
    button {
      flex: 1;
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      border: none; border-radius: 12px; 
      color: white; cursor: pointer; padding: 14px; 
      font-size: 15px; font-weight: 600;
      transition: transform 0.2s, brightness 0.2s, opacity 0.2s;
    }
    button:hover:not(:disabled) { filter: brightness(1.1); transform: translateY(-1px); }
    button:disabled { opacity: 0.6; cursor: not-allowed; }
    button.secondary {
      background: var(--bg-elevated);
      border: 1px solid var(--border);
      color: var(--text-primary);
    }

    .mcp-entry {
      border: 1px solid var(--border); border-radius: 12px; padding: 16px;
      display: flex; flex-direction: column; gap: 12px; position: relative;
    }
    .remove-btn {
      position: absolute; top: 8px; right: 8px; background: none; border: none;
      color: #ef4444; cursor: pointer; font-size: 18px; padding: 4px;
    }
    .mcp-list { display: flex; flex-direction: column; gap: 16px; max-height: 300px; overflow-y: auto; padding-right: 4px; }
    
    /* Scrollbar */
    .mcp-list::-webkit-scrollbar { width: 4px; }
    .mcp-list::-webkit-scrollbar-track { background: transparent; }
    .mcp-list::-webkit-scrollbar-thumb { background: var(--border); border-radius: 2px; }

    .loader {
      display: inline-block; width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.3);
      border-radius: 50%; border-top-color: #fff; animation: spin 1s ease-in-out infinite;
      margin-right: 8px; vertical-align: middle;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
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
      if (status.provider) this._provider = status.provider;
      if (status.model) this._model = status.model;
      
      if (this._currentStep >= 6) {
        await this._finish();
      }
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
      this._currentStep = res.nextStep;
      if (this._currentStep >= 6) {
        await this._finish();
      }
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _finish() {
    try {
      const dto = await this._client.onboardingComplete();
      this.dispatchEvent(new CustomEvent('onboarding-complete', {
        detail: { provider: dto.provider, model: dto.model },
        bubbles: true,
        composed: true,
      }));
      this.dispatchEvent(new CustomEvent('api-key-set', {
        detail: { apiKey: ONBOARDING_COMPLETE_SENTINEL },
        bubbles: true,
        composed: true,
      }));
    } catch (e: any) {
      this._error = `Finalizing Error: ${e.message}`;
    }
  }

  private _onProviderChange(e: Event) {
    const val = (e.target as HTMLSelectElement).value;
    this._provider = val;
    this._model = this.DEFAULT_MODELS[val] || '';
  }

  private _addMcp() {
    this._mcpServers = [...this._mcpServers, { name: '', transport: 'sse', url: '' }];
  }

  private _removeMcp(index: number) {
    this._mcpServers = this._mcpServers.filter((_, i) => i !== index);
  }

  render() {
    return html`
      <div class="card ${this._currentStep === 5 ? 'wide' : ''}">
        <div class="progress-bar">
          ${[1, 2, 3, 4, 5].map(s => html`
            <div class="dot ${this._currentStep >= s ? 'active' : ''}"></div>
          `)}
        </div>

        <div class="logo-section">
          <span class="logo-icon">⬡</span>
          <h1>ArcClaw</h1>
        </div>

        ${this._error ? html`<div class="error">${this._error}</div>` : ''}

        ${this._renderStep()}
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
      case 6: return html`<h2>Setup Complete!</h2><p>Redirecting to ArcClaw...</p>`;
      default: return html`<p>Loading...</p>`;
    }
  }

  private _step1() {
    return html`
      <h2>Welcome to ArcClaw</h2>
      <p>Set up your AI agent in 5 steps. We'll configure your LLM provider, security, and tools.</p>
      <button @click="${() => this._next()}" ?disabled="${this._loading}">
        ${this._loading ? html`<span class="loader"></span>Applying...` : 'Get Started'}
      </button>
    `;
  }

  private _step2() {
    return html`
      <h2>Choose your LLM Provider</h2>
      <div class="form-group">
        <label>Provider</label>
        <select .value="${this._provider}" @change="${this._onProviderChange}">
          <option value="groq">Groq</option>
          <option value="openai">OpenAI</option>
          <option value="ollama">Ollama</option>
          <option value="anthropic">Anthropic</option>
          <option value="claude-code">Claude Code</option>
        </select>
      </div>
      <div class="form-group">
        <label>Model (Optional)</label>
        <input type="text" .value="${this._model}" placeholder="${this.DEFAULT_MODELS[this._provider]}"
               @input="${(e: any) => this._model = e.target.value}">
      </div>
      <button @click="${() => this._next({ provider: this._provider, model: this._model })}" ?disabled="${this._loading}">
        ${this._loading ? html`<span class="loader"></span>Connecting...` : 'Continue'}
      </button>
    `;
  }

  private _step3() {
    return html`
      <h2>Enter API Key</h2>
      <p>Provide your key for <strong>${this._provider}</strong>. We'll validate it now.</p>
      <div class="form-group">
        <label>${this._provider} API Key</label>
        <input type="password" .value="${this._apiKey}" placeholder="Enter your API key"
               @input="${(e: any) => this._apiKey = e.target.value}">
      </div>
      <button @click="${() => this._next({ apiKey: this._apiKey })}" ?disabled="${this._loading || !this._apiKey}">
        ${this._loading ? html`<span class="loader"></span>Validating...` : 'Validate Key'}
      </button>
    `;
  }

  private _step4() {
    return html`
      <h2>System Prompt</h2>
      <p>The core instruction that defines how your ArcClaw agents behave.</p>
      <div class="form-group">
        <label>System Instructions</label>
        <textarea .value="${this._systemPrompt}"
                  @input="${(e: any) => this._systemPrompt = e.target.value}"></textarea>
      </div>
      <button @click="${() => this._next({ systemPrompt: this._systemPrompt })}" ?disabled="${this._loading}">
        ${this._loading ? html`<span class="loader"></span>Saving...` : 'Save & Continue'}
      </button>
    `;
  }

  private _step5() {
    return html`
      <div style="display: flex; justify-content: space-between; align-items: center;">
        <h2>MCP Servers (Optional)</h2>
        <button class="secondary" style="flex: 0; padding: 8px 16px;" @click="${this._addMcp}">+ Add Server</button>
      </div>
      <p>Connect Multi-Context Protocol servers to grant your agent extra tools.</p>
      
      <div class="mcp-list">
        ${this._mcpServers.length === 0 ? html`<p style="text-align: center; padding: 20px; opacity: 0.5;">No MCP servers added yet.</p>` : ''}
        ${this._mcpServers.map((s, i) => html`
          <div class="mcp-entry">
            <button class="remove-btn" @click="${() => this._removeMcp(i)}">×</button>
            <div class="form-group">
              <label>Name</label>
              <input type="text" .value="${s.name}" @input="${(e: any) => s.name = e.target.value}">
            </div>
            <div class="form-group">
              <label>Transport</label>
              <select .value="${s.transport}" @change="${(e: any) => { s.transport = e.target.value; this.requestUpdate(); }}">
                <option value="sse">SSE (HTTP)</option>
                <option value="stdio">Stdio (Local Process)</option>
              </select>
            </div>
            ${s.transport === 'sse' ? html`
              <div class="form-group">
                <label>URL</label>
                <input type="text" .value="${s.url || ''}" placeholder="http://localhost:3001/mcp"
                       @input="${(e: any) => s.url = e.target.value}">
              </div>
            ` : html`
              <div class="form-group">
                <label>Command</label>
                <input type="text" .value="${s.command || ''}" placeholder="npx"
                       @input="${(e: any) => s.command = e.target.value}">
              </div>
            `}
          </div>
        `)}
      </div>

      <div class="actions">
        <button @click="${() => this._next({ mcpServers: this._mcpServers })}" ?disabled="${this._loading}">
          ${this._loading ? html`<span class="loader"></span>Initializing...` : 'Finish Setup'}
        </button>
      </div>
    `;
  }
}
