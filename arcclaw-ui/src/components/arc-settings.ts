import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';

const PROVIDERS = [
  { id:'groq',        name:'Groq',        tag:'Fast inference',  icon:'G',  color:'#f55036' },
  { id:'openai',      name:'OpenAI',       tag:'GPT-4o series',   icon:'O',  color:'#10a37f' },
  { id:'ollama',      name:'Ollama',       tag:'Local / private', icon:'🦙', color:'#666' },
  { id:'anthropic',   name:'Anthropic',    tag:'Claude series',   icon:'A',  color:'#cc785c' },
  { id:'claude-code', name:'Claude Code',  tag:'CLI tool',        icon:'C',  color:'#8b5cf6' },
  { id:'openrouter',  name:'OpenRouter',   tag:'Unified API',     icon:'R',  color:'#6561f5' },
  { id:'nvidia',      name:'NVIDIA',       tag:'NIM models',      icon:'N',  color:'#76b900' },
  { id:'cerebras',    name:'Cerebras',     tag:'Ultra-fast LPU',  icon:'Cb', color:'#11e400' },
  { id:'gemini',      name:'Gemini',       tag:'Google AI',       icon:'Ge', color:'#4285f4' },
];

@customElement('arc-settings')
export class ArcSettings extends LitElement {
  @property() apiKey = '';

  @state() private _activeTab = 'provider';
  
  @state() private _provider = '';
  @state() private _model = '';
  @state() private _inputApiKey = '';
  @state() private _systemPrompt = '';
  @state() private _appVersion = '';
  @state() private _hasApiKey = false;
  
  private _client!: ArcClient;
  @state() private _loading = false;
  @state() private _error = '';
  @state() private _success = '';

  static styles = css`
    :host {
      display: block;
      padding: var(--space-6);
      max-width: 800px;
      margin: 0 auto;
    }
    .tabs {
      display: flex; gap: var(--space-4); margin-bottom: var(--space-6);
      border-bottom: 1px solid var(--border);
      padding-bottom: var(--space-2);
    }
    .tab {
      padding: var(--space-2) var(--space-4); cursor: pointer;
      font-weight: var(--font-weight-medium); color: var(--text-muted);
      border-bottom: 2px solid transparent;
      margin-bottom: -calc(var(--space-2) + 1px);
    }
    .tab.active {
      color: var(--accent); border-bottom-color: var(--accent);
    }
    .section { display: flex; flex-direction: column; gap: var(--space-4); }

    /* Provider card styles from onboarding */
    .provider-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
    .provider-card {
      display: flex; align-items: center; gap: var(--space-3);
      padding: var(--space-4); background: var(--bg-elevated);
      border: 1.5px solid var(--border-card); border-radius: var(--radius-md);
      cursor: pointer; transition: all var(--transition); position: relative;
    }
    .provider-card:hover { border-color: var(--accent); background: var(--accent-subtle); }
    .provider-card.selected { border-color: var(--accent); background: var(--accent-subtle); box-shadow: 0 0 0 3px var(--accent-glow); }
    .provider-card.selected::after {
      content: "✓"; position: absolute; top: 6px; right: 8px;
      font-size: var(--font-size-xs); color: var(--accent); font-weight: var(--font-weight-bold);
    }
    .provider-icon {
      width: 32px; height: 32px; flex-shrink: 0; border-radius: var(--radius-sm);
      display: flex; align-items: center; justify-content: center;
      font-size: 16px; font-weight: var(--font-weight-bold);
    }
    .provider-name { font-size: var(--font-size-sm); font-weight: var(--font-weight-medium); color: var(--text-primary); }
    .provider-tag { font-size: 10px; color: var(--text-muted); margin-top: 1px; }

    .field-label { display: block; font-size: var(--font-size-xs); font-weight: var(--font-weight-semi); text-transform: uppercase; color: var(--text-muted); }
    .field-input, .field-textarea {
      width: 100%; padding: var(--space-3) var(--space-4); background: var(--bg-elevated);
      border: 1.5px solid var(--border); border-radius: var(--radius-md);
      color: var(--text-primary); font-family: var(--font-family); font-size: var(--font-size-sm);
      box-sizing: border-box;
    }
    .field-input:focus, .field-textarea:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 3px var(--accent-glow); }
    .field-textarea { min-height: 150px; resize: vertical; }

    .btn-primary {
      padding: var(--space-3) var(--space-6); background: var(--accent); color: #fff;
      border: none; border-radius: var(--radius-md); cursor: pointer; font-weight: var(--font-weight-semi);
    }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-danger {
      padding: var(--space-3) var(--space-6); background: var(--error); color: #fff;
      border: none; border-radius: var(--radius-md); cursor: pointer; font-weight: var(--font-weight-semi);
    }

    .status-msg { padding: var(--space-3); border-radius: var(--radius-md); font-size: var(--font-size-sm); margin-top: var(--space-4); }
    .status-msg.error { background: var(--error-bg); color: var(--error); border: 1px solid rgba(239,68,68,0.2); }
    .status-msg.success { background: var(--success-bg); color: var(--success); border: 1px solid rgba(34,197,94,0.2); }
  `;

