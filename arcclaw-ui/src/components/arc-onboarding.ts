import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';
import { McpServerRequest } from '../api/generated/models/McpServerRequest';
import './arc-onboarding-step1';
import './arc-onboarding-step2';
import './arc-onboarding-step3';
import './arc-onboarding-step4';
import './arc-onboarding-step5';
import './arc-onboarding-step6';

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {
  private _client: ArcClient = new ArcClient('/api/v1');

  @state() private _currentStep = 1;
  @state() private _loading = false;
  @state() private _error = '';

  @state() private _provider = 'groq';
  @state() private _model = '';
  @state() private _apiKey = '';
  @state() private _systemPrompt = 'You are ArcClaw, a helpful AI assistant.';
  @state() private _mcpServers: McpServerRequest[] = [];
  @state() private _validationOk = false;

  private readonly DEFAULT_MODELS: Record<string, string> = {
    groq: 'meta-llama/llama-4-scout-17b-16e-instruct',
    openai: 'gpt-4o-mini',
    ollama: 'llama3.2',
    anthropic: 'claude-haiku-4-5',
    'claude-code': 'claude-sonnet-4-5',
    openrouter: 'openai/gpt-oss-20b:free',
    nvidia: 'mistralai/devstral-2-123b-instruct-2512',
    cerebras: 'qwen-3-235b-a22b-instruct-2507',
    gemini: 'gemini-2.5-flash-lite',
  };

  static styles = css`
  :host {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    background: var(--bg-base);
    font-family: var(--font-family);
    padding: var(--space-6) var(--space-4);
  }

  .wizard-card {
    width: 100%;
    max-width: 480px;
    background: var(--bg-surface);
    border: 1px solid var(--border-card);
    border-radius: var(--radius-xl);
    box-shadow: var(--shadow-lg), var(--shadow-glow);
    overflow: hidden;
    position: relative;
    animation: card-in 0.4s cubic-bezier(0.16,1,0.3,1) both;
    transition: max-width var(--transition);
  }

  .wizard-card.wide {
    max-width: 600px;
  }

  [data-theme="dark"] .wizard-card,
  :host-context([data-theme="dark"]) .wizard-card {
    background: linear-gradient(145deg, #13151f 0%, #111318 100%);
    border-color: rgba(255,255,255,0.06);
  }

  @keyframes card-in {
    from { opacity: 0; transform: translateY(20px) scale(0.98); }
    to   { opacity: 1; transform: translateY(0)   scale(1); }
  }

  .wizard-card::before {
    content: "";
    position: absolute; top: 0; left: 0; right: 0; height: 2px;
    background: linear-gradient(90deg,
      var(--accent) 0%, var(--accent-secondary) 100%);
  }

  .wizard-header {
    padding: var(--space-8) var(--space-8) var(--space-6);
    text-align: center;
  }

  .brand-mark {
    width: 44px; height: 44px;
    margin: 0 auto var(--space-4);
    background: linear-gradient(135deg,
      var(--accent) 0%, var(--accent-secondary) 100%);
    border-radius: var(--radius-md);
    display: flex; align-items: center; justify-content: center;
    box-shadow: 0 8px 24px var(--accent-glow);
  }

  .brand-mark svg { color: #fff; }

  .wizard-title {
    font-size: var(--font-size-2xl);
    font-weight: var(--font-weight-bold);
    color: var(--text-primary);
    letter-spacing: -0.02em;
    line-height: 1.2;
    margin-bottom: var(--space-2);
  }

  .wizard-subtitle {
    font-size: var(--font-size-sm);
    color: var(--text-muted);
    line-height: 1.5;
  }

  .stepper {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0;
    padding: 0 var(--space-8) var(--space-6);
  }

  .step-dot-wrap {
    display: flex; align-items: center; flex: 1;
  }
  .step-dot-wrap:last-child { flex: none; }

  .step-dot {
    width: 28px; height: 28px; flex-shrink: 0;
    border-radius: var(--radius-full);
    border: 2px solid var(--border);
    background: var(--bg-elevated);
    display: flex; align-items: center; justify-content: center;
    font-size: var(--font-size-xs);
    font-weight: var(--font-weight-semi);
    color: var(--text-muted);
    transition: all var(--transition);
    position: relative; z-index: 1;
  }

  .step-dot.active {
    border-color: var(--accent);
    background: var(--accent);
    color: #fff;
    box-shadow: 0 0 0 4px var(--accent-glow);
  }

  .step-dot.done {
    border-color: var(--accent-secondary);
    background: var(--accent-secondary);
    color: #fff;
  }

  .step-connector {
    flex: 1; height: 2px;
    background: var(--border);
    margin: 0 -1px;
    transition: background var(--transition-slow);
  }

  .step-connector.done {
    background: linear-gradient(90deg,
      var(--accent-secondary) 0%, var(--accent) 100%);
  }

  .wizard-body {
    padding: 0 var(--space-8) var(--space-8);
  }
  `;

  async connectedCallback() {
    super.connectedCallback();
    await this._fetchStatus();
  }

  private async _fetchStatus() {
    this._loading = true;
    try {
      const status = await this._client.onboardingStatus();
      this._currentStep = status.step;
      if (status.model) this._model = status.model;
    } catch (e: any) {
      this._error = `Setup Error: ${e.message}`;
    } finally {
      this._loading = false;
    }
  }

  private async _next(body?: object) {
    this._loading = true;
    this._error = '';
    try {
      const res = await this._client.onboardingStep(this._currentStep, body);
      if (this._currentStep === 3) {
        if (res.valid === false) {
          this._error = res.error || 'Invalid API Key';
          return;
        } else {
          this._validationOk = true;
          this._error = 'Key is valid!';
          await new Promise(r => setTimeout(r, 600));
        }
      }
      this._currentStep = res.nextStep;
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _finish() {
    try {
      const result = await this._client.onboardingComplete();
      this.dispatchEvent(new CustomEvent('api-key-set', {
        bubbles: true, composed: true,
        detail: { key: result.serviceKey }
      }));
    } catch (e: any) {
      this._error = `Finalizing Error: ${e.message}`;
    }
  }

  private _back() {
    if (this._currentStep > 1) {
      this._currentStep--;
      this._error = '';
      this._loading = false;
      this._validationOk = false;
    }
  }

  private _onStepEvent(e: CustomEvent) {
    switch (this._currentStep) {
      case 2: {
        const { provider, model } = (e as CustomEvent<{ provider: string; model: string }>).detail || {};
        if (e.type === 'provider-change') {
          this._provider = provider;
          this._model = model;
          this._validationOk = false;
          this._error = '';
        } else if (e.type === 'model-change') {
          this._model = model;
        } else if (e.type === 'next') {
          this._next({ provider: this._provider, model: this._model });
        }
        break;
      }
      case 3: {
        if (e.type === 'key-change') {
          this._apiKey = (e as CustomEvent<{ apiKey: string }>).detail.apiKey;
        } else if (e.type === 'validate') {
          this._next({ apiKey: this._apiKey });
        } else if (e.type === 'back') {
          this._back();
        }
        break;
      }
      case 4: {
        if (e.type === 'prompt-change') {
          this._systemPrompt = (e as CustomEvent<{ systemPrompt: string }>).detail.systemPrompt;
        } else if (e.type === 'next') {
          this._next({ systemPrompt: this._systemPrompt });
        } else if (e.type === 'back') {
          this._back();
        }
        break;
      }
      case 5: {
        if (e.type === 'mcp-add' || e.type === 'mcp-remove') {
          this._mcpServers = (e as CustomEvent<{ mcpServers: McpServerRequest[] }>).detail.mcpServers;
        } else if (e.type === 'next') {
          this._next({ mcpServers: this._mcpServers });
        } else if (e.type === 'skip') {
          this._next({ mcpServers: [] });
        } else if (e.type === 'back') {
          this._back();
        }
        break;
      }
    }
  }

  private _onFinish() {
    this._finish();
  }

  render() {
    return html`
      <div class="wizard-card ${this._currentStep === 5 ? 'wide' : ''}">
        ${this._currentStep !== 6 ? html`
          <div class="wizard-header">
            <div class="brand-mark">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
              </svg>
            </div>
            <h1 class="wizard-title">ArcClaw</h1>
            <p class="wizard-subtitle">AI Agent Infrastructure</p>
          </div>
          <div class="stepper">
            ${[1, 2, 3, 4, 5].map(s => html`
              <div class="step-dot-wrap">
                <div class="step-dot ${this._currentStep === s ? 'active' : this._currentStep > s ? 'done' : ''}">
                  ${this._currentStep > s ? '✓' : s}
                </div>
                ${s < 5 ? html`<div class="step-connector ${this._currentStep > s ? 'done' : ''}"></div>` : ''}
              </div>
            `)}
          </div>
        ` : ''}

        <div class="wizard-body" @next="${this._onStepEvent}" @back="${this._onStepEvent}"
             @provider-change="${this._onStepEvent}" @model-change="${this._onStepEvent}"
             @key-change="${this._onStepEvent}" @validate="${this._onStepEvent}"
             @prompt-change="${this._onStepEvent}"
             @mcp-add="${this._onStepEvent}" @mcp-remove="${this._onStepEvent}"
             @skip="${this._onStepEvent}" @finish="${this._onFinish}">
          ${this._currentStep === 1 ? html`
            <arc-onboarding-step1></arc-onboarding-step1>
          ` : this._currentStep === 2 ? html`
            <arc-onboarding-step2 .provider="${this._provider}" .model="${this._model}"
              .defaultModels="${this.DEFAULT_MODELS}"></arc-onboarding-step2>
          ` : this._currentStep === 3 ? html`
            <arc-onboarding-step3 .provider="${this._provider}" .apiKey="${this._apiKey}"
              .loading="${this._loading}" .error="${this._error}"
              .validationOk="${this._validationOk}"></arc-onboarding-step3>
          ` : this._currentStep === 4 ? html`
            <arc-onboarding-step4 .systemPrompt="${this._systemPrompt}"></arc-onboarding-step4>
          ` : this._currentStep === 5 ? html`
            <arc-onboarding-step5 .mcpServers="${this._mcpServers}"
              .loading="${this._loading}"></arc-onboarding-step5>
          ` : html`
            <arc-onboarding-step6></arc-onboarding-step6>
          `}
        </div>
      </div>
    `;
  }
}
