import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { ToolDto } from '../api/types';
import { ArcClient } from '../api/client';

@customElement('arc-skills-panel')
export class ArcSkillsPanel extends LitElement {
  @property({ type: String }) apiKey = '';
  
  @state() private _tools: ToolDto[] = [];
  @state() private _loading = false;
  @state() private _error = '';

  static styles = css`
    :host { display: block; padding: var(--space-6); overflow-y: auto; height: 100%; box-sizing: border-box; }
    h2 { color: var(--color-text-primary); margin-top: 0; margin-bottom: var(--space-4); font-size: var(--font-size-lg); }
    .status-msg { color: var(--color-text-secondary); font-style: italic; }
    .error { color: var(--color-error); }
    
    .list { display: flex; flex-direction: column; gap: var(--space-3); }
    .card {
      background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md);
      padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-2);
    }
    
    .header { display: flex; justify-content: space-between; align-items: flex-start; }
    .name-col { display: flex; flex-direction: column; gap: 4px; }
    .name { color: var(--color-text-primary); font-weight: var(--font-weight-bold); font-size: var(--font-size-base); }
    .desc { color: var(--color-text-secondary); font-size: var(--font-size-sm); }
    
    .badges { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .badge {
      font-size: 0.7rem; padding: 2px 8px; border-radius: var(--radius-full);
      font-weight: var(--font-weight-medium);
    }
    .badge-op-network { background: rgba(59, 130, 246, 0.2); color: var(--color-info); }
    .badge-op-read { background: rgba(34, 197, 94, 0.2); color: var(--color-success); }
    .badge-op-default { background: var(--color-bg-elevated); color: var(--color-text-primary); }
    
    .badge-aut-full { border: 1px solid var(--color-success); color: var(--color-success); }
    .badge-aut-supervised { border: 1px solid var(--color-warning); color: var(--color-warning); }
    .badge-aut-readonly { border: 1px solid var(--color-text-secondary); color: var(--color-text-secondary); }
    .badge-aut-default { border: 1px solid var(--color-border); color: var(--color-text-secondary); }
  `;

  async connectedCallback() {
    super.connectedCallback();
    if (this.apiKey) {
      this._loadData();
    }
  }
  
  updated(changedProperties: Map<string, any>) {
    if (changedProperties.has('apiKey') && this.apiKey && this._tools.length === 0 && !this._loading) {
      this._loadData();
    }
  }

  private async _loadData() {
    this._loading = true;
    this._error = '';
    try {
      const client = new ArcClient('/api/v1', this.apiKey);
      this._tools = await client.getTools();
    } catch (e: any) {
      this._error = e.message || 'Failed to load tools';
    } finally {
      this._loading = false;
    }
  }

  render() {
    return html`
      <h2>Skills & Tools</h2>
      
      ${this._loading ? html`<p class="status-msg">Loading skills...</p>` : ''}
      ${this._error ? html`<p class="status-msg error">${this._error}</p>` : ''}
      
      ${!this._loading && !this._error && this._tools.length === 0 ? html`<p class="status-msg">No skills registered.</p>` : ''}
      
      <div class="list">
        ${this._tools.map(t => html`
          <div class="card">
            <div class="header">
              <div class="name-col">
                <span class="name">${t.name}</span>
                <span class="desc">${t.description}</span>
              </div>
            </div>
            <div class="badges">
              <span class="badge ${t.operationType === 'NETWORK_OUTBOUND' ? 'badge-op-network' : t.operationType === 'READ_ONLY' ? 'badge-op-read' : 'badge-op-default'}">
                ${t.operationType || 'SYSTEM'}
              </span>
              <span class="badge ${t.autonomyRequired?.includes('super') ? 'badge-aut-supervised' : t.autonomyRequired?.includes('read') ? 'badge-aut-readonly' : t.autonomyRequired?.includes('full') ? 'badge-aut-full' : 'badge-aut-default'}">
                Req: ${t.autonomyRequired || 'UNKNOWN'}
              </span>
            </div>
          </div>
        `)}
      </div>
    `;
  }
}
