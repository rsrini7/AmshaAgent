import { LitElement, css, html, nothing, PropertyValues } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ArcClient, ArcApiError, CockpitHealthResponse } from '../api/client';

@customElement('arc-health-dashboard')
export class ArcHealthDashboard extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @state() private _loading = false;
  @state() private _error: string | null = null;
  @state() private _authStatus: 401 | 403 | null = null;
  @state() private _health: CockpitHealthResponse | null = null;
  @state() private _autoRefresh = true;
  @state() private _providerFilter: 'all' | 'configured' | 'not_configured' = 'all';
  @state() private _providerSearch = '';

  private _refreshTimer: ReturnType<typeof setInterval> | null = null;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      padding: var(--space-6, 24px);
      box-sizing: border-box;
      gap: var(--space-5, 20px);
      background: var(--bg-base, #0a0b0f);
      color: var(--text-primary, #ffffff);
      overflow-y: auto;
    }

    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: 12px;
    }

    .title-group h2 {
      margin: 0 0 4px 0;
      font-size: 1.25rem;
      font-weight: 700;
      letter-spacing: -0.02em;
    }

    .subtitle {
      margin: 0;
      font-size: 12px;
      color: var(--text-muted, #8b949e);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .btn {
      padding: 6px 14px;
      font-size: 12px;
      font-weight: 600;
      border-radius: var(--radius-sm, 6px);
      cursor: pointer;
      border: 1px solid var(--border, #2d333b);
      background: var(--bg-surface, #161b22);
      color: var(--text-primary, #ffffff);
      transition: all 0.15s ease;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }

    .btn:hover:not(:disabled) {
      border-color: var(--accent, #6366f1);
      background: var(--bg-elevated, #21262d);
    }

    .btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .btn-toggle {
      background: transparent;
    }

    .btn-toggle.active {
      background: var(--accent-glow, rgba(99, 102, 241, 0.15));
      border-color: var(--accent, #6366f1);
      color: var(--accent, #818cf8);
    }

    .banner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 18px;
      border-radius: var(--radius, 8px);
      border: 1px solid transparent;
      font-size: 14px;
      font-weight: 600;
    }

    .banner.up {
      background: rgba(16, 185, 129, 0.1);
      border-color: rgba(16, 185, 129, 0.3);
      color: #34d399;
    }

    .banner.degraded {
      background: rgba(245, 158, 11, 0.1);
      border-color: rgba(245, 158, 11, 0.3);
      color: #fbbf24;
    }

    .banner.down {
      background: rgba(239, 68, 68, 0.1);
      border-color: rgba(239, 68, 68, 0.3);
      color: #f87171;
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 16px;
    }

    .card {
      background: var(--bg-surface, #161b22);
      border: 1px solid var(--border, #2d333b);
      border-radius: var(--radius, 8px);
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border, #2d333b);
      padding-bottom: 10px;
    }

    .card-title {
      margin: 0;
      font-size: 14px;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .badge {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 9999px;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .badge.up, .badge.configured {
      background: rgba(16, 185, 129, 0.15);
      color: #34d399;
      border: 1px solid rgba(16, 185, 129, 0.3);
    }

    .badge.degraded {
      background: rgba(245, 158, 11, 0.15);
      color: #fbbf24;
      border: 1px solid rgba(245, 158, 11, 0.3);
    }

    .badge.down, .badge.not_configured {
      background: rgba(239, 68, 68, 0.15);
      color: #f87171;
      border: 1px solid rgba(239, 68, 68, 0.3);
    }

    .badge.default {
      background: rgba(99, 102, 241, 0.15);
      color: #818cf8;
      border: 1px solid rgba(99, 102, 241, 0.3);
    }

    .stat-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .stat-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 13px;
      padding: 4px 0;
    }

    .stat-label {
      color: var(--text-muted, #8b949e);
    }

    .stat-val {
      font-family: var(--font-mono, monospace);
      font-weight: 600;
    }

    .progress-bar {
      height: 6px;
      background: var(--bg-base, #0a0b0f);
      border-radius: 9999px;
      overflow: hidden;
      margin-top: 4px;
    }

    .progress-fill {
      height: 100%;
      background: var(--accent, #6366f1);
      transition: width 0.3s ease;
    }

    .check-item {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 8px;
      background: var(--bg-base, #0a0b0f);
      border-radius: 6px;
      border: 1px solid var(--border, #2d333b);
    }

    .check-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 13px;
      font-weight: 600;
    }

    .check-data {
      font-size: 11px;
      font-family: var(--font-mono, monospace);
      color: var(--text-muted, #8b949e);
      word-break: break-all;
    }

    .provider-filter-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }

    .filter-input {
      padding: 4px 10px;
      font-size: 12px;
      background: var(--bg-base, #0a0b0f);
      border: 1px solid var(--border, #2d333b);
      border-radius: var(--radius-sm, 6px);
      color: var(--text-primary, #ffffff);
      outline: none;
      min-width: 180px;
    }

    .filter-input:focus {
      border-color: var(--accent, #6366f1);
    }

    .filter-select {
      padding: 4px 8px;
      font-size: 12px;
      background: var(--bg-base, #0a0b0f);
      border: 1px solid var(--border, #2d333b);
      border-radius: var(--radius-sm, 6px);
      color: var(--text-primary, #ffffff);
      cursor: pointer;
    }

    .error-box {
      padding: 16px;
      border-radius: var(--radius, 8px);
      background: rgba(239, 68, 68, 0.1);
      border: 1px solid rgba(239, 68, 68, 0.3);
      color: #f87171;
      font-size: 13px;
    }
  `;

  connectedCallback() {
    super.connectedCallback();
    this.refresh();
    this._startAutoRefresh();
  }

  override updated(changedProperties: PropertyValues) {
    if (changedProperties.has('_appContext')) {
      const oldContext = changedProperties.get('_appContext') as ArcAppContext | undefined;
      const keyChanged = this._appContext?.apiKey !== oldContext?.apiKey;
      if ((keyChanged || this._authStatus === 401 || !this._health) && this._appContext?.apiKey && !this._loading) {
        this.refresh();
      }
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._stopAutoRefresh();
  }

  private _startAutoRefresh() {
    this._stopAutoRefresh();
    if (this._autoRefresh) {
      this._refreshTimer = globalThis.setInterval(() => {
        if (!this._loading) {
          this.refresh(true);
        }
      }, 10000);
    }
  }

  private _stopAutoRefresh() {
    if (this._refreshTimer !== null) {
      globalThis.clearInterval(this._refreshTimer);
      this._refreshTimer = null;
    }
  }

  private _toggleAutoRefresh() {
    this._autoRefresh = !this._autoRefresh;
    if (this._autoRefresh) {
      this._startAutoRefresh();
    } else {
      this._stopAutoRefresh();
    }
  }

  async refresh(silent = false) {
    if (!silent) this._loading = true;
    this._error = null;
    this._authStatus = null;

    try {
      const client = new ArcClient('/api/v1', this._appContext?.apiKey || '');
      this._health = await client.getCockpitHealth();
    } catch (e: any) {
      if (e instanceof ArcApiError) {
        if (e.status === 401 || e.status === 403) {
          this._authStatus = e.status;
        }
        this._error = e.message;
      } else {
        this._error = e.message || 'Failed to fetch system health';
      }
    } finally {
      if (!silent) this._loading = false;
    }
  }

  render() {
    return html`
      <div class="header">
        <div class="title-group">
          <h2>🩺 System Health & Diagnostics</h2>
          <p class="subtitle">MicroProfile Health, Agroal connection pools, and LLM provider configuration status</p>
        </div>
        <div class="actions">
          <button
            class="btn btn-toggle ${this._autoRefresh ? 'active' : ''}"
            @click=${this._toggleAutoRefresh}
            aria-pressed="${this._autoRefresh}"
            title="Toggle 10s auto-refresh"
          >
            ⏱ Auto-refresh: ${this._autoRefresh ? 'ON' : 'OFF'}
          </button>
          <button
            class="btn"
            @click=${() => this.refresh(false)}
            ?disabled=${this._loading}
            aria-label="Refresh health metrics"
          >
            🔄 ${this._loading ? 'Refreshing...' : 'Refresh'}
          </button>
        </div>
      </div>

      ${this._renderContent()}
    `;
  }

  private _renderContent() {
    if (this._authStatus === 401 || this._authStatus === 403) {
      return html`
        <div class="error-box" role="alert">
          <strong>Access Denied (${this._authStatus})</strong>: Internal operator role required to view full cockpit health.
        </div>
      `;
    }

    if (this._error && !this._health) {
      return html`
        <div class="error-box" role="alert">
          <strong>Failed to load health status:</strong> ${this._error}
        </div>
      `;
    }

    if (!this._health) {
      return html`<div class="stat-label">Loading diagnostics...</div>`;
    }

    const overallClass = (this._health.status || 'up').toLowerCase();

    return html`
      <div class="banner ${overallClass}" role="status" aria-live="polite">
        <span>Overall Status: ${this._health.status}</span>
        <span style="font-size: 11px; font-weight: normal; opacity: 0.8">
          Last checked: ${new Date(this._health.timestamp).toLocaleTimeString()}
        </span>
      </div>

      <div class="grid">
        ${this._renderHealthChecksCard()}
        ${this._renderDatabaseCard()}
        ${this._renderProvidersCard()}
      </div>
    `;
  }

  private _renderHealthChecksCard() {
    const hc = this._health?.healthChecks;
    if (!hc) return nothing;

    return html`
      <div class="card" role="region" aria-label="MicroProfile Health Checks">
        <div class="card-header">
          <h3 class="card-title">MicroProfile Checks</h3>
          <span class="badge ${hc.status.toLowerCase()}">${hc.status}</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Passing Checks</span>
          <span class="stat-val">${hc.passingChecks} / ${hc.totalChecks}</span>
        </div>
        <div class="stat-list">
          ${hc.checks.map(
            (c) => html`
              <div class="check-item">
                <div class="check-header">
                  <span>${c.name}</span>
                  <span class="badge ${c.status.toLowerCase()}">${c.status}</span>
                </div>
                ${c.data && Object.keys(c.data).length > 0
                  ? html`
                      <div class="check-data">
                        ${Object.entries(c.data).map(([k, v]) => `${k}: ${v}`).join(' | ')}
                      </div>
                    `
                  : nothing}
              </div>
            `
          )}
        </div>
      </div>
    `;
  }

  private _renderDatabaseCard() {
    const db = this._health?.database;
    if (!db) return nothing;

    const active = db.activeConnections ?? 0;
    const max = db.maxPoolSize ?? 1;
    const pct = Math.min(100, Math.round((active / (max || 1)) * 100));

    return html`
      <div class="card" role="region" aria-label="Database and Connection Pool">
        <div class="card-header">
          <h3 class="card-title">Database & Agroal Pool</h3>
          <span class="badge ${db.status.toLowerCase()}">${db.status}</span>
        </div>
        <div class="stat-list">
          <div class="stat-row">
            <span class="stat-label">Persistence Backend</span>
            <span class="stat-val">${db.backend}</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">Connection Pooling</span>
            <span class="stat-val">${db.poolEnabled ? 'Agroal Enabled' : 'Disabled / Direct'}</span>
          </div>
          ${db.poolEnabled
            ? html`
                <div class="stat-row">
                  <span class="stat-label">Active Connections</span>
                  <span class="stat-val">${db.activeConnections ?? 'N/A'}</span>
                </div>
                <div class="stat-row">
                  <span class="stat-label">Available Connections</span>
                  <span class="stat-val">${db.availableConnections ?? 'N/A'}</span>
                </div>
                <div class="stat-row">
                  <span class="stat-label">Max Pool Size</span>
                  <span class="stat-val">${db.maxPoolSize ?? 'N/A'}</span>
                </div>
                <div class="stat-row">
                  <span class="stat-label">Awaiting Requests</span>
                  <span class="stat-val">${db.awaitingConnections ?? 0}</span>
                </div>
                <div class="progress-bar" title="Pool Utilization: ${pct}%">
                  <div class="progress-fill" style="width: ${pct}%"></div>
                </div>
              `
            : nothing}
        </div>
      </div>
    `;
  }

  private _renderProvidersCard() {
    const allProviders = this._health?.providers || [];
    const search = this._providerSearch.trim().toLowerCase();
    const filtered = allProviders.filter((p) => {
      if (this._providerFilter === 'configured' && p.status !== 'CONFIGURED') {
        return false;
      }
      if (this._providerFilter === 'not_configured' && p.status === 'CONFIGURED') {
        return false;
      }
      if (search) {
        const name = (p.providerName || '').toLowerCase();
        const base = (p.baseUrl || '').toLowerCase();
        const details = (p.details || '').toLowerCase();
        const isMatch =
          name.includes(search) ||
          base.includes(search) ||
          details.includes(search) ||
          (search === 'claude' && (name === 'claude-code' || name === 'anthropic')) ||
          (search === 'claudecode' && (name === 'claude-code' || name === 'anthropic')) ||
          (search === 'anthropic' && (name === 'claude-code' || name === 'anthropic')) ||
          (search === 'chatgpt' && (name === 'codex' || name === 'openai')) ||
          (search === 'codex' && (name === 'codex' || name === 'openai'));
        if (!isMatch) return false;
      }
      return true;
    });

    return html`
      <div class="card" style="grid-column: 1 / -1" role="region" aria-label="LLM Provider Configuration Status">
        <div class="card-header" style="flex-wrap: wrap; gap: 8px;">
          <h3 class="card-title">LLM Provider Configuration Status (${filtered.length}/${allProviders.length})</h3>
          <div class="provider-filter-bar">
            <input
              type="search"
              class="filter-input"
              placeholder="Filter providers (e.g. codex, claude)..."
              .value="${this._providerSearch}"
              @input="${(e: Event) => (this._providerSearch = (e.target as HTMLInputElement).value)}"
              aria-label="Filter providers by name, host, or status"
            />
            <select
              class="filter-select"
              .value="${this._providerFilter}"
              @change="${(e: Event) => (this._providerFilter = (e.target as HTMLSelectElement).value as any)}"
              aria-label="Filter providers by configuration status"
            >
              <option value="all">All Providers</option>
              <option value="configured">Configured Only</option>
              <option value="not_configured">Not Configured</option>
            </select>
          </div>
        </div>
        <div class="stat-list">
          ${filtered.length === 0
            ? html`
                <div style="font-size: 12px; color: var(--text-muted, #8b949e); padding: 12px; text-align: center;">
                  No providers match the filter criteria "${this._providerSearch}".
                </div>
              `
            : filtered.map(
                (p) => html`
                  <div class="check-item">
                    <div class="check-header">
                      <span style="display: flex; align-items: center; gap: 6px;">
                        <strong>${p.providerName}</strong>
                        ${p.isDefault ? html`<span class="badge default">Default</span>` : nothing}
                        ${p.providerName === 'claude-code'
                          ? html`<span class="badge" style="font-size: 9px; opacity: 0.75;" title="Anthropic / Claude Code CLI OAuth">Claude Code / Anthropic</span>`
                          : p.providerName === 'codex'
                          ? html`<span class="badge" style="font-size: 9px; opacity: 0.75;" title="Codex CLI / ChatGPT Plus OAuth">Codex / ChatGPT Plus</span>`
                          : nothing}
                      </span>
                      <span class="badge ${p.status.toLowerCase()}">${p.status}</span>
                    </div>
                    <div class="check-data">
                      <span>Host: ${p.baseUrl}</span>
                      <br />
                      <span>Status: ${p.details}</span>
                    </div>
                  </div>
                `
              )}
        </div>
      </div>
    `;
  }
}
