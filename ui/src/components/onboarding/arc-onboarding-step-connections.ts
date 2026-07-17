import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { ConnectionDraft } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-connections')
export class ArcOnboardingStepConnections extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }

      .connections-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--space-4);
      }

      .connections-title {
        font-size: var(--font-size-md);
        font-weight: var(--font-weight-bold);
        color: var(--text-primary);
      }

      .connection-list {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
        margin-bottom: var(--space-5);
      }

      .connection-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--space-3) var(--space-4);
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
      }

      .connection-info {
        display: flex;
        align-items: center;
        gap: var(--space-3);
      }

      .form-section {
        border-top: 1px solid var(--border);
        padding-top: var(--space-4);
        margin-top: var(--space-4);
      }

      .grid-2 {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--space-4);
        margin-bottom: var(--space-4);
      }
    `
  ];

  @property({ type: Array }) connections: ConnectionDraft[] = [];
  @property({ type: Array }) catalog: any[] = [];

  @state() private _selectedProviderId = 'groq';
  @state() private _apiKey = '';
  @state() private _baseUrl = '';
  @state() private _targetUrl = '';
  @state() private _modelId = '';
  @state() private _contextWindow = 8192;
  @state() private _structuredOutputMode = 'NATIVE';
  @state() private _capabilities: string[] = ['tool-calling'];
  @state() private _headroomEnabled = false;

  connectedCallback() {
    super.connectedCallback();
    this._loadProviderDefaults();
  }

  private _loadProviderDefaults() {
    const prov = this.catalog.find(p => p.id === this._selectedProviderId);
    if (prov) {
      this._modelId = prov.defaultModel || '';
      this._baseUrl = prov.hasBaseUrl ? '' : '';
      this._targetUrl = '';
    }
  }

  private _onProviderSelect(provId: string) {
    this._selectedProviderId = provId;
    this._loadProviderDefaults();
  }

  private _toggleCapability(cap: string) {
    if (this._capabilities.includes(cap)) {
      this._capabilities = this._capabilities.filter(c => c !== cap);
    } else {
      this._capabilities = [...this._capabilities, cap];
    }
  }

  private _addConnection() {
    const conn: ConnectionDraft = {
      provider: this._selectedProviderId,
      apiKey: this._apiKey || undefined,
      apiKeyVaultRef: this._apiKey ? 'staged-ref-' + Math.random().toString(36).substring(7) : undefined,
      baseUrl: this._baseUrl || undefined,
      targetUrl: this._selectedProviderId === 'generic-openai' ? this._targetUrl || undefined : undefined,
      modelId: this._modelId,
      contextWindow: Number(this._contextWindow),
      structuredOutputMode: this._structuredOutputMode,
      capabilities: this._capabilities,
      headroomEnabled: this._headroomEnabled
    };

    const updated = [...this.connections, conn];
    this.dispatchEvent(
      new CustomEvent('update-connections', {
        detail: { connections: updated },
        bubbles: true,
        composed: true
      })
    );

    // Reset inputs
    this._apiKey = '';
    this._baseUrl = '';
    this._targetUrl = '';
    this._loadProviderDefaults();
  }

  private _removeConnection(idx: number) {
    const updated = this.connections.filter((_, i) => i !== idx);
    this.dispatchEvent(
      new CustomEvent('update-connections', {
        detail: { connections: updated },
        bubbles: true,
        composed: true
      })
    );
  }

  private _onNext() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  render() {
    const activeProvider = this.catalog.find(p => p.id === this._selectedProviderId) || {};

    return html`
      <div class="connections-header">
        <div class="connections-title">Configured LLM Connections</div>
      </div>

      <div class="connection-list">
        ${this.connections.length === 0
          ? html`<div style="font-size:12px;color:var(--text-muted);text-align:center;padding:var(--space-4)">No connections configured yet.</div>`
          : this.connections.map((c, idx) => {
              const prov = this.catalog.find(p => p.id === c.provider) || {};
              return html`
                <div class="connection-row">
                  <div class="connection-info">
                    <div class="provider-icon" style="background:${prov.color || '#888'}22;color:${prov.color || '#888'}">
                      ${prov.icon || '?'}
                    </div>
                    <div>
                      <div class="provider-name">${prov.name || c.provider}</div>
                      <div class="provider-tag">${c.modelId} (${c.contextWindow.toLocaleString()} ctx)</div>
                    </div>
                  </div>
                  <button class="mcp-remove" @click="${() => this._removeConnection(idx)}">&times;</button>
                </div>
              `;
            })}
      </div>

      <div class="form-section">
        <span class="field-label">Select Provider</span>
        <div class="provider-grid">
          ${this.catalog.map(p => html`
            <div class="provider-card ${this._selectedProviderId === p.id ? 'selected' : ''}"
                 @click="${() => this._onProviderSelect(p.id)}">
              <div class="provider-icon" style="background:${p.color}22;color:${p.color}">
                ${p.icon}
              </div>
              <div>
                <div class="provider-name">${p.name}</div>
                <div class="provider-tag">${p.tag}</div>
              </div>
            </div>
          `)}
        </div>

        <div class="grid-2">
          <div>
            <span class="field-label">Model Identifier</span>
            <input class="field-input" type="text" .value="${this._modelId}"
                   @input="${(e: any) => (this._modelId = e.target.value)}" />
          </div>
          <div>
            <span class="field-label">Context Window (tokens)</span>
            <input class="field-input" type="number" .value="${this._contextWindow}"
                   @input="${(e: any) => (this._contextWindow = e.target.value)}" />
          </div>
        </div>

        ${activeProvider.requiresApiKey
          ? html`
              <div style="margin-bottom:var(--space-4)">
                <span class="field-label">API Key</span>
                <input class="field-input" type="password" placeholder="Enter provider API key"
                       .value="${this._apiKey}" @input="${(e: any) => (this._apiKey = e.target.value)}" />
              </div>
            `
          : ''}

        ${activeProvider.hasBaseUrl
          ? html`
              <div style="margin-bottom:var(--space-4)">
                <span class="field-label">Base URL Override (Optional)</span>
                <input class="field-input" type="text" placeholder="https://api.openai.com/v1"
                       .value="${this._baseUrl}" @input="${(e: any) => (this._baseUrl = e.target.value)}" />
              </div>
            `
          : ''}

        ${this._selectedProviderId === 'generic-openai'
          ? html`
              <div style="margin-bottom:var(--space-4)">
                <span class="field-label">Target URL (generic-openai)</span>
                <input class="field-input" type="text" placeholder="https://api.customprovider.com/v1/chat/completions"
                       .value="${this._targetUrl}" @input="${(e: any) => (this._targetUrl = e.target.value)}" />
              </div>
            `
          : ''}

        <div class="grid-2">
          <div>
            <span class="field-label">Structured Output Mode</span>
            <select class="field-select" .value="${this._structuredOutputMode}"
                    @change="${(e: any) => (this._structuredOutputMode = e.target.value)}">
              <option value="NATIVE">Native JSON Schema</option>
              <option value="PROMPT_DIRECTIVE">Prompt Directives only</option>
              <option value="LENIENT">Lenient Parser</option>
            </select>
          </div>
          <div style="display:flex;align-items:center;padding-top:var(--space-6)">
            <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-primary)">
              <input type="checkbox" ?checked="${this._headroomEnabled}"
                     @change="${(e: any) => (this._headroomEnabled = e.target.checked)}" />
              Enable Headroom Proxy
            </label>
          </div>
        </div>

        <div style="margin-bottom:var(--space-4)">
          <span class="field-label">Capabilities</span>
          <div style="display:flex;gap:var(--space-4)">
            <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-primary)">
              <input type="checkbox" ?checked="${this._capabilities.includes('tool-calling')}"
                     @change="${() => this._toggleCapability('tool-calling')}" />
              Tool Calling
            </label>
            <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-primary)">
              <input type="checkbox" ?checked="${this._capabilities.includes('vision')}"
                     @change="${() => this._toggleCapability('vision')}" />
              Vision / Multimodal
            </label>
          </div>
        </div>

        <button class="btn-ghost" style="width:100%" @click="${this._addConnection}">
          + Add Connection Profile
        </button>
      </div>

      <div class="btn-row">
        <button class="btn-primary" ?disabled="${this.connections.length === 0}" @click="${this._onNext}">
          Continue to Routing →
        </button>
      </div>
    `;
  }
}
