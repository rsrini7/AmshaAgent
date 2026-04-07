import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';

@customElement('arc-connection-badge')
export class ArcConnectionBadge extends LitElement {
  @property({ type: String }) status: 'connected' | 'disconnected' | 'error' = 'disconnected';

  static styles = css`
    :host {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 11px;
      padding: 6px 12px;
      border-radius: 99px;
      background: var(--accent-glow);
      border: 1px solid var(--accent);
      color: var(--accent);
      font-weight: 600;
      letter-spacing: 0.02em;
      transition: var(--transition);
      cursor: default;
    }
    
    .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      position: relative;
    }
    
    .status-connected .dot { 
      background: #00d4aa; 
      box-shadow: 0 0 8px #00d4aa;
    }
    .status-connected .dot::after {
      content: '';
      position: absolute;
      inset: -2px;
      border-radius: 50%;
      border: 1px solid #00d4aa;
      animation: pulse 2s infinite;
    }
    
    .status-disconnected .dot { background: var(--text-muted); opacity: 0.5; }
    .status-error .dot { background: #ef4444; box-shadow: 0 0 8px #ef4444; }

    @keyframes pulse {
      0% { transform: scale(1); opacity: 0.8; }
      100% { transform: scale(2.5); opacity: 0; }
    }

    .label {
      text-transform: uppercase;
    }
  `;

  render() {
    let label = 'Offline';
    if (this.status === 'connected') label = 'Live';
    // If status is 'error' or 'disconnected', label remains 'Offline'


    return html`
      <div class="status-${this.status}" style="display: inherit; align-items: inherit; gap: inherit;">
        <span class="dot"></span>
        <span class="label">${label}</span>
      </div>
    `;
  }
}
