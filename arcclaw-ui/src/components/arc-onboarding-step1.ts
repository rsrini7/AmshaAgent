import { LitElement, css, html } from 'lit';
import { customElement } from 'lit/decorators.js';
import { formStyles } from './arc-onboarding-styles';

@customElement('arc-onboarding-step1')
export class ArcOnboardingStep1 extends LitElement {
  static styles = [formStyles, css`
    :host { display: block; }
  `];

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <div style="text-align:center;padding:var(--space-2) 0 var(--space-4)">
        <p style="font-size:var(--font-size-sm);color:var(--text-muted);
                  line-height:1.6;max-width:36ch;margin:0 auto">
          Configure your AI agent infrastructure in 4 quick steps.
        </p>
      </div>
      <div class="welcome-features">
        ${['Choose your LLM provider and model', 'Validate your API credentials securely',
           "Define your agent's system behaviour", 'Optionally connect MCP tool servers']
          .map(text => html`
            <div class="feature-row">
              <div class="feature-dot"></div>
              ${text}
            </div>`)}
      </div>
      <div class="btn-row">
        <button class="btn-primary" @click="${this._next}">Get Started →</button>
      </div>
    `;
  }
}
