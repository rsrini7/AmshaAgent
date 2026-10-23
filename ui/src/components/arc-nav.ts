import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { SseStatus } from '../api/sse';
import { ROUTES } from '../routes';
import { LOGO_SVG_SRC } from '../branding';
import './arc-connection-badge';

@customElement('arc-nav')
export class ArcNav extends LitElement {
  @property({ type: String }) route = '#/chat';
  @property({ type: Boolean, reflect: true }) open = false;
  @property({ type: Boolean, reflect: true }) collapsed = false;

  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  connectedCallback() {
    super.connectedCallback();
    this._restoreCollapsedPreference();
  }

  _restoreCollapsedPreference(): void {
    try {
      if (typeof localStorage !== 'undefined') {
        const saved = localStorage.getItem('amsha-nav-collapsed');
        if (saved !== null) {
          this.collapsed = saved === 'true';
        }
      }
    } catch (_) {}
  }

  private _toggleCollapse(): void {
    this.collapsed = !this.collapsed;
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('amsha-nav-collapsed', String(this.collapsed));
      }
    } catch (_) {}
    this.dispatchEvent(
      new CustomEvent('nav-collapse-changed', {
        bubbles: true,
        composed: true,
        detail: { collapsed: this.collapsed },
      })
    );
  }

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      width: 220px;
      background: var(--bg-surface);
      border-right: 1px solid var(--border);
      padding: var(--space-4) var(--space-3);
      height: 100vh;
      box-sizing: border-box;
      transition: width 0.2s cubic-bezier(0.16, 1, 0.3, 1), padding 0.2s ease;
      flex-shrink: 0;
    }
    :host([collapsed]) {
      width: 68px;
      padding: var(--space-4) 8px;
    }
    @media (max-width: 640px) {
      :host {
        position: fixed;
        left: 0;
        top: 0;
        bottom: 0;
        z-index: 100;
        transform: translateX(-100%);
        box-shadow: var(--shadow-lg);
        width: 250px;
      }
      :host([open]) {
        transform: translateX(0);
      }
    }
    .nav-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: var(--space-2) var(--space-2) var(--space-6);
      min-height: 36px;
    }
    :host([collapsed]) .nav-header {
      flex-direction: column;
      align-items: center;
      gap: 12px;
      padding: var(--space-2) 0 var(--space-6);
      justify-content: center;
    }
    .logo {
      display: flex;
      align-items: center;
      gap: 10px;
      font-weight: 700;
      font-size: 1.25rem;
      letter-spacing: -0.02em;
      min-width: 0;
      cursor: default;
    }
    :host([collapsed]) .logo {
      padding: 0;
      justify-content: center;
    }
    .logo-icon {
      width: 26px;
      height: 26px;
      display: block;
      flex-shrink: 0;
    }
    .logo-text {
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      white-space: nowrap;
    }
    .collapse-btn {
      background: transparent;
      border: 1px solid var(--border);
      border-radius: 6px;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 26px;
      height: 26px;
      padding: 0;
      transition: var(--transition);
      flex-shrink: 0;
    }
    .collapse-btn:hover {
      color: var(--text-primary);
      border-color: var(--accent);
      background: var(--accent-glow);
    }
    .links {
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex-grow: 1;
    }
    a {
      color: var(--text-muted);
      padding: 10px 14px;
      text-decoration: none;
      font-size: 14px;
      cursor: pointer;
      border-radius: 8px;
      display: flex;
      align-items: center;
      gap: 12px;
      transition: var(--transition);
      position: relative;
      font-weight: 500;
      white-space: nowrap;
    }
    :host([collapsed]) a {
      justify-content: center;
      padding: 10px 0;
      gap: 0;
    }
    :host([collapsed]) .link-label {
      display: none;
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
    :host([collapsed]) a.active::before {
      left: -8px;
    }
    a:hover:not(.active) {
      background: var(--accent-glow);
      color: var(--text-primary);
    }
    .icon {
      font-size: 16px;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 20px;
      flex-shrink: 0;
    }
    .footer {
      padding-top: var(--space-4);
      border-top: 1px solid var(--border);
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }
    :host([collapsed]) .footer {
      align-items: center;
      padding: var(--space-3) 0 0;
    }
    :host([collapsed]) .version {
      display: none;
    }
    :host([collapsed]) arc-connection-badge {
      padding: 4px;
      justify-content: center;
    }
    .version {
      font-size: 11px;
      color: var(--text-muted);
      padding-left: var(--space-3);
    }
  `;

  render() {
    return html`
      <div class="nav-header">
        <div class="logo" title="${this.collapsed ? 'Amsha Agent' : ''}">
          <img class="logo-icon" src="${LOGO_SVG_SRC}" alt="" width="26" height="26" />
          ${!this.collapsed ? html`<span class="logo-text">Amsha Agent</span>` : nothing}
        </div>
        <button
          class="collapse-btn"
          aria-label="${this.collapsed ? 'Expand navigation' : 'Collapse navigation'}"
          title="${this.collapsed ? 'Expand navigation' : 'Collapse navigation'}"
          @click=${this._toggleCollapse}
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            style="transform: ${this.collapsed ? 'rotate(180deg)' : 'none'}; transition: transform 0.2s ease;"
          >
            <polyline points="11 17 6 12 11 7"></polyline>
            <polyline points="18 17 13 12 18 7"></polyline>
          </svg>
        </button>
      </div>
      <div class="links" role="navigation">
        ${ROUTES.map(
          (r) => html`
            <a
              href="${r.hash}"
              class="${this.route === r.hash ? 'active' : ''}"
              title="${r.label}"
              aria-label="${r.label}"
              aria-current="${this.route === r.hash ? 'page' : nothing}"
            >
              <span class="icon">${r.icon}</span>
              ${!this.collapsed ? html`<span class="link-label">${r.label}</span>` : nothing}
            </a>
          `
        )}
      </div>
      <div class="footer">
        <arc-connection-badge
          .status="${this._appContext?.sseStatus ?? SseStatus.DISCONNECTED}"
          title="${this.collapsed ? `Live updates: ${this._appContext?.sseStatus ?? SseStatus.DISCONNECTED}` : ''}"
        ></arc-connection-badge>
        ${!this.collapsed ? html`<div class="version">v1.0</div>` : nothing}
      </div>
    `;
  }
}
