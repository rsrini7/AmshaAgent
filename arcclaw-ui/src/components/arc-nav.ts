import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import './arc-connection-badge';

@customElement('arc-nav')
export class ArcNav extends LitElement {
  @property({ type: String }) route = '#/chat';
  @property({ type: String }) sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';

  static styles = css`
    :host {
      display: flex; flex-direction: column;
      width: 200px; background: var(--color-bg-surface);
      border-right: 1px solid var(--color-border);
      padding: var(--space-4) 0; height: 100vh;
    }
    .logo {
      color: var(--color-info); font-size: var(--font-size-lg);
      font-weight: var(--font-weight-bold);
      padding: var(--space-2) var(--space-6) var(--space-4);
    }
    .links {
      display: flex; flex-direction: column;
      flex-grow: 1;
    }
    a {
      color: var(--color-text-secondary); padding: var(--space-2) var(--space-6);
      text-decoration: none; font-size: var(--font-size-sm);
      cursor: pointer; border-left: 3px solid transparent;
      display: flex; align-items: center; gap: var(--space-2);
      transition: background 0.1s, color 0.1s;
    }
    a.active {
      color: var(--color-info);
      border-left-color: var(--color-info);
      background: var(--color-bg-base);
    }
    a:hover:not(.active) {
      background: var(--color-bg-elevated);
      color: var(--color-text-primary);
    }
    .footer {
      padding: var(--space-4) var(--space-6);
      border-top: 1px solid var(--color-border);
    }
  `;

  render() {
    return html`
      <div class="logo">🦅 ArcClaw</div>
      <div class="links">
        <a href="#/chat" class="${this.route === '#/chat' ? 'active' : ''}">💬 Chat</a>
        <a href="#/agents" class="${this.route === '#/agents' ? 'active' : ''}">🤖 Agents</a>
        <a href="#/hitl" class="${this.route === '#/hitl' ? 'active' : ''}">✋ Approvals</a>
        <a href="#/skills" class="${this.route === '#/skills' ? 'active' : ''}">🔧 Skills</a>
      </div>
      <div class="footer">
        <arc-connection-badge .status="${this.sseStatus}"></arc-connection-badge>
      </div>
    `;
  }
}
