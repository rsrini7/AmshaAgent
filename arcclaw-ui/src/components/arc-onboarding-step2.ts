import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { formStyles } from './arc-onboarding-styles';

@customElement('arc-onboarding-step2')
export class ArcOnboardingStep2 extends LitElement {
  static styles = [formStyles, css`
    :host { display: block; }
  `];

  @property({ type: String }) provider = 'groq';
  @property({ type: String }) model = '';
  @property({ type: Object }) defaultModels: Record<string, string> = {};

  private providers = [
    { id: 'groq',        name: 'Groq',        tag: 'Fast inference',  icon: 'G',  color: '#f55036' },
    { id: 'openai',      name: 'OpenAI',       tag: 'GPT-4o series',   icon: 'O',  color: '#10a37f' },
    { id: 'ollama',      name: 'Ollama',       tag: 'Local / private', icon: '🦙', color: '#666' },
    { id: 'anthropic',   name: 'Anthropic',    tag: 'Claude series',   icon: 'A',  color: '#cc785c' },
    { id: 'claude-code', name: 'Claude Code',  tag: 'CLI tool',        icon: 'C',  color: '#8b5cf6' },
    { id: 'openrouter',  name: 'OpenRouter',   tag: 'Unified API',     icon: 'R',  color: '#6561f5' },
    { id: 'nvidia',      name: 'NVIDIA',       tag: 'NIM models',      icon: 'N',  color: '#76b900' },
    { id: 'cerebras',    name: 'Cerebras',     tag: 'Ultra-fast LPU',  icon: 'Cb', color: '#11e400' },
    { id: 'gemini',      name: 'Gemini',       tag: 'Google AI',       icon: 'Ge', color: '#4285f4' },
    { id: 'omlx',        name: 'OMLX',         tag: 'Local MLX server', icon: 'M',  color: '#5dade2' },
    { id: 'llamacpp',    name: 'llama.cpp',    tag: 'Local GGUF',      icon: 'L',  color: '#e74c3c' },
    { id: 'generic-openai', name: 'Generic OpenAI', tag: 'Any endpoint', icon: '⚡', color: '#888888' },
  ];

  private _onProviderChange(val: string) {
    const model = this.defaultModels[val] || '';
    this.dispatchEvent(new CustomEvent('provider-change', {
      detail: { provider: val, model }, bubbles: true, composed: true
    }));
  }

  private _onModelChange(e: Event) {
    const model = (e.target as HTMLInputElement).value;
    this.dispatchEvent(new CustomEvent('model-change', {
      detail: { model }, bubbles: true, composed: true
    }));
  }

  private _next() {
    this.dispatchEvent(new CustomEvent('next', {
      detail: { provider: this.provider, model: this.model },
      bubbles: true, composed: true
    }));
  }

  render() {
    const placeholder = this.provider === 'groq' ? 'meta-llama/llama-4-scout-17b-16e-instruct'
      : this.provider === 'openai' ? 'gpt-4o-mini'
      : this.provider === 'ollama' ? 'mistral-small:latest'
      : this.provider === 'anthropic' ? 'claude-haiku-4-5'
      : this.provider === 'openrouter' ? 'openai/gpt-oss-20b:free'
      : 'claude-sonnet-4-5';

    return html`
      <span class="field-label">Provider</span>
      <div class="provider-grid" role="radiogroup" aria-label="Select a provider">
        ${this.providers.map(p => html`
          <div class="provider-card ${this.provider === p.id ? 'selected' : ''}"
               @click="${() => this._onProviderChange(p.id)}"
               role="radio" aria-checked="${this.provider === p.id}"
               tabindex="0"
               @keydown="${(e: KeyboardEvent) =>
                 (e.key === 'Enter' || e.key === ' ') && this._onProviderChange(p.id)}">
            <div class="provider-icon" style="background:${p.color}22;color:${p.color}">
              ${p.icon}
            </div>
            <div>
              <div class="provider-name">${p.name}</div>
              <div class="provider-tag">${p.tag}</div>
            </div>
          </div>
        `)}
      </div>
      <div class="model-select-wrap">
        <span class="field-label">Model</span>
        <input class="field-input" type="text" placeholder="${placeholder}"
               .value="${this.model}" @input="${this._onModelChange}" />
      </div>
      <div class="btn-row">
        <button class="btn-primary" ?disabled="${!this.provider}"
          @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
