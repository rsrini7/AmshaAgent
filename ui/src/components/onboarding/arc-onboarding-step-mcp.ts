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
    `
  ];

  @property({ type: Array }) mcpServers: McpServerDraft[] = [];
  @state() private _showAddMcp = false;
  @state() private _newMcp: Partial<McpServerDraft> = { transport: 'sse' };

  private _addMcp() {
    if (this._newMcp.name && this._newMcp.transport) {
      const updated = [...this.mcpServers, this._newMcp as McpServerDraft];
      this.dispatchEvent(
        new CustomEvent('update-mcp', {
          detail: { mcpServers: updated },
          bubbles: true,
          composed: true
        })
      );
      this._newMcp = { transport: 'sse' };
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
        Connect external tool servers (MCP) to extend your agents with web search, code execution, databases, and more.
      </p>

      ${this.mcpServers?.length
        ? html`
            <div class="mcp-list">
              ${this.mcpServers.map(
                (s, i) => html`
                  <div class="mcp-item">
                    <span class="mcp-item-name">${s.name}</span>
                    <span class="mcp-item-tag">${s.transport}</span>
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

              <label class="field-label">Transport</label>
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:var(--space-2);margin-bottom:var(--space-3)">
                ${['sse', 'stdio'].map(
                  t => html`
                    <div
                      class="provider-card ${this._newMcp?.transport === t ? 'selected' : ''}"
                      @click="${() => (this._newMcp = { ...this._newMcp, transport: t })}"
                    >
                      <span
                        style="font-size:var(--font-size-sm);font-weight:var(--font-weight-medium);color:var(--text-primary)"
                      >
                        ${t === 'sse' ? 'http/sse' : t}
                      </span>
                    </div>
                  `
                )}
              </div>

              ${this._newMcp?.transport === 'sse'
                ? html`
                    <label class="field-label">URL</label>
                    <input
                      class="field-input"
                      type="url"
                      placeholder="http://localhost:3100/mcp"
                      .value="${this._newMcp?.url ?? ''}"
                      @input="${(e: any) => (this._newMcp = { ...this._newMcp, url: e.target.value })}"
                      style="margin-bottom:var(--space-3)"
                    />
                  `
                : html`
                    <label class="field-label">Command</label>
                    <input
                      class="field-input"
                      type="text"
                      placeholder="npx -y @brave/search-mcp"
                      .value="${this._newMcp?.command ?? ''}"
                      @input="${(e: any) => (this._newMcp = { ...this._newMcp, command: e.target.value })}"
                      style="margin-bottom:var(--space-3)"
                    />
                  `}

              <div class="btn-row" style="margin-top:var(--space-2)">
                <button class="btn-ghost" @click="${() => (this._showAddMcp = false)}">Cancel</button>
                <button
                  class="btn-primary"
                  ?disabled="${!this._newMcp?.name?.trim() || !this._newMcp?.transport}"
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
