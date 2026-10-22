import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { McpServerDraft } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-mcp')
export class ArcOnboardingStepMcp extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }
      .headers-section {
        background: var(--bg-surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
        margin-bottom: var(--space-3);
      }
      .header-row {
        display: flex;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
      }
      .header-chip-list {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-2);
        margin-top: var(--space-2);
      }
      .header-chip {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        font-size: var(--font-size-xs);
        padding: 2px 8px;
        background: var(--bg-elevated);
        border: 1px solid var(--border);
        border-radius: var(--radius-full);
      }
      .header-chip button {
        background: none;
        border: none;
        color: var(--text-muted);
        cursor: pointer;
        padding: 0;
        font-size: 14px;
        line-height: 1;
      }
    `
  ];

  @property({ type: Array }) mcpServers: McpServerDraft[] = [];
  @state() private _showAddMcp = false;
  @state() private _newMcp: Partial<McpServerDraft> = { transport: 'sse', category: 'NETWORK' };
  @state() private _headerKey = '';
  @state() private _headerValue = '';
  @state() private _headers: Record<string, string> = {};

  private _addHeader() {
    const k = this._headerKey.trim();
    const v = this._headerValue.trim();
    if (k && v) {
      this._headers = { ...this._headers, [k]: v };
      this._headerKey = '';
      this._headerValue = '';
    }
  }

  private _removeHeader(key: string) {
    const next = { ...this._headers };
    delete next[key];
    this._headers = next;
  }

  private _addMcp() {
    const url = this._newMcp.url?.trim();
    if (!url || (!url.startsWith('http://') && !url.startsWith('https://'))) {
      return;
    }
    try {
      new URL(url);
    } catch (_) {
      return;
    }
    if (this._newMcp.name?.trim() && !this._newMcp.name.includes(',')
      && this._newMcp.transport) {
      const server: McpServerDraft = {
        name: this._newMcp.name.trim(),
        transport: this._newMcp.transport,
        url,
        category: this._newMcp.category || 'NETWORK',
        headers: Object.keys(this._headers).length > 0 ? { ...this._headers } : undefined
      };
      const updated = [...this.mcpServers, server];
      this.dispatchEvent(
        new CustomEvent('update-mcp', {
          detail: { mcpServers: updated },
          bubbles: true,
          composed: true
        })
      );
      this._newMcp = { transport: 'sse', category: 'NETWORK' };
      this._headers = {};
      this._headerKey = '';
      this._headerValue = '';
      this._showAddMcp = false;
    }
  }

  private _removeMcp(index: number) {
    const updated = this.mcpServers.filter((_, i) => i !== index);
    this.dispatchEvent(
      new CustomEvent('update-mcp', {
        detail: { mcpServers: updated },
        bubbles: true,
        composed: true
      })
    );
  }

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <p style="font-size:var(--font-size-sm);color:var(--text-muted);line-height:1.6;margin-bottom:var(--space-5)">
        Connect remote HTTP/SSE tool servers (MCP) to extend your agents with web search and databases. This onboarding step
        accepts remote endpoints and protected headers. Local STDIO processes and Podman containers are outside this
        onboarding flow. MCP configuration import is preview-only and rejects STDIO entries without launching them.
      </p>

      ${this.mcpServers?.length
        ? html`
            <div class="mcp-list">
              ${this.mcpServers.map(
                (s, i) => html`
                  <div class="mcp-item">
                    <span class="mcp-item-name">${s.name}</span>
                    <span class="mcp-item-tag">${s.category || 'NETWORK'}</span>
                    <span class="mcp-item-tag">${s.transport}</span>
                    ${(s.headers && Object.keys(s.headers).length) || (s.headerVaultRefs && Object.keys(s.headerVaultRefs).length)
                      ? html`<span class="mcp-item-tag">${Object.keys(s.headers || s.headerVaultRefs || {}).length} header${Object.keys(s.headers || s.headerVaultRefs || {}).length > 1 ? 's' : ''}</span>`
                      : ''}
                    <button class="mcp-remove" @click="${() => this._removeMcp(i)}">&times;</button>
                  </div>
                `
              )}
            </div>
          `
        : html`
            <div style="text-align:center;padding:var(--space-6) 0;color:var(--text-muted);font-size:var(--font-size-sm)">
              No MCP servers added yet
            </div>
          `}
      ${this._showAddMcp
        ? html`
            <div
              style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-4);margin-bottom:var(--space-3);"
            >
              <label class="field-label">Server Name</label>
              <input
                class="field-input"
                type="text"
                placeholder="e.g. brave-search"
                .value="${this._newMcp?.name ?? ''}"
                @input="${(e: any) => (this._newMcp = { ...this._newMcp, name: e.target.value })}"
                style="margin-bottom:var(--space-3)"
              />
              ${this._newMcp?.name?.includes(',')
                ? html`<div style="color:var(--text-danger);font-size:12px;margin-bottom:var(--space-3)">Server names cannot contain commas.</div>`
                : ''}

              <label class="field-label">Category</label>
              <select
                class="field-select"
                .value="${this._newMcp?.category ?? 'NETWORK'}"
                @change="${(e: any) => (this._newMcp = { ...this._newMcp, category: e.target.value })}"
                style="margin-bottom:var(--space-3)"
              >
                <option value="NETWORK">NETWORK (Outbound Tools)</option>
                <option value="INTERNAL">INTERNAL (Host / Local Tools)</option>
                <option value="COMPUTE">COMPUTE (Execution / Sandbox Tools)</option>
              </select>

              <label class="field-label">Transport</label>
              <!-- O1 wizard provides SSE transport for outbound integrations; HTTP/Stdio/Podman transport configuration is staged for O4 -->
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:var(--space-2);margin-bottom:var(--space-3)">
                ${['sse'].map(
                  t => html`
                    <div
                      class="provider-card ${this._newMcp?.transport === t ? 'selected' : ''}"
                      @click="${() => (this._newMcp = { ...this._newMcp, transport: t })}"
                    >
                      <span
                        style="font-size:var(--font-size-sm);font-weight:var(--font-weight-medium);color:var(--text-primary)"
                      >
                        ${t === 'sse' ? 'HTTP / SSE' : t}
                      </span>
                    </div>
                  `
                )}
              </div>

              <label class="field-label">URL</label>
              <input
                class="field-input"
                type="url"
                placeholder="http://localhost:3100/mcp"
                .value="${this._newMcp?.url ?? ''}"
                @input="${(e: any) => (this._newMcp = { ...this._newMcp, url: e.target.value })}"
                style="margin-bottom:var(--space-3)"
              />

              <div class="headers-section">
                <label class="field-label">Custom Headers</label>
                <div class="header-row">
                  <input
                    class="field-input"
                    type="text"
                    placeholder="Header name (e.g. Authorization)"
                    .value="${this._headerKey}"
                    @input="${(e: any) => (this._headerKey = e.target.value)}"
                    style="flex: 1;"
                  />
                  <input
                    class="field-input"
                    type="password"
                    placeholder="Header value (e.g. Bearer token)"
                    .value="${this._headerValue}"
                    @input="${(e: any) => (this._headerValue = e.target.value)}"
                    style="flex: 1;"
                  />
                  <button
                    class="btn-secondary"
                    type="button"
                    ?disabled="${!this._headerKey.trim() || !this._headerValue.trim()}"
                    @click="${this._addHeader}"
                  >
                    Add
                  </button>
                </div>
                ${Object.keys(this._headers).length > 0
                  ? html`
                      <div class="header-chip-list">
                        ${Object.keys(this._headers).map(
                          k => html`
                            <span class="header-chip">
                              <strong>${k}:</strong> <span class="header-masked">••••••••</span>
                              <button type="button" @click="${() => this._removeHeader(k)}">&times;</button>
                            </span>
                          `
                        )}
                      </div>
                    `
                  : ''}
              </div>

              <div class="btn-row" style="margin-top:var(--space-2)">
                <button class="btn-ghost" @click="${() => (this._showAddMcp = false)}">Cancel</button>
                <button
                  class="btn-primary"
                  ?disabled="${!this._newMcp?.name?.trim() || this._newMcp?.name?.includes(',') || !this._newMcp?.transport || !this._newMcp?.url?.trim()}"
                  @click="${this._addMcp}"
                >
                  Add Server
                </button>
              </div>
            </div>
          `
        : html`
            <button class="add-mcp-btn" @click="${() => (this._showAddMcp = true)}">+ Add MCP Server</button>
          `}

      <div class="btn-row" style="margin-top:var(--space-5)">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
