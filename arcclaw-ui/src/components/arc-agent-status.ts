import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { AgentStatusDto } from '../api/types';

const AUTONOMY_LABELS: Record<string, string> = {
  readonly:   '👁 Read Only',
  background: '⚙ Background',
  supervised: '✋ Supervised',
  full:       '🚀 Full',
};

const AUTONOMY_COLORS: Record<string, string> = {
  readonly:   'var(--color-text-secondary)',
  background: 'var(--color-info)',
  supervised: 'var(--color-warning)',
  full:       'var(--color-success)',
};

@customElement('arc-agent-status')
export class ArcAgentStatus extends LitElement {
  @property({ type: Array }) agents: AgentStatusDto[] = [];

  static styles = css`
    :host { display: block; padding: var(--space-6); overflow-y: auto; height: 100%; box-sizing: border-box; }
    h2 { color: var(--color-text-primary); margin-top: 0; margin-bottom: var(--space-4); font-size: var(--font-size-lg); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
      gap: var(--space-4);
    }
    .empty { color: var(--color-text-secondary); font-style: italic; }
    
    .card {
      background: var(--color-bg-surface); border-radius: var(--radius-md); 
      padding: var(--space-4);
      border-left: 4px solid var(--autonomy-color, var(--color-border));
      display: flex; flex-direction: column; gap: var(--space-3);
    }
    .header { display: flex; align-items: center; justify-content: space-between; }
    .name-col { display: flex; flex-direction: column; }
    .name { font-weight: var(--font-weight-bold); font-size: var(--font-size-base); color: var(--color-text-primary); }
    
    .status-badge {
      font-size: 0.7rem; padding: 2px 8px; border-radius: var(--radius-full);
      font-weight: var(--font-weight-medium); text-transform: uppercase;
    }
    .status-active      { background: rgba(59, 130, 246, 0.2); color: var(--color-info); }
    .status-idle        { background: rgba(34, 197, 94, 0.2); color: var(--color-success); }
    .status-waiting_approval { background: rgba(245, 158, 11, 0.2); color: var(--color-warning); }
    .status-error       { background: rgba(239, 68, 68, 0.2); color: var(--color-error); }
    
    .autonomy-chip {
      font-size: 0.75rem; color: var(--autonomy-color);
      display: inline-block; padding: 2px 6px; 
      border: 1px solid var(--autonomy-color); border-radius: 4px;
      align-self: flex-start;
    }
    
    .task { 
      font-size: var(--font-size-sm); color: var(--color-text-secondary); 
      font-style: italic; min-height: 1.2em;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .footer-row { display: flex; justify-content: space-between; align-items: center; }
    .tokens { font-size: 0.75rem; color: var(--color-text-secondary); }
  `;

  private displayName(name: string): string {
    return name.includes(':') ? name.split(':')[1] : name;
  }

  private renderCard(agent: AgentStatusDto) {
    const dName = this.displayName(agent.agentName);
    const color = AUTONOMY_COLORS[agent.autonomyLevel] || 'var(--color-border)';
    const autLabel = AUTONOMY_LABELS[agent.autonomyLevel] || agent.autonomyLevel;
    const task = agent.currentTask && agent.currentTask.length > 60 
      ? agent.currentTask.substring(0, 57) + '...' 
      : (agent.currentTask || 'No active task');
      
    const tokenDisplay = agent.tokenCount > 0 ? `~${agent.tokenCount.toLocaleString()} tokens` : '— tokens';

    return html`
      <div class="card" style="--autonomy-color: ${color}">
        <div class="header">
          <div class="name-col">
            <span class="name">${dName}</span>
          </div>
          <span class="status-badge status-${agent.status}">${agent.status.replace('_', ' ')}</span>
        </div>
        <div class="autonomy-chip">${autLabel}</div>
        <div class="task" title="${agent.currentTask || ''}">${task}</div>
        <div class="footer-row">
          <div class="tokens">${tokenDisplay}</div>
        </div>
      </div>
    `;
  }

  render() {
    return html`
      <h2>Agent Status</h2>
      ${this.agents.length === 0
        ? html`<p class="empty">No agents active. Send a message in Chat to start a session.</p>`
        : html`<div class="grid">${this.agents.map(a => this.renderCard(a))}</div>`
      }
    `;
  }
}
