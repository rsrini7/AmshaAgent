import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { IdentityConfig } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-identity')
export class ArcOnboardingStepIdentity extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }
    `
  ];

  @property({ type: Object }) identity: IdentityConfig = {
    oidcClientId: '',
    oidcClientSecretVaultRef: '',
    oidcAuthUrl: '',
    oidcTokenUrl: '',
    oidcUserinfoUrl: '',
    oidcjwksUrl: '',
    oidcRedirectUri: ''
  };

  private _onChange(key: keyof IdentityConfig, value: any) {
    const updated = { ...this.identity, [key]: value };
    this.dispatchEvent(
      new CustomEvent('update-identity', {
        detail: { identity: updated },
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
      <span class="field-label">External Identity & OIDC (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Configure external Identity Provider (IDP) protocols for OAuth and single-sign-on (SSO).
      </p>

      <div style="display:flex;flex-direction:column;gap:var(--space-4);margin-bottom:var(--space-5)">
        <div>
          <span class="field-label">OIDC Client ID</span>
          <input class="field-input" type="text" placeholder="Enter Client ID"
                 .value="${this.identity.oidcClientId || ''}"
                 @input="${(e: any) => this._onChange('oidcClientId', e.target.value)}" />
        </div>

        <div>
          <span class="field-label">OIDC Client Secret</span>
          <input class="field-input" type="password" placeholder="Enter Client Secret"
                 .value="${this.identity.oidcClientSecretVaultRef || ''}"
                 @input="${(e: any) => this._onChange('oidcClientSecretVaultRef', e.target.value)}" />
        </div>

        <div>
          <span class="field-label">Authorization URL</span>
          <input class="field-input" type="url" placeholder="https://idp.example.com/oauth/authorize"
                 .value="${this.identity.oidcAuthUrl || ''}"
                 @input="${(e: any) => this._onChange('oidcAuthUrl', e.target.value)}" />
        </div>

        <div>
          <span class="field-label">Token URL</span>
          <input class="field-input" type="url" placeholder="https://idp.example.com/oauth/token"
                 .value="${this.identity.oidcTokenUrl || ''}"
                 @input="${(e: any) => this._onChange('oidcTokenUrl', e.target.value)}" />
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
