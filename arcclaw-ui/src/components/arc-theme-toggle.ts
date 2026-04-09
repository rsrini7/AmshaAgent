import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';

@customElement('arc-theme-toggle')
export class ArcThemeToggle extends LitElement {
  @state() private _theme = 'dark';

  static styles = css`
    button {
      width: 40px;
      height: 40px;
      border: 1px solid var(--border);
      border-radius: var(--radius-full);
      background: var(--bg-elevated);
      color: var(--text-muted);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      transition:
        background var(--transition),
        color var(--transition),
        border-color var(--transition),
        box-shadow var(--transition),
        transform var(--transition);
      backdrop-filter: blur(10px);
      box-shadow: var(--shadow-sm);
    }

    button:hover {
      background: var(--accent-subtle);
      color: var(--accent);
      border-color: var(--border-focus);
      box-shadow: 0 0 0 3px var(--accent-glow);
      transform: translateY(-1px);
    }
    
    svg { pointer-events: none; }
  `;

  connectedCallback() {
    super.connectedCallback();
    this._theme = document.documentElement.getAttribute('data-theme') || 'dark';
  }

  private _toggle() {
    this._theme = this._theme === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', this._theme);
    sessionStorage.setItem('arc-theme', this._theme);
  }

  render() {
    return html`
      <button aria-label="Switch to ${this._theme === 'dark' ? 'light' : 'dark'} mode" title="Toggle light/dark" @click="${this._toggle}">
        ${this._theme === 'light' ? html`
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="5"/>
            <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/>
          </svg>
        ` : html`
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
          </svg>
        `}
      </button>
    `;
  }
}
