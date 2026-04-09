import { LitElement, css, html, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import './arc-connection-badge';

@customElement('arc-nav')
export class ArcNav extends LitElement {
  @property({ type: String }) route = '#/chat';
  @property({ type: String }) sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';
  @property({ type: Boolean, reflect: true }) open = false;

  static styles = css`
    :host {
      display: flex; flex-direction: column;
      width: 220px; background: var(--bg-surface);
      border-right: 1px solid var(--border);
      padding: var(--space-4) var(--space-3); height: 100vh;
      box-sizing: border-box;
      transition: var(--transition);
    }
    @media (max-width: 640px) {
      :host {
        position: fixed;
        left: 0; top: 0; bottom: 0;
        z-index: 100;
        transform: translateX(-100%);
        box-shadow: var(--shadow-lg);
        width: 250px;
      }
      :host([open]) {
        transform: translateX(0);
      }
    }
    .logo {
      display: flex; align-items: center; gap: 10px;
      padding: var(--space-4) var(--space-3) var(--space-8);
      font-weight: 700; font-size: 1.25rem;
      letter-spacing: -0.02em;
    }
    .logo-icon {
      color: var(--accent);
      width: 20px; height: 20px;
    }
    .logo-text {
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .links {
      display: flex; flex-direction: column;
      gap: 4px;
      flex-grow: 1;
    }
    a {
      color: var(--text-muted); 
      padding: 10px 14px;
      text-decoration: none; font-size: 14px;
      cursor: pointer; border-radius: 8px;
      display: flex; align-items: center; gap: 12px;
      transition: var(--transition);
      position: relative;
      font-weight: 500;
    }
    a.active {
      color: var(--accent);
      background: var(--accent-glow);
    }
    a.active::before {
      content: '';
      position: absolute;
      left: -12px;
      top: 10px;
      bottom: 10px;
      width: 3px;
      background: var(--accent);
      border-radius: 0 4px 4px 0;
    }
    a:hover:not(.active) {
      background: var(--accent-glow);
      color: var(--text-primary);
    }
    .icon {
      width: 16px; height: 16px;
      display: flex; align-items: center; justify-content: center;
    }
    .footer {
      padding-top: var(--space-4);
      border-top: 1px solid var(--border);
      display: flex; flex-direction: column; gap: var(--space-4);
    }
    .version {
      font-size: 11px;
      color: var(--text-muted);
      padding-left: var(--space-3);
    }
  `;

  render() {
    return html`
      <div class="logo">
        <span class="logo-icon">⬡</span>
        <span class="logo-text">ArcClaw</span>
      </div>
      <div class="links">
        <a href="#/chat" class="${this.route === '#/chat' ? 'active' : ''}" aria-current="${this.route === '#/chat' ? 'page' : nothing}">
          <span class="icon">💬</span> Chat
        </a>
        <a href="#/agents" class="${this.route === '#/agents' ? 'active' : ''}" aria-current="${this.route === '#/agents' ? 'page' : nothing}">
          <span class="icon">🤖</span> Agents
        </a>
        <a href="#/hitl" class="${this.route === '#/hitl' ? 'active' : ''}" aria-current="${this.route === '#/hitl' ? 'page' : nothing}">
          <span class="icon">✋</span> Approvals
        </a>
        <a href="#/skills" class="${this.route === '#/skills' ? 'active' : ''}" aria-current="${this.route === '#/skills' ? 'page' : nothing}">
          <span class="icon">🔧</span> Skills
        </a>
        <a href="#/settings" class="${this.route === '#/settings' ? 'active' : ''}" aria-current="${this.route === '#/settings' ? 'page' : nothing}">
          <span class="icon">⚙</span> Settings
        </a>
      </div>
      <div class="footer">
        <arc-connection-badge .status="${this.sseStatus}"></arc-connection-badge>
        <div class="version">v1.0</div>
      </div>
    `;
  }
}