  async connectedCallback() {
    super.connectedCallback();
    this._client = new ArcClient('/api/v1', this.apiKey);
    await this._loadSettings();
  }

  private async _loadSettings() {
    try {
      this._loading = true;
      const data = await this._client.getSettings();
      this._provider = data.provider;
      this._model = data.model;
      this._hasApiKey = data.hasApiKey;
      this._systemPrompt = data.systemPrompt;
      this._appVersion = data.appVersion;
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _saveProvider() {
    try {
      this._loading = true; this._error = ''; this._success = '';
      await this._client.patchProvider({ provider: this._provider, model: this._model, apiKey: this._inputApiKey });
      this._success = 'Provider updated successfully.';
      this._inputApiKey = '';
      await this._loadSettings();
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _savePrompt() {
    try {
      this._loading = true; this._error = ''; this._success = '';
      await this._client.patchPrompt(this._systemPrompt);
      this._success = 'Prompt updated successfully.';
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _reset() {
    if (!confirm('Are you sure you want to reset onboarding?')) return;
    try {
      this._loading = true; this._error = ''; this._success = '';
      await this._client.resetOnboarding();
      localStorage.removeItem('arc-api-key');
      window.location.hash = '#/onboarding';
      window.location.reload();
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  render() {
    return html`
      <h2>Settings ${this._appVersion ? html`<span style="font-size:0.5em;color:var(--text-muted)">v${this._appVersion}</span>` : ''}</h2>
      
      <div class="tabs">
        <div class="tab ${this._activeTab === 'provider' ? 'active' : ''}" @click="${() => this._activeTab = 'provider'}">LLM Provider</div>
        <div class="tab ${this._activeTab === 'prompt' ? 'active' : ''}" @click="${() => this._activeTab = 'prompt'}">System Prompt</div>
        <div class="tab ${this._activeTab === 'danger' ? 'active' : ''}" @click="${() => this._activeTab = 'danger'}">Danger Zone</div>
      </div>

      ${this._error ? html`<div class="status-msg error">${this._error}</div>` : ''}
      ${this._success ? html`<div class="status-msg success">${this._success}</div>` : ''}

      ${this._activeTab === 'provider' ? this._renderProvider() : ''}
      ${this._activeTab === 'prompt' ? this._renderPrompt() : ''}
      ${this._activeTab === 'danger' ? this._renderDanger() : ''}
    `;
  }

  private _renderProvider() {
    return html`
      <div class="section">
        <span class="field-label">Provider</span>
        <div class="provider-grid" role="radiogroup" aria-label="Select a provider">
          ${PROVIDERS.map(p => html`
            <div
              class="provider-card ${this._provider === p.id ? 'selected' : ''}"
              @click="${() => this._provider = p.id}"
            >
              <div class="provider-icon" style="background:${p.color}22;color:${p.color}">${p.icon}</div>
              <div>
                <div class="provider-name">${p.name}</div>
                <div class="provider-tag">${p.tag}</div>
              </div>
            </div>
          `)}
        </div>
        
        <span class="field-label">Model</span>
        <input class="field-input" .value="${this._model}" @input="${(e:any) => this._model = e.target.value}" />

        <span class="field-label" style="margin-top:var(--space-3)">API Key ${this._hasApiKey ? '(Leave blank to keep existing)' : ''}</span>
        <input class="field-input" type="password" .value="${this._inputApiKey}" @input="${(e:any) => this._inputApiKey = e.target.value}" placeholder="sk-••••••••••••" autocomplete="new-password" />

        <div style="margin-top:var(--space-4)">
          <button class="btn-primary" ?disabled="${this._loading}" @click="${() => this._saveProvider()}">Validate & Save</button>
        </div>
      </div>
    `;
  }

  private _renderPrompt() {
    return html`
      <div class="section">
        <span class="field-label">System Prompt</span>
        <textarea class="field-textarea" .value="${this._systemPrompt}" @input="${(e:any) => this._systemPrompt = e.target.value}"></textarea>
        <div style="margin-top:var(--space-4)">
          <button class="btn-primary" ?disabled="${this._loading}" @click="${() => this._savePrompt()}">Save Prompt</button>
        </div>
      </div>
    `;
  }

  private _renderDanger() {
    return html`
      <div class="section">
        <p style="color:var(--text-muted);font-size:var(--font-size-sm);line-height:1.5;">Resetting onboarding will clear your session and force you to re-run the wizard. Your existing API keys are not deleted until overwritten.</p>
        <div style="margin-top:var(--space-4)">
          <button class="btn-danger" ?disabled="${this._loading}" @click="${() => this._reset()}">Reset & Re-Onboard</button>
        </div>
      </div>
    `;
  }
}
