import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { ConnectionDraft, ModelProfileDraft, ProviderDescriptor, NetworkProfilePolicyDto, EgressMode, StructuredOutputMode } from '../../api/types';
import { ArcClient } from '../../api/client';
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

  @property({ type: Object }) client?: ArcClient;
  @property({ type: Array }) connections: ConnectionDraft[] = [];
  @property({ type: Array }) catalog: ProviderDescriptor[] = [];
  @property({ type: Object }) modelDefaults: Record<string, { defaultModel: string; defaultBaseUrl?: string }> = {};

  @state() private _selectedProviderId = 'groq';
  @state() private _apiKey = '';
  @state() private _baseUrl = '';
  @state() private _targetUrl = '';
  @state() private _modelId = '';
  @state() private _contextWindow = 8192;
  @state() private _structuredOutputMode: StructuredOutputMode = 'NATIVE';
  @state() private _capabilities: string[] = ['tool-calling'];
  @state() private _headroomEnabled = false;
  @state() private _discoveredModels: { id: string; label: string }[] = [];
  @state() private _isDiscoveringModels = false;
  private _discoverSequence = 0;
  @state() private _showNetworkProfile = false;
  @state() private _egressMode: EgressMode = 'ALLOW_ALL';
  @state() private _allowedHosts = '';
  @state() private _requireProxy = false;
  @state() private _proxyUrl = '';
  @state() private _proxyCredentialVaultRef = '';
  @state() private _noProxyHosts = '';
  @state() private _connectTimeoutMs: number | undefined = undefined;
  @state() private _readTimeoutMs: number | undefined = undefined;
  @state() private _tlsTrustMode: 'SYSTEM' | 'CUSTOM_CA' = 'SYSTEM';
  @state() private _customCaVaultRef = '';
  @state() private _addingProfileConnId: string | null = null;
  @state() private _newProfileModelId = '';

  connectedCallback() {
    super.connectedCallback();
    this._loadProviderDefaults();
  }

  protected updated(changedProperties: Map<string, unknown>) {
    if ((changedProperties.has('catalog') || changedProperties.has('modelDefaults'))
      && this.catalog.length > 0 && !this._modelId) {
      this._loadProviderDefaults();
    }
  }

  private _loadProviderDefaults() {
    const prov = this.catalog.find(p => p.id === this._selectedProviderId);
    if (prov) {
      const defaults = this.modelDefaults[prov.id];
      this._modelId = defaults?.defaultModel || prov.modelCatalog?.[0]?.id || '';
      this._baseUrl = '';
      this._targetUrl = '';
      this._discoveredModels = prov.modelCatalog || [];
      this._discoverModels();
    }
  }

  private async _discoverModels() {
    if (!this.client || !this._selectedProviderId) return;
    const seq = ++this._discoverSequence;
    this._isDiscoveringModels = true;
    try {
      const models = await this.client.getProviderModels(
        this._selectedProviderId,
        this._baseUrl || undefined,
        this._apiKey || undefined
      );
      if (seq === this._discoverSequence && models && models.length > 0) {
        this._discoveredModels = models;
      }
    } catch (_) {
      // Retain static fallback
    } finally {
      if (seq === this._discoverSequence) {
        this._isDiscoveringModels = false;
      }
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

  private _safeUUID(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return 'id-' + Math.random().toString(36).substring(2, 11) + '-' + Date.now().toString(36);
  }

  private _addConnection() {
    const provider = this.catalog.find(p => p.id === this._selectedProviderId);
    if (!this._modelId.trim()) return;
    if (provider?.requiresApiKey && !this._apiKey.trim()) return;
    if (this._selectedProviderId === 'generic-openai' && !this._isHttpUrl(this._targetUrl)) return;
    if (this._connectionValidationMessage()) return;

    const allowedHostsList = this._allowedHosts
      .split(',')
      .map(h => h.trim())
      .filter(h => h.length > 0);
    const noProxyHostsList = this._noProxyHosts
      .split(',')
      .map(h => h.trim())
      .filter(h => h.length > 0);

    let netProfileJson: string | undefined;
    if (this._showNetworkProfile) {
      const activeNetProfile: NetworkProfilePolicyDto = {
        egressMode: this._egressMode,
        allowedHosts: allowedHostsList,
        requireProxy: this._requireProxy,
        proxyUrl: this._requireProxy && this._proxyUrl.trim() ? this._proxyUrl.trim() : undefined,
        proxyCredentialVaultRef: this._requireProxy && this._proxyCredentialVaultRef.trim() ? this._proxyCredentialVaultRef.trim() : undefined,
        noProxyHosts: noProxyHostsList,
        connectTimeoutMs: this._connectTimeoutMs && this._connectTimeoutMs > 0 ? Number(this._connectTimeoutMs) : undefined,
        readTimeoutMs: this._readTimeoutMs && this._readTimeoutMs > 0 ? Number(this._readTimeoutMs) : undefined,
        tlsTrustMode: this._tlsTrustMode,
        customCaVaultRef: this._tlsTrustMode === 'CUSTOM_CA' && this._customCaVaultRef.trim() ? this._customCaVaultRef.trim() : undefined
      };

      const netJsonObj: Record<string, any> = {
        egressMode: activeNetProfile.egressMode || 'ALLOW_ALL',
        tlsTrustMode: activeNetProfile.tlsTrustMode || 'SYSTEM'
      };
      if (allowedHostsList.length > 0) netJsonObj.allowedHosts = allowedHostsList;
      if (activeNetProfile.requireProxy) {
        netJsonObj.requireProxy = true;
        if (activeNetProfile.proxyUrl) netJsonObj.proxyUrl = activeNetProfile.proxyUrl;
        if (activeNetProfile.proxyCredentialVaultRef) netJsonObj.proxyCredentialVaultRef = activeNetProfile.proxyCredentialVaultRef;
      }
      if (noProxyHostsList.length > 0) netJsonObj.noProxyHosts = noProxyHostsList;
      if (activeNetProfile.connectTimeoutMs) netJsonObj.connectTimeoutMs = activeNetProfile.connectTimeoutMs;
      if (activeNetProfile.readTimeoutMs) netJsonObj.readTimeoutMs = activeNetProfile.readTimeoutMs;
      if (activeNetProfile.customCaVaultRef) netJsonObj.customCaVaultRef = activeNetProfile.customCaVaultRef;
      netProfileJson = JSON.stringify(netJsonObj);
    }

    const connId = 'conn-' + this._safeUUID();
    const initialProfile: ModelProfileDraft = {
      id: 'prof-' + this._safeUUID(),
      connectionId: connId,
      modelId: this._modelId.trim(),
      structuredOutputMode: this._structuredOutputMode,
      enabled: true
    };

    const conn: ConnectionDraft = {
      id: connId,
      displayName: provider?.displayName || this._selectedProviderId,
      provider: this._selectedProviderId,
      apiKey: this._apiKey || undefined,
      apiKeyVaultRef: this._apiKey ? 'staged-ref-' + this._safeUUID() : undefined,
      baseUrl: this._baseUrl || undefined,
      targetUrl: this._selectedProviderId === 'generic-openai' ? this._targetUrl || undefined : undefined,
      modelId: this._modelId.trim(),
      contextWindow: Number(this._contextWindow),
      structuredOutputMode: this._structuredOutputMode,
      capabilities: this._capabilities,
      headroomEnabled: this._headroomEnabled,
      networkProfileJson: netProfileJson,
      profiles: [initialProfile]
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
    this._showNetworkProfile = false;
    this._egressMode = 'ALLOW_ALL';
    this._allowedHosts = '';
    this._requireProxy = false;
    this._proxyUrl = '';
    this._proxyCredentialVaultRef = '';
    this._noProxyHosts = '';
    this._connectTimeoutMs = undefined;
    this._readTimeoutMs = undefined;
    this._tlsTrustMode = 'SYSTEM';
    this._customCaVaultRef = '';
    this._loadProviderDefaults();
  }

  private _isHttpUrl(value: string): boolean {
    try {
      const url = new URL(value.trim());
      return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname.length > 0;
    } catch (_) {
      return false;
    }
  }

  private _connectionValidationMessage(): string {
    const provider = this.catalog.find(p => p.id === this._selectedProviderId);
    if (provider?.requiresApiKey && !this._apiKey.trim()) return 'An API key is required for this provider.';
    if (this._selectedProviderId === 'generic-openai' && !this._isHttpUrl(this._targetUrl)) {
      return 'Generic OpenAI requires a valid HTTP(S) target URL.';
    }
    if (this._requireProxy && (!this._proxyUrl.trim() || !this._isHttpUrl(this._proxyUrl))) {
      return 'Proxy URL must be a valid HTTP or HTTPS URI when proxy is enabled.';
    }
    if (this._tlsTrustMode === 'CUSTOM_CA' && !this._customCaVaultRef.trim()) {
      return 'Custom CA Vault Reference is required when CUSTOM_CA mode is selected.';
    }
    if (this._egressMode === 'ALLOW_LIST' && !this._allowedHosts.trim()) {
      return 'Allowed hosts cannot be empty when egress mode is ALLOW_LIST.';
    }
    if (this._connectTimeoutMs !== undefined && (this._connectTimeoutMs < 0 || this._connectTimeoutMs > 300000)) {
      return 'Connect timeout must be between 0 and 300,000 ms.';
    }
    if (this._readTimeoutMs !== undefined && (this._readTimeoutMs < 0 || this._readTimeoutMs > 300000)) {
      return 'Read timeout must be between 0 and 300,000 ms.';
    }
    return '';
  }

  private _startAddProfile(conn: ConnectionDraft) {
    if (!conn.id) {
      conn.id = 'conn-' + this._safeUUID();
    }
    this._addingProfileConnId = conn.id;
    this._newProfileModelId = '';
  }

  private _cancelAddProfile() {
    this._addingProfileConnId = null;
    this._newProfileModelId = '';
  }

  private _commitAddProfile(conn: ConnectionDraft) {
    const model = this._newProfileModelId.trim();
    if (!model) return;
    const connId = conn.id || ('conn-' + this._safeUUID());
    const newProfile: ModelProfileDraft = {
      id: 'prof-' + this._safeUUID(),
      connectionId: connId,
      modelId: model,
      structuredOutputMode: 'NATIVE',
      enabled: true
    };
    const currentProfiles = conn.profiles && conn.profiles.length > 0
      ? conn.profiles
      : (conn.modelId ? [{
          id: 'prof-' + this._safeUUID(),
          connectionId: connId,
          modelId: conn.modelId,
          structuredOutputMode: conn.structuredOutputMode || 'NATIVE',
          enabled: true
        }] : []);
    const updated = this.connections.map(c => {
      if (c === conn || (c.id && c.id === connId)) {
        return { ...c, id: connId, profiles: [...currentProfiles, newProfile] };
      }
      return c;
    });
    this._addingProfileConnId = null;
    this._newProfileModelId = '';
    this.dispatchEvent(
      new CustomEvent('update-connections', {
        detail: { connections: updated },
        bubbles: true,
        composed: true
      })
    );
  }

  private _removeProfileFromConnection(conn: ConnectionDraft, profileId: string) {
    if (!conn || !conn.profiles || conn.profiles.length <= 1) return;
    const updatedProfiles = conn.profiles.filter(p => p.id !== profileId);
    const updated = this.connections.map(c => {
      if (c === conn || (c.id && c.id === conn.id)) {
        return { ...c, profiles: updatedProfiles };
      }
      return c;
    });
    this.dispatchEvent(
      new CustomEvent('update-connections', {
        detail: { connections: updated },
        bubbles: true,
        composed: true
      })
    );
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
    const activeProvider: Partial<ProviderDescriptor> = this.catalog.find(p => p.id === this._selectedProviderId) || {};
    const validationMessage = this._connectionValidationMessage() || (!this._modelId.trim() ? 'A model identifier is required.' : '');

    return html`
      <div class="connections-header">
        <div class="connections-title">Configured LLM Connections</div>
      </div>

      <div class="connection-list">
        ${this.connections.length === 0
          ? html`<div style="font-size:12px;color:var(--text-muted);text-align:center;padding:var(--space-4)">No connections configured yet.</div>`
          : this.connections.map((c, idx) => {
          const prov: Partial<ProviderDescriptor> = this.catalog.find(p => p.id === c.provider) || {};
              return html`
                <div class="connection-row" style="flex-direction:column;align-items:stretch;gap:var(--space-2)">
                  <div style="display:flex;align-items:center;justify-content:space-between">
                    <div class="connection-info">
                      <div class="provider-icon" style="background:${prov.attributes?.color || '#888'}22;color:${prov.attributes?.color || '#888'}">
                        ${prov.attributes?.icon || '?'}
                      </div>
                      <div>
                        <div class="provider-name">${c.displayName || prov.displayName || c.provider}</div>
                        <div class="provider-tag">${c.modelId ? `${c.modelId} (${(c.contextWindow || 8192).toLocaleString()} ctx)` : (c.baseUrl || 'Configured Endpoint')}</div>
                      </div>
                    </div>
                    <div style="display:flex;gap:var(--space-2);align-items:center">
                      <button class="mode-toggle" style="font-size:11px;padding:2px 6px" @click="${() => this._startAddProfile(c)}">+ Add Profile</button>
                      <button class="mcp-remove" @click="${() => this._removeConnection(idx)}">&times;</button>
                    </div>
                  </div>
                  ${c.profiles && c.profiles.length > 1 ? html`
                    <div style="display:flex;flex-direction:column;gap:4px;margin-left:var(--space-8);padding-left:var(--space-2);border-left:2px solid var(--border)">
                      ${c.profiles.map(p => html`
                        <div style="display:flex;align-items:center;justify-content:space-between;font-size:12px;color:var(--text-secondary)">
                          <span><strong>${p.modelId}</strong> (${p.structuredOutputMode || 'NATIVE'})</span>
                          <button class="mcp-remove" style="font-size:14px" title="Remove profile" aria-label="Remove profile ${p.modelId}" @click="${() => this._removeProfileFromConnection(c, p.id)}">&times;</button>
                        </div>
                      `)}
                    </div>
                  ` : ''}
                  ${this._addingProfileConnId === c.id ? html`
                    <div style="display:flex;gap:var(--space-2);align-items:center;margin-left:var(--space-8);padding-top:var(--space-1)">
                      <input class="field-input" style="font-size:12px;padding:4px 8px;flex:1"
                             placeholder="e.g. gpt-4o-mini"
                             .value="${this._newProfileModelId}"
                             @input="${(ev: Event) => this._newProfileModelId = (ev.target as HTMLInputElement).value}"
                             @keydown="${(ev: KeyboardEvent) => { if (ev.key === 'Enter') this._commitAddProfile(c); if (ev.key === 'Escape') this._cancelAddProfile(); }}" />
                      <button class="mode-toggle" style="font-size:11px;padding:4px 8px" @click="${() => this._commitAddProfile(c)}">Add</button>
                      <button class="mode-toggle" style="font-size:11px;padding:4px 8px;opacity:0.7" @click="${() => this._cancelAddProfile()}">Cancel</button>
                    </div>
                  ` : ''}
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
            <div class="provider-icon" style="background:${p.attributes?.color || '#888'}22;color:${p.attributes?.color || '#888'}">
              ${p.attributes?.icon || '?'}
              </div>
              <div>
              <div class="provider-name">${p.displayName}</div>
              <div class="provider-tag">${p.attributes?.tag || ''}</div>
              </div>
            </div>
          `)}
        </div>

        <div class="grid-2">
          <div>
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:var(--space-1)">
              <label class="field-label" for="draft-model-id" style="margin:0">Model Identifier</label>
              <button type="button" class="mode-toggle" style="font-size:11px;cursor:pointer;padding:0;color:var(--accent)"
                      @click="${() => this._discoverModels()}"
                      ?disabled="${this._isDiscoveringModels}">
                ${this._isDiscoveringModels ? 'Discovering...' : 'Discover Models'}
              </button>
            </div>
            <span style="position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0;" aria-live="polite" id="model-discovery-status">
              ${this._isDiscoveringModels ? 'Discovering available models from provider...' : ''}
            </span>
            <input class="field-input" id="draft-model-id" type="text" list="discovered-models-list" .value="${this._modelId}"
                   placeholder="e.g. gpt-4o-mini"
                   @input="${(e: any) => (this._modelId = e.target.value)}" />
            <datalist id="discovered-models-list">
              ${this._discoveredModels.map(m => html`<option value="${m.id}">${m.label && m.label !== m.id ? m.label : m.id}</option>`)}
            </datalist>
        ${this._selectedProviderId === 'groq' ? html`
          <p style="font-size:12px;margin-top:var(--space-1)">
            Choose a model your Groq account can access. If discovery does not list it, enter its model ID.
            <a href="https://console.groq.com/docs/models.md" target="_blank" rel="noopener noreferrer">Groq model docs</a>.
          </p>
        ` : ''}
          </div>
          <div>
            <label class="field-label" for="draft-context-window">Context Window (tokens)</label>
            <input class="field-input" id="draft-context-window" type="number" .value="${this._contextWindow}"
                   @input="${(e: any) => (this._contextWindow = e.target.value)}" />
          </div>
        </div>

        ${activeProvider.requiresApiKey
          ? html`
              <div style="margin-bottom:var(--space-4)">
                <label class="field-label" for="draft-api-key">API Key</label>
                <input class="field-input" id="draft-api-key" type="password" placeholder="Enter provider API key"
                       .value="${this._apiKey}" @input="${(e: any) => (this._apiKey = e.target.value)}" />
              </div>
            `
          : ''}

        ${activeProvider.authScheme === 'CLI_OAUTH'
          ? html`
              <p class="status-msg" role="note" style="margin-bottom:var(--space-4)">
                This provider uses CLI OAuth. The web wizard cannot open the provider login flow. Complete the
                provider's supported CLI login on the Amsha host first, then select the provider here. The server
                uses its local OAuth credential; it is not copied into this browser draft.
              </p>
            `
          : ''}

        ${(activeProvider.defaultBaseUrl || activeProvider.attributes?.hasBaseUrl === 'true')
          ? html`
              <div style="margin-bottom:var(--space-4)">
                <label class="field-label" for="draft-base-url">Base URL Override (Optional)</label>
                <input class="field-input" id="draft-base-url" type="text" placeholder="${activeProvider.defaultBaseUrl || ''}"
                       .value="${this._baseUrl}" @input="${(e: any) => (this._baseUrl = e.target.value)}" />
              </div>
            `
          : ''}

        ${this._selectedProviderId === 'generic-openai'
          ? html`
              <div style="margin-bottom:var(--space-4)">
                <label class="field-label" for="draft-target-url">Target URL (generic-openai)</label>
                <input class="field-input" id="draft-target-url" type="text" placeholder="https://api.customprovider.com/v1/chat/completions"
                       .value="${this._targetUrl}" @input="${(e: any) => (this._targetUrl = e.target.value)}" />
              </div>
            `
          : ''}

        <div class="grid-2">
          <div>
            <label class="field-label" for="draft-structured-mode">Structured Output Mode</label>
            <select class="field-select" id="draft-structured-mode" .value="${this._structuredOutputMode}"
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

        <div style="margin-bottom:var(--space-4)">
          <button type="button" class="mode-toggle" id="toggle-network-profile-btn"
                  aria-expanded="${this._showNetworkProfile}"
                  aria-controls="network-profile-section"
                  style="font-size:12px;color:var(--accent);cursor:pointer;background:none;border:none;padding:0;text-decoration:underline;"
                  @click="${() => (this._showNetworkProfile = !this._showNetworkProfile)}">
            ${this._showNetworkProfile ? '▼ Hide Network Profile & Egress Settings' : '▶ Advanced Network Profile & Egress Settings'}
          </button>
        </div>

        ${this._showNetworkProfile
          ? html`
              <div class="network-profile-section" id="network-profile-section" style="background:var(--bg-elevated);padding:var(--space-4);border-radius:var(--radius-md);border:1px solid var(--border);margin-bottom:var(--space-4);">
                <div class="grid-2">
                  <div>
                    <label class="field-label" for="draft-egress-mode">Outbound Egress Mode</label>
                    <select class="field-select" id="draft-egress-mode" .value="${this._egressMode}"
                            @change="${(e: any) => (this._egressMode = e.target.value)}">
                      <option value="ALLOW_ALL">Allow Outbound (All Hosts)</option>
                      <option value="ALLOW_LIST">Allow List</option>
                      <option value="DENY_ALL">Deny All Outbound</option>
                    </select>
                  </div>
                  <div>
                    <label class="field-label" for="draft-tls-mode">TLS Trust Posture</label>
                    <select class="field-select" id="draft-tls-mode" .value="${this._tlsTrustMode}"
                            @change="${(e: any) => (this._tlsTrustMode = e.target.value)}">
                      <option value="SYSTEM">SYSTEM (Default JVM Store)</option>
                      <option value="CUSTOM_CA">CUSTOM_CA (Enterprise Vault CA)</option>
                    </select>
                  </div>
                </div>

                ${this._egressMode === 'ALLOW_LIST'
                  ? html`
                      <div style="margin-bottom:var(--space-3)">
                        <label class="field-label" for="draft-allowed-hosts">Allowed Hosts</label>
                        <input class="field-input" id="draft-allowed-hosts" type="text"
                               placeholder="api.openai.com, api.groq.com"
                               .value="${this._allowedHosts}"
                               @input="${(e: any) => (this._allowedHosts = e.target.value)}" />
                      </div>
                    `
                  : ''}

                ${this._tlsTrustMode === 'CUSTOM_CA'
                  ? html`
                      <div style="margin-bottom:var(--space-3)">
                        <label class="field-label" for="draft-custom-ca-ref">Custom CA Vault Reference</label>
                        <input class="field-input" id="draft-custom-ca-ref" type="text"
                               placeholder="vault-ca-cert-ref"
                               .value="${this._customCaVaultRef}"
                               @input="${(e: any) => (this._customCaVaultRef = e.target.value)}" />
                      </div>
                    `
                  : ''}

                <div class="grid-2">
                  <div>
                    <label class="field-label" for="draft-connect-timeout">Connect Timeout (ms)</label>
                    <input class="field-input" id="draft-connect-timeout" type="number" min="0" max="300000"
                           placeholder="Default (e.g. 5000)"
                           .value="${this._connectTimeoutMs ?? ''}"
                           @input="${(e: any) => (this._connectTimeoutMs = e.target.value ? Number(e.target.value) : undefined)}" />
                  </div>
                  <div>
                    <label class="field-label" for="draft-read-timeout">Read Timeout (ms)</label>
                    <input class="field-input" id="draft-read-timeout" type="number" min="0" max="300000"
                           placeholder="Default (e.g. 30000)"
                           .value="${this._readTimeoutMs ?? ''}"
                           @input="${(e: any) => (this._readTimeoutMs = e.target.value ? Number(e.target.value) : undefined)}" />
                  </div>
                </div>

                <div style="margin-top:var(--space-2);margin-bottom:var(--space-3)">
                  <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-primary);cursor:pointer">
                    <input type="checkbox" id="draft-require-proxy" ?checked="${this._requireProxy}"
                           @change="${(e: any) => (this._requireProxy = e.target.checked)}" />
                    Require Egress Proxy
                  </label>
                </div>

                ${this._requireProxy
                  ? html`
                      <div class="grid-2">
                        <div>
                          <label class="field-label" for="draft-proxy-url">Proxy URL</label>
                          <input class="field-input" id="draft-proxy-url" type="url"
                                 placeholder="http://proxy.internal:3128"
                                 .value="${this._proxyUrl}"
                                 @input="${(e: any) => (this._proxyUrl = e.target.value)}" />
                        </div>
                        <div>
                          <label class="field-label" for="draft-proxy-vault-ref">Proxy Credential Vault Ref</label>
                          <input class="field-input" id="draft-proxy-vault-ref" type="text"
                                 placeholder="vault-proxy-ref"
                                 .value="${this._proxyCredentialVaultRef}"
                                 @input="${(e: any) => (this._proxyCredentialVaultRef = e.target.value)}" />
                        </div>
                      </div>
                      <div style="margin-top:var(--space-2)">
                        <label class="field-label" for="draft-no-proxy-hosts">No-Proxy Host Patterns</label>
                        <input class="field-input" id="draft-no-proxy-hosts" type="text"
                               placeholder="localhost, *.internal"
                               .value="${this._noProxyHosts}"
                               @input="${(e: any) => (this._noProxyHosts = e.target.value)}" />
                      </div>
                    `
                  : ''}
              </div>
            `
          : ''}

  ${validationMessage
    ? html`<div style="color:var(--text-danger);font-size:12px;margin-bottom:var(--space-2)">${validationMessage}</div>`
    : ''}
  <button class="btn-ghost" style="width:100%" ?disabled="${!!validationMessage}" @click="${this._addConnection}">
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
