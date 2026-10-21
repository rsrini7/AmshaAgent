import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { IntegrationsConfig } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-integrations')
export class ArcOnboardingStepIntegrations extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }
    `
  ];

  @property({ type: Object }) integrations: IntegrationsConfig = {
    playwrightEnabled: false,
    playwrightHeaded: false,
    braveApiKeyVaultRef: '',
    serperApiKeyVaultRef: '',
    githubTokenVaultRef: '',
    skillsjarsApprovalRequired: true,
    skillsMaxIterations: 10
  };

  private _onChange(key: keyof IntegrationsConfig, value: any) {
    const updated = { ...this.integrations, [key]: value };
    this.dispatchEvent(
      new CustomEvent('update-integrations', {
        detail: { integrations: updated },
        bubbles: true,
        composed: true
      })
    );
  }

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <span class="field-label">Outbound Integrations (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Configure external tools, search APIs, and automation scripts.
      </p>

      <div style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-3);margin-bottom:var(--space-4)">
        <strong>Speech services (Gnani)</strong>
        <p style="font-size:var(--font-size-xs);color:var(--text-muted);line-height:1.6">
          Gnani speech is an optional cloud service. When enabled, recordings or text are sent to Gnani under your account terms.
          Set these values in the deployment environment and restart Amsha. This web wizard does not edit the deployment
          <code>.env</code> file or store a service-wide speech credential.
        </p>
        <pre style="white-space:pre-wrap;overflow-wrap:anywhere;background:var(--bg-surface);border:1px solid var(--border);border-radius:var(--radius-sm);padding:var(--space-3);font-size:var(--font-size-xs)"><code>AMSHA_GNANI_API_KEY=&lt;api-key&gt;
# Optional transcription
AMSHA_STT_GNANI_ENABLED=true
# Optional language override (default en-IN)
# AMSHA_STT_GNANI_DEFAULT_LANGUAGE=hi-IN
# Optional speech replies
AMSHA_TTS_ENABLED=true
AMSHA_TTS_PROVIDER=gnani
# Defaults: timbre-v3.0 model, Shivani voice
# Optional default reply mode (otherwise TEXT_ONLY)
# AMSHA_TTS_DEFAULT_OUTPUT_MODE=TEXT_AND_AUDIO</code></pre>
        <p style="font-size:var(--font-size-xs);color:var(--text-muted);line-height:1.6;margin-bottom:0">
          Transcription defaults to <code>en-IN</code> and a 30-second maximum. Supported STT languages are
          <code>bn-IN</code>, <code>en-IN</code>, <code>gu-IN</code>, <code>hi-IN</code>, <code>kn-IN</code>,
          <code>ml-IN</code>, <code>mr-IN</code>, <code>pa-IN</code>, <code>ta-IN</code>, and <code>te-IN</code>.
          Speech replies still follow each user's output-mode preference; the default mode remains <code>TEXT_ONLY</code>.
        </p>
      </div>

      <div style="display:flex;flex-direction:column;gap:var(--space-4);margin-bottom:var(--space-5)">
        <div style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-3)">
          <label style="display:flex;align-items:center;gap:var(--space-2);font-size:var(--font-size-sm);color:var(--text-primary);font-weight:var(--font-weight-semi)">
            <input type="checkbox" ?checked="${this.integrations.playwrightEnabled}"
                   @change="${(e: any) => this._onChange('playwrightEnabled', e.target.checked)}" />
            Enable Playwright Browser Tool
          </label>
        </div>

        <div>
          <span class="field-label">Brave Search API Key</span>
          <input class="field-input" type="password" placeholder="Enter Brave Search API key"
                 .value="${this.integrations.braveApiKeyVaultRef || ''}"
                 @input="${(e: any) => this._onChange('braveApiKeyVaultRef', e.target.value)}" />
        </div>

        <div>
          <span class="field-label">Serper Search API Key</span>
          <input class="field-input" type="password" placeholder="Enter Serper API key"
                 .value="${this.integrations.serperApiKeyVaultRef || ''}"
                 @input="${(e: any) => this._onChange('serperApiKeyVaultRef', e.target.value)}" />
        </div>

        <div>
          <span class="field-label">GitHub Token</span>
          <input class="field-input" type="password" placeholder="Enter GitHub Personal Access Token"
                 .value="${this.integrations.githubTokenVaultRef || ''}"
                 @input="${(e: any) => this._onChange('githubTokenVaultRef', e.target.value)}" />
        </div>

        <div style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-3)">
          <label style="display:flex;align-items:center;gap:var(--space-2);font-size:var(--font-size-sm);color:var(--text-primary)">
            <input type="checkbox" ?checked="${this.integrations.skillsjarsApprovalRequired}"
                   @change="${(e: any) => this._onChange('skillsjarsApprovalRequired', e.target.checked)}" />
            Require approval for sensitive tools
          </label>
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
