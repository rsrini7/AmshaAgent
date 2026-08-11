import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { SettingsDto } from '../api/types';


@customElement('arc-settings')
export class ArcSettings extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @state() private _activeTab = 'prompt';
  @state() private _systemPrompt = '';
  @state() private _appVersion = '';

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

    .field-label { display: block; font-size: var(--font-size-xs); font-weight: var(--font-weight-semi); text-transform: uppercase; color: var(--text-muted); }
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
      // Load the full system prompt via the dedicated endpoint.
      // getSettings returns displayPrompt truncated to 199 chars;
      // saving a truncated prompt back would corrupt the vault.
      try {
        const promptData = await this._client.getPrompt();
        this._systemPrompt = promptData.systemPrompt;
      } catch {
        this._systemPrompt = data.systemPrompt;
      }
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
        <div class="tab ${this._activeTab === 'prompt' ? 'active' : ''}" @click="${() => this._activeTab = 'prompt'}">System Prompt</div>
        <div class="tab ${this._activeTab === 'danger' ? 'active' : ''}" @click="${() => this._activeTab = 'danger'}">Danger Zone</div>
      </div>

      ${this._error ? html`<div class="status-msg error">${this._error}</div>` : ''}
      ${this._success ? html`<div class="status-msg success">${this._success}</div>` : ''}
      ${this._renderConfigurationSummary()}

      ${this._activeTab === 'prompt' ? this._renderPrompt() : ''}
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
}
