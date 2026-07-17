import { LitElement, css, html } from 'lit';
import { customElement } from 'lit/decorators.js';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-welcome')
export class ArcOnboardingStepWelcome extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
        text-align: center;
      }
      
      .welcome-features {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
        margin: var(--space-6) 0;
      }

      .feature-row {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        padding: var(--space-3) var(--space-4);
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
        font-size: var(--font-size-sm);
        color: var(--text-primary);
        text-align: left;
      }

      .feature-dot {
        width: 8px;
        height: 8px;
        flex-shrink: 0;
        border-radius: var(--radius-full);
        background: linear-gradient(135deg, var(--accent), var(--accent-secondary));
      }
    `
  ];

  private _onQuickStart() {
    this.dispatchEvent(new CustomEvent('quick-start', { bubbles: true, composed: true }));
  }

  private _onAdvancedStart() {
    this.dispatchEvent(new CustomEvent('advanced-start', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <div style="padding:var(--space-2) 0 var(--space-4)">
        <p style="font-size:var(--font-size-sm);color:var(--text-muted);line-height:1.6;max-width:44ch;margin:0 auto">
          Welcome to Amsha Agent. Set up your AI agent infrastructure, connections, model profiles, and integrations in minutes.
        </p>
      </div>

      <div class="welcome-features">
        <div class="feature-row">
          <div class="feature-dot"></div>
          <div>
            <strong>Identity & Credentials First</strong>
            <div style="font-size:11px;color:var(--text-muted)">Establish secure, vault-backed, owner-scoped provider credentials.</div>
          </div>
        </div>
        <div class="feature-row">
          <div class="feature-dot"></div>
          <div>
            <strong>Multi-Model Concern Routing</strong>
            <div style="font-size:11px;color:var(--text-muted)">Map different LLMs to loop, compaction, and embedding concerns.</div>
          </div>
        </div>
        <div class="feature-row">
          <div class="feature-dot"></div>
          <div>
            <strong>Dynamic Tool Integrations</strong>
            <div style="font-size:11px;color:var(--text-muted)">Connect MCP servers, Playwright, web search, and security sandboxes.</div>
          </div>
        </div>
      </div>

      <div style="display:flex;flex-direction:column;gap:var(--space-3);margin-top:var(--space-6)">
        <button class="btn-primary" @click="${this._onQuickStart}">
          Quick Start (Recommended)
        </button>
        <div style="font-size:11px;color:var(--text-muted);margin-bottom:var(--space-2)">
          One provider, one model, applied everywhere — you can fine-tune later in Advanced Setup.
        </div>
        <button class="btn-ghost" @click="${this._onAdvancedStart}">
          Advanced Multi-Model Setup
        </button>
      </div>
    `;
  }
}
