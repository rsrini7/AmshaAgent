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
    :host { display: block; padding: 32px; overflow-y: auto; height: 100%; box-sizing: border-box; background: var(--bg-base); }
    h2 { color: var(--text-primary); margin-top: 0; margin-bottom: 24px; font-size: 20px; font-weight: 700; letter-spacing: -0.02em; }
    .status-msg { color: var(--text-muted); font-style: italic; font-size: 14px; }
    .error { color: #ef4444; }
    
    .list { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 20px; }
    .card {
      background: var(--bg-surface); border: 1px solid var(--border); border-radius: 16px;
      padding: 20px; display: flex; flex-direction: column; gap: 16px;
      transition: var(--transition);
      box-shadow: 0 4px 12px rgba(0,0,0,0.1);
    }
    .card:hover { border-color: var(--accent); transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0,0,0,0.2); }
    
    .header { display: flex; align-items: flex-start; gap: 12px; }
    .tool-avatar {
      width: 36px; height: 36px; border-radius: 10px;
      background: var(--accent-glow); border: 1px solid var(--accent);
      display: flex; align-items: center; justify-content: center;
      color: var(--accent); font-size: 18px; flex-shrink: 0;
    }
    .name-col { display: flex; flex-direction: column; gap: 4px; }
    .name { color: var(--text-primary); font-weight: 700; font-size: 16px; letter-spacing: -0.01em; }
    .desc { color: var(--text-muted); font-size: 13px; line-height: 1.5; min-height: 3em; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
    
    .badges { display: flex; gap: 8px; flex-wrap: wrap; margin-top: auto; }
    .badge {
      font-size: 10px; padding: 4px 10px; border-radius: 99px;
      font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
    }
    .badge-op-network { background: rgba(108, 99, 255, 0.1); color: var(--accent); }
    .badge-op-read { background: rgba(0, 212, 170, 0.1); color: var(--accent-secondary); }
    .badge-op-default { background: var(--bg-elevated); color: var(--text-muted); }
    
    .badge-aut-full { border: 1px solid var(--accent-secondary); color: var(--accent-secondary); }
    .badge-aut-supervised { border: 1px solid #f59e0b; color: #f59e0b; }
    .badge-aut-readonly { border: 1px solid var(--text-muted); color: var(--text-muted); }
    .badge-aut-default { border: 1px solid var(--border); color: var(--text-muted); }
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
      <h2>Skills & System Tools</h2>
      
      ${this._loading ? html`<p class="status-msg">Synchronizing skills catalogue...</p>` : ''}
      ${this._error ? html`<p class="status-msg error">${this._error}</p>` : ''}
      
      ${!this._loading && !this._error && this._tools.length === 0 ? html`<p class="status-msg">No skills registered with the cluster.</p>` : ''}
      
      <div class="list">
        ${this._tools.map(t => html`
          <div class="card">
            <div class="header">
               <div class="tool-avatar">⚒</div>
               <div class="name-col">
                  <span class="name">${t.name}</span>
               </div>
            </div>
            <span class="desc">${t.description}</span>
            <div class="badges">
              <span class="badge ${t.operationType === 'NETWORK_OUTBOUND' ? 'badge-op-network' : t.operationType === 'READ_ONLY' ? 'badge-op-read' : 'badge-op-default'}">
                ${t.operationType || 'SYSTEM'}
              </span>
              <span class="badge ${t.autonomyRequired?.includes('super') ? 'badge-aut-supervised' : t.autonomyRequired?.includes('read') ? 'badge-aut-readonly' : t.autonomyRequired?.includes('full') ? 'badge-aut-full' : 'badge-aut-default'}">
                REQ: ${t.autonomyRequired || 'UNKNOWN'}
              </span>
            </div>
          </div>
        `)}
      </div>
    `;
  }
}
