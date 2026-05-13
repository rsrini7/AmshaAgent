import { LitElement, css, html } from 'lit';
import { customElement } from 'lit/decorators.js';
import { formStyles } from './arc-onboarding-styles';

@customElement('arc-onboarding-step6')
export class ArcOnboardingStep6 extends LitElement {
  static styles = [formStyles, css`
    :host { display: block; }
  `];

  private _finish() {
    this.dispatchEvent(new CustomEvent('finish', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <div style="text-align:center;padding:var(--space-4) 0">
        <div class="complete-icon">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="3">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </div>
        <h2 class="wizard-title" style="margin-top:var(--space-4);font-size:var(--font-size-2xl);font-weight:var(--font-weight-bold);color:var(--text-primary)">Setup Complete!</h2>
        <p class="wizard-subtitle" style="margin-bottom:var(--space-8);font-size:var(--font-size-sm);color:var(--text-muted);line-height:1.5">
          Your AI infrastructure is ready. You can now start chatting with your agent and assigning tasks.
        </p>
        <div class="btn-row">
          <button class="btn-primary" @click="${this._finish}">Go to Dashboard →</button>
        </div>
      </div>
    `;
  }
}
