import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ArcClient, ArcApiError, ArenaCompareResponse, ArenaModelResult } from '../api/client';

interface ArenaProvider {
  id: string;
  name: string;
  category: string;
  defaultModel: string;
}

const ARENA_PROVIDERS: ArenaProvider[] = [
  // Local / Self-Hosted
  { id: 'omlx', name: 'OMLX (Apple Silicon MLX)', category: 'Local / Self-Hosted', defaultModel: 'mlx-community/Qwen3.6-35B-A3B-4bit' },
  { id: 'ollama', name: 'Ollama (Local)', category: 'Local / Self-Hosted', defaultModel: 'mistral-small:latest' },
  { id: 'llamacpp', name: 'llama.cpp Server', category: 'Local / Self-Hosted', defaultModel: 'qwen3' },

  // Ultra-Fast Cloud
  { id: 'groq', name: 'Groq (LPU)', category: 'Ultra-Fast Cloud', defaultModel: 'meta-llama/llama-4-scout-17b-16e-instruct' },
  { id: 'cerebras', name: 'Cerebras (CS-3)', category: 'Ultra-Fast Cloud', defaultModel: 'qwen-3.8-27b' },
  { id: 'cloudflare', name: 'Cloudflare Workers AI', category: 'Ultra-Fast Cloud', defaultModel: '@cf/meta/llama-3.3-70b-instruct-fp8-fast' },

  // Frontier APIs
  { id: 'openai', name: 'OpenAI', category: 'Frontier APIs', defaultModel: 'gpt-4o-mini' },
  { id: 'gemini', name: 'Google Gemini', category: 'Frontier APIs', defaultModel: 'gemini-2.5-flash-lite' },
  { id: 'anthropic', name: 'Anthropic Claude', category: 'Frontier APIs', defaultModel: 'claude-haiku-4-5' },
  { id: 'mistral', name: 'Mistral AI', category: 'Frontier APIs', defaultModel: 'codestral-latest' },
  { id: 'nvidia', name: 'NVIDIA NIM', category: 'Frontier APIs', defaultModel: 'meta/llama-3.2-11b-vision-instruct' },
  { id: 'cohere', name: 'Cohere Command', category: 'Frontier APIs', defaultModel: 'command-r-plus-08-2024' },

  // Subscription / CLI OAuth
  { id: 'codex', name: 'Codex CLI (ChatGPT Plus)', category: 'Subscription / CLI OAuth', defaultModel: 'gpt-5.6-luna' },
  { id: 'claude-code', name: 'Claude Code CLI (OAuth)', category: 'Subscription / CLI OAuth', defaultModel: 'claude-sonnet-4-5' },

  // Aggregators & Gateways
  { id: 'generic-openai', name: 'Generic OpenAI Gateway', category: 'Aggregators & Gateways', defaultModel: 'gpt-4o-mini' },
  { id: 'openrouter', name: 'OpenRouter', category: 'Aggregators & Gateways', defaultModel: 'minimax/minimax-m3:free' },
  { id: 'sarvam', name: 'Sarvam AI (Indic)', category: 'Aggregators & Gateways', defaultModel: 'sarvam-105b-conversations' },
  { id: 'opencode', name: 'OpenCode Zen', category: 'Aggregators & Gateways', defaultModel: 'nemotron-3.5-lightning-free' },
  { id: 'huggingface', name: 'Hugging Face Router', category: 'Aggregators & Gateways', defaultModel: 'meta-llama/Llama-3.3-70B-Instruct' },
  { id: 'foundry', name: 'Foundry (Local/VLLM)', category: 'Aggregators & Gateways', defaultModel: 'qwen3-14b' },
];

const POPULAR_SLOTS = ['omlx', 'groq', 'cerebras', 'generic-openai', 'openai', 'gemini', 'anthropic', 'codex', 'claude-code'];

