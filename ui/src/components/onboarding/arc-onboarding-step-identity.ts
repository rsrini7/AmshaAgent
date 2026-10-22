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
      .setup-card {
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
        padding: var(--space-4);
        margin-bottom: var(--space-4);
      }
      .setup-card h3 {
        margin: 0 0 var(--space-2);
        color: var(--text-primary);
        font-size: var(--font-size-sm);
      }
      .setup-card p {
        color: var(--text-muted);
        font-size: var(--font-size-xs);
        line-height: 1.6;
      }
      pre {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        background: var(--bg-surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
        font-size: var(--font-size-xs);
      }
    `
  ];

  // Keep the legacy draft contract readable; identity configuration is deployment-level.
  @property({ type: Object }) identity: IdentityConfig = {
    oidcClientId: '',
    oidcClientSecretVaultRef: '',
    oidcAuthUrl: '',
    oidcTokenUrl: '',
    oidcUserinfoUrl: '',
    oidcjwksUrl: '',
    oidcRedirectUri: ''
  };

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <span class="field-label">External Identity (Optional)</span>
      <p class="status-msg">
        Identity-provider settings are read by the server at startup. The browser wizard cannot safely update the
        deployment's authentication configuration. Configure these values in the deployment environment and restart
        Amsha; never paste client secrets here.
      </p>

      <section class="setup-card">
        <h3>Generic OIDC</h3>
        <pre><code>AMSHA_AUTH_IDP_OIDC_ENABLED=true
AMSHA_AUTH_IDP_OIDC_CLIENT_ID=&lt;client-id&gt;
AMSHA_AUTH_IDP_OIDC_CLIENT_SECRET=&lt;client-secret&gt;
AMSHA_AUTH_IDP_OIDC_ISSUER=&lt;issuer-url&gt;
AMSHA_AUTH_IDP_OIDC_AUTHORIZATION_URL=&lt;authorization-url&gt;
AMSHA_AUTH_IDP_OIDC_TOKEN_URL=&lt;token-url&gt;
AMSHA_AUTH_IDP_OIDC_USERINFO_URL=&lt;userinfo-url&gt;
AMSHA_AUTH_IDP_OIDC_JWKS_URL=&lt;jwks-url&gt;
AMSHA_AUTH_IDP_OIDC_REDIRECT_URI=&lt;registered-redirect-uri&gt;</code></pre>
      </section>

      <section class="setup-card">
        <h3>GitHub identity</h3>
        <pre><code>AMSHA_AUTH_IDP_GITHUB_ENABLED=true
AMSHA_AUTH_IDP_GITHUB_CLIENT_ID=&lt;client-id&gt;
AMSHA_AUTH_IDP_GITHUB_CLIENT_SECRET=&lt;client-secret&gt;
AMSHA_AUTH_IDP_GITHUB_REDIRECT_URI=&lt;registered-redirect-uri&gt;</code></pre>
      </section>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
