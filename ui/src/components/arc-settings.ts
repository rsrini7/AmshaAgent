import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { SettingsDto, NetworkProfilePolicyDto, EgressMode } from '../api/types';

@customElement('arc-settings')
export class ArcSettings extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @state() private _activeTab = 'prompt';
  @state() private _systemPrompt = '';
  @state() private _appVersion = '';
  @state() private _voice = 'default';
  @state() private _customVoice = '';
  @state() private _speechOutputMode = 'default';
  @state() private _availableVoices: string[] = ['default', 'vivian', 'serena', 'eric', 'ryan', 'alloy', 'onyx'];

  @state() private _egressMode: EgressMode = 'ALLOW_ALL';
  @state() private _allowedHosts = '';
  @state() private _requireProxy = false;
  @state() private _proxyUrl = '';
  @state() private _proxyCredentialVaultRef = '';
  @state() private _noProxyHosts = '';
  @state() private _connectTimeoutMs: number | undefined = undefined;
  @state() private _readTimeoutMs: number | undefined = undefined;
  @state() private _tlsTrustMode = 'SYSTEM';
  @state() private _customCaVaultRef = '';
  @state() private _networkProfileLoadError = false;

  private get _client(): ArcClient {
    return new ArcClient('/api/v1', this._appContext?.apiKey || '');
  }
  @state() private _loading = false;
  @state() private _error = '';
  @state() private _success = '';
  @state() private _skipOnboarding = false;
  @state() private _settings: SettingsDto | null = null;

  static styles = css`
    :host {
      display: block;
      padding: var(--space-6);
      max-width: 800px;
      margin: 0 auto;
      box-sizing: border-box;
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

    .field-group {
      display: flex; flex-direction: column; gap: var(--space-2);
    }
    .field-label { display: block; font-size: var(--font-size-xs); font-weight: var(--font-weight-semi); text-transform: uppercase; color: var(--text-muted); }
    .field-hint { font-size: var(--font-size-xs); color: var(--text-muted); }
    .field-select, .field-input {
      width: 100%; max-width: 400px; padding: var(--space-3) var(--space-4); background: var(--bg-elevated);
      border: 1.5px solid var(--border); border-radius: var(--radius-md);
      color: var(--text-primary); font-family: var(--font-family); font-size: var(--font-size-sm);
      box-sizing: border-box;
    }
    .field-select:focus, .field-input:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 3px var(--accent-glow); }

    .field-textarea {
      width: 100%; padding: var(--space-3) var(--space-4); background: var(--bg-elevated);
      border: 1.5px solid var(--border); border-radius: var(--radius-md);
      color: var(--text-primary); font-family: var(--font-family); font-size: var(--font-size-sm);
      box-sizing: border-box; min-height: 150px; resize: vertical;
    }
    .field-textarea:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 3px var(--accent-glow); }

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
    await this._loadSettings();
  }

  private async _loadSettings() {
    try {
      this._loading = true;
      const data = await this._client.getSettings();
      this._settings = data;
      this._appVersion = data.appVersion;
      this._skipOnboarding = data.skipOnboarding;
      if (data.availableVoices && data.availableVoices.length > 0) {
        this._availableVoices = data.availableVoices;
      }
      if (data.voice && this._availableVoices.includes(data.voice)) {
        this._voice = data.voice;
      } else {
        this._voice = 'default';
      }
      if (data.speechOutputMode) {
        this._speechOutputMode = data.speechOutputMode;
      } else {
        this._speechOutputMode = 'default';
      }
      // Load the full system prompt via the dedicated endpoint.
      try {
        const promptData = await this._client.getPrompt();
        this._systemPrompt = promptData.systemPrompt;
      } catch {
        this._systemPrompt = data.systemPrompt;
      }

      // Load the network profile policy.
      try {
        const net = await this._client.getConnectionNetworkProfile();
        if (net) {
          this._egressMode = net.egressMode || 'ALLOW_ALL';
          this._allowedHosts = (net.allowedHosts || []).join(', ');
          this._requireProxy = !!net.requireProxy;
          this._proxyUrl = net.proxyUrl || '';
          this._proxyCredentialVaultRef = net.proxyCredentialVaultRef || '';
          this._noProxyHosts = (net.noProxyHosts || []).join(', ');
          this._connectTimeoutMs = net.connectTimeoutMs;
          this._readTimeoutMs = net.readTimeoutMs;
          this._tlsTrustMode = net.tlsTrustMode || 'SYSTEM';
          this._customCaVaultRef = net.customCaVaultRef || '';
          this._networkProfileLoadError = false;
        }
      } catch (e: any) {
        this._networkProfileLoadError = true;
        this._error = 'Failed to load network profile policy: ' + (e.message || 'Unknown error');
      }
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private _isHttpUrl(url: string): boolean {
    try {
      const parsed = new URL(url);
      return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && !!parsed.host;
    } catch {
      return false;
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

  private async _saveVoice() {
    try {
      this._loading = true; this._error = ''; this._success = '';
      await this._client.patchVoice(this._voice, this._speechOutputMode);
      this._success = 'Speech & voice settings updated successfully.';
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _saveNetworkProfile() {
    try {
      this._loading = true; this._error = ''; this._success = '';
      if (this._networkProfileLoadError) {
        throw new Error('Cannot save network profile: existing profile failed to load');
      }
      if (this._egressMode === 'DENY_ALL') {
        if (!confirm('Warning: Setting egress mode to DENY_ALL will block all external network access for this connection. Continue?')) {
          this._loading = false;
          return;
        }
      }
      if (this._connectTimeoutMs !== undefined && (!Number.isFinite(this._connectTimeoutMs) || this._connectTimeoutMs < 0 || this._connectTimeoutMs > 300000)) {
        throw new Error('Connect timeout must be between 0 and 300,000 ms');
      }
      if (this._readTimeoutMs !== undefined && (!Number.isFinite(this._readTimeoutMs) || this._readTimeoutMs < 0 || this._readTimeoutMs > 300000)) {
        throw new Error('Read timeout must be between 0 and 300,000 ms');
      }
      if (this._requireProxy) {
        if (!this._proxyUrl.trim()) {
          throw new Error('Proxy URL is required when proxy is enabled');
        }
        if (!this._isHttpUrl(this._proxyUrl.trim())) {
          throw new Error('Proxy URL must be an absolute http:// or https:// URL');
        }
      }
      if (this._tlsTrustMode === 'CUSTOM_CA' && !this._customCaVaultRef.trim()) {
        throw new Error('Custom CA Vault Reference is required for CUSTOM_CA mode');
      }

      const allowedHostsList = this._allowedHosts
        .split(',')
        .map(h => h.trim())
        .filter(h => h.length > 0);
      const noProxyHostsList = this._noProxyHosts
        .split(',')
        .map(h => h.trim())
        .filter(h => h.length > 0);

      if (this._egressMode === 'ALLOW_LIST' && allowedHostsList.length === 0) {
        throw new Error('Allowed hosts list cannot be empty when egress mode is ALLOW_LIST');
      }

      const profile: NetworkProfilePolicyDto = {
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

      await this._client.patchConnectionNetworkProfile(-1, profile);
      this._success = 'Network profile policy updated successfully.';
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
      
      <div class="tabs" role="tablist" aria-label="Settings tabs">
        <div class="tab ${this._activeTab === 'prompt' ? 'active' : ''}" role="tab" id="tab-prompt" aria-selected="${this._activeTab === 'prompt'}" aria-controls="panel-prompt" tabindex="${this._activeTab === 'prompt' ? '0' : '-1'}" @click="${() => this._activeTab = 'prompt'}" @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this._activeTab = 'prompt'; } }}">System Prompt</div>
        <div class="tab ${this._activeTab === 'voice' ? 'active' : ''}" role="tab" id="tab-voice" aria-selected="${this._activeTab === 'voice'}" aria-controls="panel-voice" tabindex="${this._activeTab === 'voice' ? '0' : '-1'}" @click="${() => this._activeTab = 'voice'}" @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this._activeTab = 'voice'; } }}">Speech & Voice</div>
        <div class="tab ${this._activeTab === 'network' ? 'active' : ''}" role="tab" id="tab-network" aria-selected="${this._activeTab === 'network'}" aria-controls="panel-network" tabindex="${this._activeTab === 'network' ? '0' : '-1'}" @click="${() => this._activeTab = 'network'}" @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this._activeTab = 'network'; } }}">Network Policy</div>
        <div class="tab ${this._activeTab === 'danger' ? 'active' : ''}" role="tab" id="tab-danger" aria-selected="${this._activeTab === 'danger'}" aria-controls="panel-danger" tabindex="${this._activeTab === 'danger' ? '0' : '-1'}" @click="${() => this._activeTab = 'danger'}" @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this._activeTab = 'danger'; } }}">Danger Zone</div>
      </div>

      ${this._error ? html`<div class="status-msg error">${this._error}</div>` : ''}
      ${this._success ? html`<div class="status-msg success">${this._success}</div>` : ''}
      ${this._renderConfigurationSummary()}

      ${this._activeTab === 'prompt' ? this._renderPrompt() : ''}
      ${this._activeTab === 'voice' ? this._renderVoice() : ''}
      ${this._activeTab === 'network' ? this._renderNetworkPolicy() : ''}
      ${this._activeTab === 'danger' ? this._renderDanger() : ''}
    `;
  }

  private _renderConfigurationSummary() {
    if (!this._settings) return '';
    return html`
      <div class="status-msg" style="line-height:1.6;margin-bottom:var(--space-4)">
        <strong>Effective LLM configuration</strong><br />
        Provider: ${this._settings.provider || 'not configured'} · Model: ${this._settings.model || 'default'}<br />
        Source: ${this._settings.effectiveSource || 'unknown'} · Credential: ${this._settings.credentialState || 'unknown'} (${this._settings.credentialSource || 'unknown'})
        ${this._settings.vaultPriority
          ? html` · Vault priority: enabled`
          : html` · Vault priority: disabled (environment configuration remains active)`}
      </div>
    `;
  }

  private _renderPrompt() {
    return html`
      <div class="section">
        <span class="field-label">System Prompt</span>
        <textarea class="field-textarea" .value="${this._systemPrompt}" @input="${(e: any) => this._systemPrompt = e.target.value}"></textarea>
        <div style="margin-top:var(--space-4)">
          <button class="btn-primary" ?disabled="${this._loading}" @click="${() => this._savePrompt()}">Save Prompt</button>
        </div>
      </div>
    `;
  }

  private _renderVoice() {
    const voiceLabels: Record<string, string> = {
      default: '🎲 Default (Random / Model Neutral)',
      vivian: '👩 Vivian (Female 1)',
      serena: '👩 Serena (Female 2)',
      eric: '👨 Eric (Male 1)',
      ryan: '👨 Ryan (Male 2)',
      alloy: '🎙️ Alloy (Neutral / Standard)',
      onyx: '🎙️ Onyx (Deep Male)'
    };

    return html`
      <div class="section">
        <div class="field-group">
          <span class="field-label">Synthesized Voice</span>
          <span class="field-hint">Select the TTS voice timbre and speaker identity for audio responses.</span>
          <select class="field-select" .value="${this._voice}" @change="${(e: any) => this._voice = e.target.value}">
            ${this._availableVoices.map(v => html`
              <option value="${v}">${voiceLabels[v] || v}</option>
            `)}
          </select>
        </div>

        <div class="field-group" style="margin-top:var(--space-2)">
          <span class="field-label">Default Speech Output Mode</span>
          <span class="field-hint">Controls when audio voice replies are synthesized.</span>
          <select class="field-select" .value="${this._speechOutputMode}" @change="${(e: any) => this._speechOutputMode = e.target.value}">
            <option value="default">🎲 System Default (Follow Server Policy)</option>
            <option value="TEXT_ONLY">💬 Text Only</option>
            <option value="TEXT_AND_AUDIO">🔊 Text and Spoken Voice</option>
            <option value="AUDIO_ONLY">🎙️ Spoken Voice Only</option>
          </select>
        </div>

        <div style="margin-top:var(--space-4)">
          <button class="btn-primary" ?disabled="${this._loading}" @click="${() => this._saveVoice()}">Save Speech & Voice Settings</button>
        </div>
      </div>
    `;
  }

  private _renderDanger() {
    if (this._skipOnboarding) {
      return html`
        <div class="section">
              <p style="color:var(--text-muted);font-size:var(--font-size-sm);">Onboarding is pre-configured and managed externally. Reset is not available.</p>
        </div>
      `;
    }
    return html`
      <div class="section">
        <p style="color:var(--text-muted);font-size:var(--font-size-sm);line-height:1.5;">Resetting onboarding will clear your session and force you to re-run the wizard. Your existing API keys are not deleted until overwritten.</p>
        <div style="margin-top:var(--space-4)">
          <button class="btn-danger" ?disabled="${this._loading}" @click="${() => this._reset()}">Reset & Re-Onboard</button>
        </div>
      </div>
    `;
  }

  private _renderNetworkPolicy() {
    return html`
      <div class="section" role="form" aria-label="Network Profile & Egress Policy">
        <div class="field-group">
          <label class="field-label" for="net-egress-mode">Outbound Egress Mode</label>
          <span class="field-hint" id="hint-net-egress-mode">Policy enforcement governing outbound network calls for this connection.</span>
          <select class="field-select" id="net-egress-mode" aria-describedby="hint-net-egress-mode" .value="${this._egressMode}"
                  @change="${(e: any) => this._egressMode = e.target.value as EgressMode}">
            <option value="ALLOW_ALL">Allow Outbound (All Hosts)</option>
            <option value="ALLOW_LIST">Allow List (Permit only specific hostnames)</option>
            <option value="DENY_ALL">Deny All (Block all external egress)</option>
          </select>
        </div>

        ${this._egressMode === 'ALLOW_LIST' ? html`
          <div class="field-group">
            <label class="field-label" for="net-allowed-hosts">Allowed Hosts</label>
            <span class="field-hint" id="hint-net-allowed-hosts">Comma-separated hostnames permitted for outbound egress (e.g. api.openai.com, api.groq.com).</span>
            <input class="field-input" id="net-allowed-hosts" type="text" aria-describedby="hint-net-allowed-hosts"
                   placeholder="api.openai.com, api.groq.com"
                   .value="${this._allowedHosts}"
                   @input="${(e: any) => this._allowedHosts = e.target.value}" />
          </div>
        ` : ''}

        <div class="field-group">
          <label style="display:flex;align-items:center;gap:var(--space-2);cursor:pointer;">
            <input type="checkbox" id="net-require-proxy" aria-describedby="hint-net-require-proxy" ?checked="${this._requireProxy}"
                   @change="${(e: any) => this._requireProxy = e.target.checked}" />
            <span class="field-label" style="margin:0">Require Egress Proxy</span>
          </label>
          <span class="field-hint" id="hint-net-require-proxy">Route outbound requests through a corporate HTTP/HTTPS proxy.</span>
        </div>

        ${this._requireProxy ? html`
          <div class="field-group">
            <label class="field-label" for="net-proxy-url">Proxy URL</label>
            <input class="field-input" id="net-proxy-url" type="url"
                   placeholder="http://proxy.internal:3128"
                   .value="${this._proxyUrl}"
                   @input="${(e: any) => this._proxyUrl = e.target.value}" />
          </div>

          <div class="field-group">
            <label class="field-label" for="net-proxy-vault-ref">Proxy Credential Vault Reference (Optional)</label>
            <span class="field-hint" id="hint-net-proxy-vault-ref">Secret reference identifier stored in the secret vault.</span>
            <input class="field-input" id="net-proxy-vault-ref" type="text" aria-describedby="hint-net-proxy-vault-ref"
                   placeholder="vault-proxy-credential-ref"
                   .value="${this._proxyCredentialVaultRef}"
                   @input="${(e: any) => this._proxyCredentialVaultRef = e.target.value}" />
          </div>

          <div class="field-group">
            <label class="field-label" for="net-no-proxy-hosts">No-Proxy Host Patterns</label>
            <span class="field-hint" id="hint-net-no-proxy-hosts">Comma-separated list of host patterns that bypass proxy (e.g. localhost, *.internal).</span>
            <input class="field-input" id="net-no-proxy-hosts" type="text" aria-describedby="hint-net-no-proxy-hosts"
                   placeholder="localhost, *.internal"
                   .value="${this._noProxyHosts}"
                   @input="${(e: any) => this._noProxyHosts = e.target.value}" />
          </div>
        ` : ''}

        <div style="display:grid;grid-template-columns:1fr 1fr;gap:var(--space-4);max-width:400px;">
          <div class="field-group">
            <label class="field-label" for="net-connect-timeout">Connect Timeout (ms)</label>
            <input class="field-input" id="net-connect-timeout" type="number" min="0" max="300000"
                   placeholder="Default (e.g. 5000)"
                   .value="${this._connectTimeoutMs ?? ''}"
                   @input="${(e: any) => this._connectTimeoutMs = e.target.value ? Number(e.target.value) : undefined}" />
          </div>
          <div class="field-group">
            <label class="field-label" for="net-read-timeout">Read Timeout (ms)</label>
            <input class="field-input" id="net-read-timeout" type="number" min="0" max="300000"
                   placeholder="Default (e.g. 30000)"
                   .value="${this._readTimeoutMs ?? ''}"
                   @input="${(e: any) => this._readTimeoutMs = e.target.value ? Number(e.target.value) : undefined}" />
          </div>
        </div>

        <div class="field-group">
          <label class="field-label" for="net-tls-mode">TLS Trust Posture</label>
          <span class="field-hint" id="hint-net-tls-mode">Enforce production TLS validation using the JVM system store or a custom enterprise CA.</span>
          <select class="field-select" id="net-tls-mode" aria-describedby="hint-net-tls-mode" .value="${this._tlsTrustMode}"
                  @change="${(e: any) => this._tlsTrustMode = e.target.value}">
            <option value="SYSTEM">SYSTEM (Default JVM Trust Store)</option>
            <option value="CUSTOM_CA">CUSTOM_CA (Enterprise Private CA from Vault)</option>
          </select>
        </div>

        ${this._tlsTrustMode === 'CUSTOM_CA' ? html`
          <div class="field-group">
            <label class="field-label" for="net-custom-ca-ref">Custom CA Vault Reference</label>
            <span class="field-hint" id="hint-net-custom-ca-ref">Vault reference identifier pointing to the trusted CA certificate bundle.</span>
            <input class="field-input" id="net-custom-ca-ref" type="text" aria-describedby="hint-net-custom-ca-ref"
                   placeholder="vault-ca-cert-ref"
                   .value="${this._customCaVaultRef}"
                   @input="${(e: any) => this._customCaVaultRef = e.target.value}" />
          </div>
        ` : ''}

        <div style="margin-top:var(--space-4)">
          <button class="btn-primary" id="save-network-policy-btn" ?disabled="${this._loading || this._networkProfileLoadError}"
                  @click="${() => this._saveNetworkProfile()}">
            Save Network Policy
          </button>
        </div>
      </div>
    `;
  }
}