@customElement('arc-model-arena')
export class ArcModelArena extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @property({ type: String }) prompt = 'Compare the efficiency and safety guarantees of synchronous vs asynchronous event handling.';
  @property({ type: Array }) selectedModels: string[] = ['omlx', 'generic-openai'];
  @property({ type: Number }) maxTokens = 512;

  @state() private _availableSlots = POPULAR_SLOTS;
  @state() private _loading = false;
  @state() private _error: string | null = null;
  @state() private _isUnavailable = false;
  @state() private _authStatus: 401 | 403 | null = null;
  @state() private _response: ArenaCompareResponse | null = null;
  @state() private _customSlot = '';
  @state() private _slotError: string | null = null;

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

    .active-models-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
    }

    .active-models-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }

    .active-model-tag {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 3px 8px;
      border-radius: 6px;
      border: 1px solid var(--accent, #6c63ff);
      background: color-mix(in srgb, var(--accent, #6c63ff) 18%, transparent);
      color: #ffffff;
      font-size: 12px;
      font-family: monospace;
      font-weight: 600;
    }

    .remove-tag-btn {
      background: none;
      border: none;
      color: var(--text-muted, #8b949e);
      cursor: pointer;
      padding: 0 2px;
      font-size: 14px;
      line-height: 1;
      border-radius: 3px;
    }

    .remove-tag-btn:hover:not(:disabled) {
      color: #ef4444;
    }

    .remove-tag-btn:disabled {
      opacity: 0.3;
      cursor: not-allowed;
    }

    .slot-controls {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      margin-top: 4px;
      padding-top: 8px;
      border-top: 1px solid var(--border, #242938);
    }

    .provider-dropdown {
      padding: 5px 8px;
      font-size: 12px;
      background: var(--bg-elevated, #1a202c);
      border: 1px solid var(--border, #242938);
      color: var(--text-primary, #ffffff);
      border-radius: 6px;
      cursor: pointer;
    }

    .provider-dropdown:focus {
      border-color: var(--accent, #6c63ff);
      outline: none;
    }

    .custom-slot-form {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .custom-slot-input {
      background: var(--bg-elevated, #1a202c);
      border: 1px solid var(--border, #242938);
      color: var(--text-primary, #ffffff);
      padding: 5px 8px;
      font-size: 12px;
      border-radius: 6px;
      font-family: monospace;
      min-width: 240px;
    }

    .custom-slot-input:focus {
      border-color: var(--accent, #6c63ff);
      outline: none;
    }

    .add-slot-btn {
      padding: 5px 10px;
      font-size: 11px;
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      border: 1px solid var(--accent, #6c63ff);
      color: #ffffff;
      border-radius: 6px;
      cursor: pointer;
      font-weight: 600;
    }

    .add-slot-btn:hover:not(:disabled) {
      background: color-mix(in srgb, var(--accent, #6c63ff) 35%, transparent);
    }

    .add-slot-btn:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .slot-error {
      font-size: 11px;
      color: #f59e0b;
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
    this._slotError = null;
    if (this.selectedModels.includes(slot)) {
      if (this.selectedModels.length > 1) {
        this.selectedModels = this.selectedModels.filter((m) => m !== slot);
      }
    } else {
      if (this.selectedModels.length < 3) {
        this.selectedModels = [...this.selectedModels, slot];
      } else {
        this._slotError = 'Maximum of 3 models can be benchmarked at once';
      }
    }
  }

  private _removeModel(slot: string) {
    this._slotError = null;
    if (this.selectedModels.length > 1) {
      this.selectedModels = this.selectedModels.filter((m) => m !== slot);
    }
  }

  private _onSelectProvider(e: Event) {
    const select = e.target as HTMLSelectElement;
    const val = select.value;
    if (!val) return;
    this._toggleModel(val);
    select.value = '';
  }

  private _addCustomSlot() {
    this._slotError = null;
    const raw = this._customSlot.trim();
    if (!raw) return;

    if (this.selectedModels.includes(raw)) {
      this._slotError = `Model slot '${raw}' is already selected`;
      return;
    }

    if (this.selectedModels.length >= 3) {
      this._slotError = 'Maximum of 3 models can be benchmarked at once';
      return;
    }

    const providerPart = raw.includes(':') ? raw.substring(0, raw.indexOf(':')) : raw;
    let cleanProv = providerPart.trim().toLowerCase();
    if (cleanProv === 'claudecode' || cleanProv === 'claude') {
      cleanProv = 'claude-code';
    }
    const isAllowed =
      ARENA_PROVIDERS.some((p) => p.id === cleanProv) ||
      POPULAR_SLOTS.includes(cleanProv) ||
      cleanProv === 'default' ||
      cleanProv === 'claudecode' ||
      cleanProv === 'claude' ||
      cleanProv === 'codex' ||
      cleanProv === 'claude-code';

    if (!isAllowed) {
      this._slotError = `Unrecognized provider prefix '${cleanProv}'. Use an allowlisted provider.`;
      return;
    }

    this.selectedModels = [...this.selectedModels, raw];
    this._customSlot = '';
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
        this._error = null;
      } else if (err instanceof ArcApiError && err.status === 403) {
        const msg = (err.message || '').toLowerCase();
        if (/\b(internal|role\s+required|operator\s+role)\b/i.test(msg)) {
          this._authStatus = 403;
          this._isUnavailable = true;
          this._error = null;
        } else {
          this._authStatus = null;
          this._isUnavailable = false;
          if (msg.includes('csrf')) {
            this._error = err.message || 'CSRF validation failed. Please refresh or re-authenticate.';
          } else {
            this._error =
              err.message && !msg.includes('forbidden')
                ? err.message
                : 'Access forbidden (403): CSRF or security check failed. Please refresh or re-authenticate.';
          }
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
          <div class="active-models-header">
            <span class="section-label">Active Comparison Models (${this.selectedModels.length}/3 max):</span>
            ${this._slotError ? html`<span class="slot-error">⚠️ ${this._slotError}</span>` : nothing}
          </div>
          <div class="active-models-bar">
            ${this.selectedModels.map(
              (m) => html`
                <span class="active-model-tag">
                  <span>${m}</span>
                  <button
                    class="remove-tag-btn"
                    @click="${() => this._removeModel(m)}"
                    title="Remove ${m}"
                    ?disabled="${this.selectedModels.length <= 1}"
                  >×</button>
                </span>
              `
            )}
          </div>

          <span class="section-label" style="margin-top: 4px;">Quick Select:</span>
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

          <div class="slot-controls">
            <label style="font-size: 11px; color: var(--text-muted, #8b949e); display: flex; align-items: center; gap: 6px;">
              <span>More Providers:</span>
              <select class="provider-dropdown" @change="${this._onSelectProvider}">
                <option value="">+ Add Provider from Catalog...</option>
                ${['Local / Self-Hosted', 'Ultra-Fast Cloud', 'Frontier APIs', 'Subscription / CLI OAuth', 'Aggregators & Gateways'].map(
                  (cat) => html`
                    <optgroup label="${cat}">
                      ${ARENA_PROVIDERS.filter((p) => p.category === cat).map(
                        (p) => html`
                          <option
                            value="${p.id}"
                            ?disabled="${this.selectedModels.includes(p.id) || this.selectedModels.length >= 3}"
                          >
                            ${p.name} (${p.id})
                          </option>
                        `
                      )}
                    </optgroup>
                  `
                )}
              </select>
            </label>

            <form
              class="custom-slot-form"
              @submit="${(e: Event) => {
                e.preventDefault();
                this._addCustomSlot();
              }}"
            >
              <input
                class="custom-slot-input"
                type="text"
                placeholder="Custom slot (e.g. groq:llama-3.3-70b-versatile)..."
                .value="${this._customSlot}"
                @input="${(e: Event) => (this._customSlot = (e.target as HTMLInputElement).value)}"
              />
              <button
                type="submit"
                class="add-slot-btn"
                ?disabled="${!this._customSlot.trim() || this.selectedModels.length >= 3}"
              >
                + Add Slot
              </button>
            </form>
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
                const outputStr = res.output ?? '';
                const isEvaluating = outputStr === 'Evaluating...';
                const isTimeout = outputStr.includes('TIMEOUT');
                const isError = !isTimeout && (outputStr.includes('ERROR') || outputStr.startsWith('PROVIDER_'));

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
