import { LitElement, css, html } from 'lit';
import { consume } from '@lit/context';
import { customElement, property, state } from 'lit/decorators.js';
import { ArcClient } from '../../api/client';
import { IntegrationsConfig } from '../../api/types';
import { ArcAppContext, arcContext } from '../../context/arc-context';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-integrations')
export class ArcOnboardingStepIntegrations extends LitElement {
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

  // Keep the legacy draft property readable; these deployment settings are not applied by this page.
  @property({ type: Object }) integrations: IntegrationsConfig = {
    playwrightEnabled: false,
    playwrightHeaded: false,
    braveApiKeyVaultRef: '',
    serperApiKeyVaultRef: '',
    githubTokenVaultRef: '',
    skillsjarsApprovalRequired: true,
    skillsMaxIterations: 10
  };

  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext?: ArcAppContext;

  @state()
  private _githubCredentialStatus: 'checking' | 'configured' | 'missing' | 'unavailable' = 'checking';

  @state()
  private _githubConnectorEnabled: boolean | null = null;

  private get _client(): ArcClient {
    return new ArcClient('/api/v1', this._appContext?.apiKey || '');
  }

  protected firstUpdated(): void {
    void this._refreshGitHubCredentialStatus();
  }

  private _refreshGitHubCredentialStatus = async (): Promise<void> => {
    this._githubCredentialStatus = 'checking';
    try {
      const status = await this._client.getGitHubAppCredentialStatus();
      this._githubConnectorEnabled = status.enabled;
      this._githubCredentialStatus = status.configured ? 'configured' : 'missing';
    } catch {
      this._githubConnectorEnabled = null;
      this._githubCredentialStatus = 'unavailable';
    }
  };

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <span class="field-label">Integrations (Optional)</span>
      <p class="status-msg">
        These integrations read deployment settings at startup. This browser wizard cannot write the server's
        <code>.env</code> file or activate them. Configure them with the startup CLI or your deployment environment,
        then restart Amsha. Do not paste service credentials into this page.
      </p>

      <section class="setup-card">
        <h3>Search, GitHub, and browser automation</h3>
        <p>Only set the keys for tools you plan to use. Playwright stays disabled unless you explicitly enable it.</p>
        <pre><code>AMSHA_TOOLS_BRAVE_API_KEY=&lt;optional-brave-key&gt;
AMSHA_TOOLS_SERPER_API_KEY=&lt;optional-serper-key&gt;
AMSHA_TOOLS_GITHUB_TOKEN=&lt;optional-github-token&gt;
AMSHA_TOOLS_PLAYWRIGHT_ENABLED=false
AMSHA_TOOLS_PLAYWRIGHT_HEADLESS=true
AMSHA_TOOLS_PLAYWRIGHT_MCP_ENABLED=false</code></pre>
      </section>

