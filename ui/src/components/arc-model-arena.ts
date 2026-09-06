import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ArcClient, ArcApiError, ArenaCompareResponse, ArenaModelResult } from '../api/client';

@customElement('arc-model-arena')
export class ArcModelArena extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @property({ type: String }) prompt = 'Compare the efficiency and safety guarantees of synchronous vs asynchronous event handling.';
  @property({ type: Array }) selectedModels: string[] = ['omlx', 'generic-openai'];
  @property({ type: Number }) maxTokens = 512;

  @state() private _availableSlots = ['omlx', 'generic-openai', 'cerebras', 'groq'];
  @state() private _loading = false;
  @state() private _error: string | null = null;
  @state() private _isUnavailable = false;
  @state() private _authStatus: 401 | 403 | null = null;
  @state() private _response: ArenaCompareResponse | null = null;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      padding: var(--space-6, 24px);
      box-sizing: border-box;
      gap: var(--space-4, 16px);
      background: var(--bg-base, #0a0b0f);
      color: var(--text-primary, #ffffff);
      overflow-y: auto;
    }

    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: 12px;
    }

    .title-group h2 {
      margin: 0 0 4px 0;
      font-size: 1.25rem;
      font-weight: 700;
      letter-spacing: -0.02em;
    }

    .subtitle {
      margin: 0;
      font-size: 12px;
      color: var(--text-muted, #8b949e);
    }

    .config-box {
      display: flex;
      flex-direction: column;
      gap: 12px;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      padding: 16px;
    }

    .model-selector {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .section-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-muted, #8b949e);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .model-chips {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }

    .model-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 6px;
      border: 1px solid var(--border, #242938);
      background: var(--bg-elevated, #1a202c);
      color: var(--text-muted, #8b949e);
      font-size: 12px;
      cursor: pointer;
      user-select: none;
      font-family: monospace;
    }

    .model-chip.selected {
      border-color: var(--accent, #6c63ff);
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      color: var(--text-primary, #ffffff);
      font-weight: 600;
    }

    textarea {
      width: 100%;
      box-sizing: border-box;
      background: var(--bg-elevated, #1a202c);
      border: 1px solid var(--border, #242938);
      color: var(--text-primary, #ffffff);
      font-size: 13px;
      padding: 10px;
      border-radius: 6px;
      resize: vertical;
      min-height: 80px;
      font-family: inherit;
    }

    textarea:focus {
      outline: none;
      border-color: var(--accent, #6c63ff);
    }

    .actions-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .actions-bar-right {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    select, button {
      padding: 6px 12px;
      border-radius: 6px;
      border: 1px solid var(--border, #242938);
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
      font-size: 12px;
      cursor: pointer;
      font-weight: 600;
    }

    .compare-btn {
      background: var(--accent, #6c63ff);
      border-color: var(--accent, #6c63ff);
      color: #ffffff;
    }

    .compare-btn:hover:not(:disabled) {
      background: color-mix(in srgb, var(--accent, #6c63ff) 80%, #ffffff);
    }

    .compare-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .unavailable-notice {
      padding: 16px 20px;
      border-radius: 8px;
      background: rgba(245, 158, 11, 0.1);
      border: 1px solid rgba(245, 158, 11, 0.3);
      color: #f59e0b;
      font-size: 13px;
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .error-banner {
      padding: 12px 16px;
      border-radius: 6px;
      background: rgba(239, 68, 68, 0.15);
      border: 1px solid rgba(239, 68, 68, 0.35);
      color: #ef4444;
      font-size: 12px;
      font-family: monospace;
    }

    .results-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 16px;
      flex: 1;
      min-height: 0;
    }

    .model-card {
      display: flex;
      flex-direction: column;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      overflow: hidden;
    }

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 14px;
      background: var(--bg-elevated, #1a202c);
      border-bottom: 1px solid var(--border, #242938);
    }

    .model-title {
      font-weight: 700;
      font-family: monospace;
      color: var(--text-primary, #ffffff);
    }

    .telemetry-pills {
      display: flex;
      gap: 6px;
      align-items: center;
      flex-wrap: wrap;
      padding: 8px 14px;
      background: color-mix(in srgb, var(--bg-surface, #131722) 50%, transparent);
      border-bottom: 1px solid color-mix(in srgb, var(--border, #242938) 50%, transparent);
      font-size: 11px;
      color: var(--text-muted, #8b949e);
    }

    .telemetry-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 70%, transparent);
      padding: 2px 6px;
      border-radius: 4px;
      font-family: monospace;
    }

    .telemetry-pill.warn {
      background: color-mix(in srgb, #f59e0b 20%, transparent);
      color: #f59e0b;
      border: 1px solid color-mix(in srgb, #f59e0b 40%, transparent);
    }

    .telemetry-pill.error {
      background: color-mix(in srgb, #ef4444 20%, transparent);
      color: #ef4444;
      border: 1px solid color-mix(in srgb, #ef4444 40%, transparent);
    }

    .card-body {
      flex: 1;
      min-height: 120px;
      padding: 14px;
      overflow-y: auto;
      font-size: 13px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-word;
    }
  `;

  private _toggleModel(slot: string) {
    if (this.selectedModels.includes(slot)) {
      if (this.selectedModels.length > 1) {
        this.selectedModels = this.selectedModels.filter((m) => m !== slot);
      }
    } else {
      if (this.selectedModels.length < 3) {
        this.selectedModels = [...this.selectedModels, slot];
      }
    }
  }

  private _formatModelOutput(output: string): string {
    if (!output) return 'No response output returned.';
    if (output === 'Evaluating...') {
      return '⏱ Benchmarking model... (up to 30s deadline)';
    }
    if (output === 'PROVIDER_TIMEOUT') {
      return '⚠️ TIMEOUT: The model provider did not respond within the 30-second deadline. The endpoint may be hanging, rate-limited, or unreachable. (PROVIDER_TIMEOUT)';
    }
    if (output === 'PROVIDER_AUTHENTICATION_ERROR') {
      return '⚠️ AUTHENTICATION ERROR: Missing or invalid API key for this provider. Check your environment configuration. (PROVIDER_AUTHENTICATION_ERROR)';
    }
    if (output === 'PROVIDER_RATE_LIMIT') {
      return '⚠️ RATE LIMITED: Provider quota or rate limit reached (HTTP 429). Please retry later. (PROVIDER_RATE_LIMIT)';
    }
    if (output === 'PROVIDER_UNKNOWN_MODEL') {
      return '⚠️ UNKNOWN MODEL: The specified model name is not recognized or available on this provider. (PROVIDER_UNKNOWN_MODEL)';
    }
    if (output === 'PROVIDER_EXECUTION_ERROR') {
      return '⚠️ EXECUTION ERROR: An error occurred while communicating with the provider endpoint. (PROVIDER_EXECUTION_ERROR)';
    }
    if (output === 'PROVIDER_UNSUPPORTED_CAPABILITY') {
      return '⚠️ UNSUPPORTED CAPABILITY: This model does not support text generation capability. (PROVIDER_UNSUPPORTED_CAPABILITY)';
    }
    if (output === 'PROVIDER_INTERRUPTED') {
      return '⚠️ INTERRUPTED: Model evaluation was interrupted before completion. (PROVIDER_INTERRUPTED)';
    }
    return output;
  }

  async runCompare() {
    if (!this.prompt.trim() || this.selectedModels.length === 0 || this._loading) return;
    this._loading = true;
    this._error = null;
    this._response = null;
    this._authStatus = null;
    this._isUnavailable = false;

    try {
      const client = new ArcClient('/api/v1', this._appContext?.apiKey);
      const res = await client.compareArena({
        prompt: this.prompt.trim(),
        models: this.selectedModels,
        maxTokens: this.maxTokens,
      });
      this._response = res;
      this._isUnavailable = false;
      this._authStatus = null;
    } catch (err: any) {
      if (err instanceof ArcApiError && err.status === 401) {
        this._authStatus = 401;
        this._isUnavailable = true;
      } else if (err instanceof ArcApiError && err.status === 403) {
        const msg = (err.message || '').toLowerCase();
        if (msg.includes('csrf')) {
          this._authStatus = null;
          this._isUnavailable = false;
          this._error = err.message || 'CSRF validation failed. Please refresh or re-authenticate.';
        } else {
          this._authStatus = 403;
          this._isUnavailable = true;
        }
      } else {
        this._authStatus = null;
        this._error = err.message || 'Model arena comparison failed.';
      }
    } finally {
      this._loading = false;
    }
  }

  render() {
    return html`
      <div class="header">
        <div class="title-group">
          <h2>Model Arena</h2>
          <p class="subtitle">
            Side-by-side LLM provider benchmark. Evaluates latency, token counts, and cost.
          </p>
        </div>
      </div>

      ${this._authStatus === 401
        ? html`
            <div class="unavailable-notice" role="alert">
              <span>⚠</span>
              <span>Authentication Expired or Missing API Key (401)</span>
            </div>
          `
        : this._authStatus === 403 || this._isUnavailable
        ? html`
            <div class="unavailable-notice" role="alert">
              <span>⚠</span>
              <span>Operator Surface Unavailable (Internal Role Required)</span>
            </div>
          `
        : nothing}

      <div class="config-box">
        <div class="model-selector">
          <span class="section-label">Select Models (1 - 3):</span>
          <div class="model-chips">
            ${this._availableSlots.map(
              (slot) => html`
                <div
                  class="model-chip ${this.selectedModels.includes(slot) ? 'selected' : ''}"
                  @click="${() => this._toggleModel(slot)}"
                  role="checkbox"
                  aria-checked="${this.selectedModels.includes(slot)}"
                >
                  <span>${this.selectedModels.includes(slot) ? '✓' : '+'}</span>
                  <span>${slot}</span>
                </div>
              `
            )}
          </div>
        </div>

        <textarea
          .value="${this.prompt}"
          @input="${(e: Event) => (this.prompt = (e.target as HTMLTextAreaElement).value)}"
          placeholder="Enter benchmark prompt..."
          maxlength="2048"
          aria-label="Benchmark prompt input"
        ></textarea>

        <div class="actions-bar">
          <span style="font-size: 11px; color: var(--text-muted, #8b949e);">
            ${this.prompt.length} / 2048 characters
          </span>
          <div class="actions-bar-right">
            <label style="font-size: 11px; color: var(--text-muted, #8b949e);">
              Max Tokens:
              <select
                .value="${String(this.maxTokens)}"
                @change="${(e: Event) =>
                  (this.maxTokens = Number.parseInt((e.target as HTMLSelectElement).value, 10))}"
              >
                <option value="256">256</option>
                <option value="512">512</option>
                <option value="1024">1024</option>
              </select>
            </label>
            <button
              class="compare-btn"
              @click="${this.runCompare}"
              ?disabled="${this._loading || !this.prompt.trim() || this.selectedModels.length === 0}"
            >
              ${this._loading ? 'Comparing (30s deadline)...' : 'Run Benchmark'}
            </button>
          </div>
        </div>
      </div>

      ${this._error ? html`<div class="error-banner" role="alert">${this._error}</div>` : nothing}

      ${this._response
        ? html`
            <div style="font-size: 12px; color: var(--text-muted, #8b949e);">
              Total Duration: <strong style="color: #fff;">${this._response.totalDurationMs}ms</strong> |
              Models: <strong style="color: #fff;">${this._response.results.length}</strong>
            </div>
            <div class="results-grid">
              ${this._response.results.map((res) => {
                const isEvaluating = res.output === 'Evaluating...';
                const isTimeout = res.output.includes('TIMEOUT');
                const isError = !isTimeout && (res.output.includes('ERROR') || res.output.startsWith('PROVIDER_'));

                return html`
                  <div class="model-card">
                    <div class="card-header">
                      <span class="model-title">${res.modelSlot}</span>
                    </div>
                    <div class="telemetry-pills">
                      ${isEvaluating
                        ? html`<span class="telemetry-pill">⏱ Evaluating...</span>`
                        : isTimeout
                        ? html`
                            <span class="telemetry-pill warn">⏱ ${res.durationMs}ms</span>
                            <span class="telemetry-pill warn">⚠️ Timed Out</span>
                          `
                        : isError
                        ? html`
                            <span class="telemetry-pill error">⏱ ${res.durationMs}ms</span>
                            <span class="telemetry-pill error">⚠️ Error</span>
                          `
                        : html`
                            <span class="telemetry-pill">⏱ ${res.durationMs}ms</span>
                            <span class="telemetry-pill">📥 ${res.inputTokens} tok</span>
                            <span class="telemetry-pill">📤 ${res.outputTokens} tok</span>
                            <span class="telemetry-pill" title="Estimated USD (placeholder rates)">💰 $${(res.costUsd ?? 0).toFixed(5)}</span>
                          `}
                    </div>
                    <div
                      class="card-body"
                      style="${isEvaluating
                        ? 'color: var(--text-muted, #8b949e); font-style: italic;'
                        : isTimeout
                        ? 'color: #f59e0b;'
                        : isError
                        ? 'color: #ef4444;'
                        : ''}"
                    >
                      ${this._formatModelOutput(res.output)}
                    </div>
                  </div>
                `;
              })}
            </div>
          `
        : nothing}
    `;
  }
}
