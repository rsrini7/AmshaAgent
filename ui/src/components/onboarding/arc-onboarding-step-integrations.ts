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
