import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';

@customElement('arc-connection-badge')
export class ArcConnectionBadge extends LitElement {
  @property({ type: String }) status: 'connected' | 'disconnected' | 'error' = 'disconnected';
  @property({ type: Number }) lastHeartbeat: number = 0;

  static styles = css`
    :host {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: var(--font-size-sm, 0.875rem);
      padding: 4px 8px;
      border-radius: var(--radius-full, 9999px);
      background: var(--color-bg-elevated, #334155);
    }
    .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
    }
    .status-connected .dot { background: var(--color-success, #22c55e); }
    .status-disconnected .dot { background: var(--color-text-secondary, #94a3b8); }
    .status-error .dot { background: var(--color-error, #ef4444); }
    
    .status-connected { color: var(--color-success, #22c55e); }
    .status-disconnected { color: var(--color-text-secondary, #94a3b8); }
    .status-error { color: var(--color-error, #ef4444); }
    
    .label {
      font-weight: var(--font-weight-medium, 500);
      color: var(--color-text-primary, #f1f5f9);
    }
  `;

  render() {
    let label = 'Offline';
    if (this.status === 'connected') label = 'Live';
    else if (this.status === 'error') label = 'Error';

    return html`
      <div class="status-${this.status}" style="display: inherit; align-items: inherit; gap: inherit;">
        <span class="dot"></span>
        <span class="label">${label}</span>
      </div>
    `;
  }
}