      <section class="setup-card">
        <h3>GitHub documentation connector</h3>
        <p>
          This connector is disabled by default. To enable it, set
          <code>AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=true</code> in the server's <code>.env</code> and
          restart Amsha. When disabled, connect, refresh, and prompt retrieval stay off.
        </p>
        <p role="status" aria-live="polite">
          ${this._githubConnectorEnabled === null
            ? this._githubCredentialStatus === 'checking'
              ? 'Checking GitHub documentation connector status…'
              : 'Amsha could not check whether the GitHub documentation connector is enabled.'
            : this._githubConnectorEnabled
              ? 'GitHub documentation connector is enabled.'
              : 'GitHub documentation connector is disabled. It will not index or retrieve repository content.'}
        </p>
        <p role="status" aria-live="polite">
          ${this._githubCredentialStatus === 'checking'
            ? 'Checking GitHub App credential in Amsha SecretVault…'
            : this._githubCredentialStatus === 'configured'
              ? 'GitHub App credential is present in Amsha SecretVault.'
              : this._githubCredentialStatus === 'missing'
                ? 'GitHub App credential is missing from SecretVault. With SQLite, deleting the configured data directory removes it.'
                : 'Amsha could not check the GitHub App credential. Internal access may be required, or the server may be unavailable.'}
        </p>
        ${this._githubConnectorEnabled !== false
          && (this._githubCredentialStatus === 'missing' || this._githubCredentialStatus === 'unavailable')
          ? html`
              <p>After restoring Amsha, upload the protected PEM backup again:</p>
              <pre><code>mise run github:app-credential-upload -- --key-file "$HOME/.config/amsha/secrets/amsha-github-app.pem" --env-file dist/.env</code></pre>
            `
          : ''}
        <button
          type="button"
          class="btn-ghost"
          ?disabled=${this._githubCredentialStatus === 'checking'}
          @click="${this._refreshGitHubCredentialStatus}"
        >
          Check credential again
        </button>
        <p>
          This GitHub App is separate from <code>AMSHA_TOOLS_GITHUB_TOKEN</code> above. The App ID is the
          numeric value labeled App ID on the GitHub App's General page, not the Client ID or Client secret.
          Amsha uses it with the App's private key to identify
          the App, then request a short-lived installation token limited to the repository you install it on.
          Set <code>AMSHA_GITHUB_APP_ID</code> in the server's <code>.env</code>. Store the generated private key
          through the internal credential endpoint; never paste it into this page or <code>.env</code>.
        </p>
        <pre><code>GitHub App setup for rsrini7/AmshaAgent:
Homepage URL: https://github.com/rsrini7/AmshaAgent
Repository permissions: Contents = Read-only; Metadata = Read-only
Callback URL and Setup URL: leave blank
Request user authorization (OAuth) during installation: unchecked
Expire user authorization tokens: keep checked
Webhook Active: off; leave Webhook URL and secret blank
Where can this app be installed?: Only on this account (@rsrini7)
At installation: Only select repositories &gt; rsrini7/AmshaAgent

AMSHA_GITHUB_APP_ID=&lt;numeric-app-id&gt;</code></pre>
        <p>
          The Homepage URL is an informational link; the repository URL is suitable when Amsha has no public
          website. This connector does not use OAuth redirects or GitHub webhooks. See
          <code>docs/GITHUB_DOCUMENTATION_CONNECTOR.md</code> for the credential endpoint and refresh steps.
        </p>
      </section>

      <section class="setup-card">
        <h3>Speech: local, OMLX, or Gnani</h3>
        <p>
          Local ONNX Whisper and OMLX Whisper are available for transcription. Gnani is an optional cloud tier; it
          receives audio only after governed media checks and only when enabled. The conservative Gnani duration cap
          is 30 seconds and its default language is <code>en-IN</code> (Indian English).
        </p>
        <pre><code># Choose the local ONNX transcription path
AMSHA_STT_LOCAL_ENABLED=true
AMSHA_ONNX_ENABLED=true

# Or enable OMLX transcription
AMSHA_STT_ENABLED=true

# Optional Gnani cloud transcription
AMSHA_STT_GNANI_ENABLED=true
AMSHA_STT_GNANI_DEFAULT_LANGUAGE=en-IN
AMSHA_GNANI_API_KEY=&lt;gnani-key&gt;

# Optional Gnani speech replies; OMLX remains the default TTS provider
AMSHA_TTS_ENABLED=true
AMSHA_TTS_PROVIDER=gnani</code></pre>
        <p>
          Choose a transcription path based on your data-egress requirements. Gnani speech configuration and limits
          are documented in <code>docs/CONFIGURATION.md</code>.
        </p>
      </section>

      <section class="setup-card">
        <h3>Embeddings</h3>
        <p>
          Embedding routing is configured separately from chat models. Supported providers include Ollama, Jina,
          OpenAI, and ONNX; ONNX requires a verified local model bundle.
        </p>
        <pre><code>AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=ollama
# For Jina or OpenAI, choose the provider above and set its key:
AMSHA_EMBEDDING_JINA_API_KEY=&lt;optional-jina-key&gt;
AMSHA_EMBEDDING_OPENAI_API_KEY=&lt;optional-openai-key&gt;</code></pre>
      </section>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
