import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { AdvancedSettings } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-advanced-settings')
export class ArcOnboardingStepAdvancedSettings extends LitElement {
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

  @property({ type: Object }) advanced: AdvancedSettings = {
    temperature: 0.7,
    maxOutputTokens: 4096,
    timeoutMs: 30000,
    maxRetries: 3,
    compactPrompt: true,
    circuitBreaker: true,
    delegationDepth: 5,
    budgetLimit: 10,
    compactionStructuredOutputMode: 'NATIVE'
  };

  private _onChange(key: keyof AdvancedSettings, value: any) {
    const updated = { ...this.advanced, [key]: value };
    this.dispatchEvent(
      new CustomEvent('update-advanced', {
        detail: { advanced: updated },
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
      <span class="field-label">Advanced Settings (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Fine-tune execution parameters, model thresholds, and budget safety constraints.
      </p>

      <div class="grid-2">
        <div>
          <span class="field-label">LLM Temperature</span>
          <input class="field-input" type="number" step="0.1" .value="${this.advanced.temperature ?? 0.7}"
                 @input="${(e: any) => this._onChange('temperature', Number(e.target.value))}" />
        </div>
        <div>
          <span class="field-label">Max Output Tokens</span>
          <input class="field-input" type="number" .value="${this.advanced.maxOutputTokens ?? 4096}"
                 @input="${(e: any) => this._onChange('maxOutputTokens', Number(e.target.value))}" />
        </div>
      </div>

      <div class="grid-2">
        <div>
          <span class="field-label">Request Timeout (ms)</span>
          <input class="field-input" type="number" .value="${this.advanced.timeoutMs ?? 30000}"
                 @input="${(e: any) => this._onChange('timeoutMs', Number(e.target.value))}" />
        </div>
        <div>
          <span class="field-label">Max Retries</span>
          <input class="field-input" type="number" .value="${this.advanced.maxRetries ?? 3}"
                 @input="${(e: any) => this._onChange('maxRetries', Number(e.target.value))}" />
        </div>
      </div>

      <div class="grid-2">
        <div>
          <span class="field-label">Delegation Depth Limit</span>
          <input class="field-input" type="number" .value="${this.advanced.delegationDepth ?? 5}"
                 @input="${(e: any) => this._onChange('delegationDepth', Number(e.target.value))}" />
        </div>
        <div>
          <span class="field-label">Budget Limit ($)</span>
          <input class="field-input" type="number" .value="${this.advanced.budgetLimit ?? 10}"
                 @input="${(e: any) => this._onChange('budgetLimit', Number(e.target.value))}" />
        </div>
      </div>

      <div style="margin-bottom:var(--space-4)">
        <span class="field-label">Compaction Structured Output Mode</span>
        <select class="field-select" .value="${this.advanced.compactionStructuredOutputMode || 'NATIVE'}"
                @change="${(e: any) => this._onChange('compactionStructuredOutputMode', e.target.value)}">
          <option value="NATIVE">Native JSON Schema</option>
          <option value="PROMPT_DIRECTIVE">Prompt Directives only</option>
          <option value="LENIENT">Lenient Parser</option>
        </select>
      </div>

      <div style="display:flex;gap:var(--space-4);margin-bottom:var(--space-5)">
        <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-primary)">
          <input type="checkbox" ?checked="${this.advanced.compactPrompt}"
                 @change="${(e: any) => this._onChange('compactPrompt', e.target.checked)}" />
          Enable Compact Prompt
        </label>
        <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-primary)">
          <input type="checkbox" ?checked="${this.advanced.circuitBreaker}"
                 @change="${(e: any) => this._onChange('circuitBreaker', e.target.checked)}" />
          Enable Circuit Breaker
        </label>
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
