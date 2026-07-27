import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { SecurityConfig } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-security')
export class ArcOnboardingStepSecurity extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }
      .grid-2 {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--space-4);
        margin-bottom: var(--space-4);
      }
    `
  ];

  @property({ type: Object }) security: SecurityConfig = {
    rateLimitRequestsPerMin: 60,
    rateLimitMutationsPerMin: 15,
    sessionIdleMinutes: 30,
    sandboxStrategy: 'docker',
    sandboxMemoryLimit: 512,
    sandboxCpuLimit: 1,
    archivePassVaultRef: '',
    hostExecSkills: []
  };

  private _onChange(key: keyof SecurityConfig, value: any) {
    const updated = { ...this.security, [key]: value };
    this.dispatchEvent(
      new CustomEvent('update-security', {
        detail: { security: updated },
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
      <span class="field-label">Security & Sandbox Governance (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Expose rate limits, session timeout thresholds, and execution sandbox controls.
      </p>

      <div class="grid-2">
        <div>
          <span class="field-label">Requests / Min</span>
          <input class="field-input" type="number" .value="${this.security.rateLimitRequestsPerMin ?? 60}"
                 @input="${(e: any) => this._onChange('rateLimitRequestsPerMin', Number(e.target.value))}" />
        </div>
        <div>
          <span class="field-label">Mutations / Min</span>
          <input class="field-input" type="number" .value="${this.security.rateLimitMutationsPerMin ?? 15}"
                 @input="${(e: any) => this._onChange('rateLimitMutationsPerMin', Number(e.target.value))}" />
        </div>
      </div>

      <div class="grid-2">
        <div>
          <span class="field-label">Session Idle Timeout (min)</span>
          <input class="field-input" type="number" .value="${this.security.sessionIdleMinutes ?? 30}"
                 @input="${(e: any) => this._onChange('sessionIdleMinutes', Number(e.target.value))}" />
        </div>
        <div>
          <span class="field-label">Sandbox Strategy</span>
          <select class="field-select" .value="${this.security.sandboxStrategy || 'docker'}"
                  @change="${(e: any) => this._onChange('sandboxStrategy', e.target.value)}">
            <option value="docker">Docker Sandbox (Isolated)</option>
            <option value="podman">Podman Sandbox</option>
            <option value="local">Local Process Host (Unsafe)</option>
          </select>
        </div>
      </div>

      <div class="grid-2">
        <div>
          <span class="field-label">Sandbox Memory Cap (MB)</span>
          <input class="field-input" type="number" .value="${this.security.sandboxMemoryLimit ?? 512}"
                 @input="${(e: any) => this._onChange('sandboxMemoryLimit', Number(e.target.value))}" />
        </div>
        <div>
          <span class="field-label">Sandbox CPU Limit</span>
          <input class="field-input" type="number" .value="${this.security.sandboxCpuLimit ?? 1}"
                 @input="${(e: any) => this._onChange('sandboxCpuLimit', Number(e.target.value))}" />
        </div>
      </div>

      <div style="margin-bottom:var(--space-5)">
        <span class="field-label">Archive Password (7z Pass)</span>
        <input class="field-input" type="password" placeholder="Enter secure archive password"
               .value="${this.security.archivePassVaultRef || ''}"
               @input="${(e: any) => this._onChange('archivePassVaultRef', e.target.value)}" />
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
