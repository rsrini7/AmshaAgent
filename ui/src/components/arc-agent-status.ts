import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { AgentStatusDto } from '../api/generated/models/AgentStatusDto';
import { LOGO_SVG_SRC } from '../branding';

const AUTONOMY_LABELS: Record<string, string> = {
  readonly:   '👁 Read Only',
  background: '⚙ Background',
  supervised: '✋ Supervised',
  full:       '🚀 Full',
};

const AUTONOMY_COLORS: Record<string, string> = {
  readonly:   'var(--text-muted)',
  background: 'var(--accent)',
  supervised: '#f59e0b',
  full:       'var(--accent-secondary)',
};

@customElement('arc-agent-status')
export class ArcAgentStatus extends LitElement {
  @property({ type: Array }) agents: AgentStatusDto[] = [];

  static styles = css`
    :host { display: block; padding: var(--space-8); box-sizing: border-box; background: var(--bg-base); }
    h2 { color: var(--text-primary); margin-top: 0; margin-bottom: var(--space-6); font-size: 20px; font-weight: 700; letter-spacing: -0.02em; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: var(--space-5);
    }
    .empty { color: var(--text-muted); font-style: italic; font-size: 14px; }
    
    .card {
      background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-xl); 
      padding: var(--space-5); border-top: 4px solid var(--autonomy-color, var(--border));
      display: flex; flex-direction: column; gap: var(--space-4);
      transition: var(--transition);
      box-shadow: 0 4px 12px rgba(0,0,0,0.1);
    }
    .card:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0,0,0,0.2); border-color: var(--autonomy-color); }

    .header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
    .name-col { display: flex; flex-direction: column; gap: 4px; }
    .name { font-weight: 700; font-size: 16px; color: var(--text-primary); letter-spacing: -0.01em; }
    
    .status-badge {
      font-size: 10px; padding: 4px 10px; border-radius: 99px;
      font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
    }
    .status-active      { background: rgba(108, 99, 255, 0.1); color: var(--accent); }
    .status-idle        { background: rgba(0, 212, 170, 0.1); color: var(--accent-secondary); }
    .status-waiting_approval,
    .status-waiting-approval { background: rgba(245, 158, 11, 0.1); color: #f59e0b; }
    .status-waiting-delegate { background: rgba(59, 130, 246, 0.1); color: #60a5fa; }
    .status-error       { background: rgba(239, 68, 68, 0.1); color: #ef4444; }
    
    .autonomy-chip {
      font-size: 12px; color: var(--autonomy-color);
      display: inline-flex; align-items: center; gap: 6px;
      padding: 6px 12px; background: rgba(255, 255, 255, 0.03);
      border-radius: 8px; align-self: flex-start;
      font-weight: 600;
    }
    
    .task { 
      font-size: 14px; color: var(--text-muted); 
      line-height: 1.5; min-height: 3em; display: -webkit-box;
      -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
    }
    .footer-row { display: flex; justify-content: space-between; align-items: center; margin-top: 4px; }
    .tokens { font-size: 11px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }
    .agent-avatar {
      width: 32px; height: 32px; border-radius: 50%;
      background: var(--bg-elevated); border: 1px solid var(--border);
      display: flex; align-items: center; justify-content: center;
    }
    .agent-avatar-img { width: 24px; height: 24px; display: block; }
  `;

  private displayName(name: string): string {
    return name.includes(':') ? name.split(':')[1] : name;
  }

  private renderCard(agent: AgentStatusDto) {
    const dName = this.displayName(agent.agentName ?? '');
    const autonomyLevel = agent.autonomyLevel ?? 'readonly';
    const color = AUTONOMY_COLORS[autonomyLevel] || 'var(--border)';
    const autLabel = AUTONOMY_LABELS[autonomyLevel] || autonomyLevel;
    const task = agent.currentTask ?? 'Agent is currently idle.';
    const tokenCount = agent.tokenCount ?? 0;
    const tokenDisplay = tokenCount > 0 ? `${tokenCount.toLocaleString()} TOKENS` : '0 TOKENS';
    const status = agent.status ?? 'idle';

    return html`
      <div class="card" style="--autonomy-color: ${color}">
        <div class="header">
          <div class="agent-avatar"><img class="agent-avatar-img" src="${LOGO_SVG_SRC}" alt="" width="24" height="24" /></div>
          <div class="name-col">
            <span class="name">${dName}</span>
          </div>
          <span class="status-badge status-${status}">${status.replace('_', ' ')}</span>
        </div>
        <div class="autonomy-chip">${autLabel}</div>
        <div class="task" title="${task}">${task}</div>
        <div class="footer-row">
          <div class="tokens">${tokenDisplay}</div>
        </div>
      </div>
    `;
  }

  render() {
    return html`
      <div style="max-width: 1200px; margin: 0 auto;">
        <h2>Active Agents</h2>
        ${this.agents.length === 0
          ? html`<p class="empty">No agents active. Send a message in Chat to start a session.</p>`
          : html`<div class="grid">${this.agents.map(a => this.renderCard(a))}</div>`
        }
      </div>
    `;
  }
}
