import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { formStyles } from './arc-onboarding-styles';

@customElement('arc-onboarding-step4')
export class ArcOnboardingStep4 extends LitElement {
  static styles = [formStyles, css`
    :host { display: block; }
  `];

  @property({ type: String }) systemPrompt = '';

  private _onInput(e: Event) {
    const value = (e.target as HTMLTextAreaElement).value;
    this.dispatchEvent(new CustomEvent('prompt-change', {
      detail: { systemPrompt: value }, bubbles: true, composed: true
    }));
  }

  private _next() {
    this.dispatchEvent(new CustomEvent('next', {
      detail: { systemPrompt: this.systemPrompt }, bubbles: true, composed: true
    }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <p style="font-size:var(--font-size-sm);color:var(--text-muted);line-height:1.6;margin-bottom:var(--space-5)">
        The core instruction that defines how your Amsha Agent agents behave. You can refine this later from settings.
      </p>
      <label class="field-label" for="system-prompt">System Instructions</label>
      <textarea id="system-prompt" class="field-textarea" rows="5"
        placeholder="You are Amsha Agent, a helpful AI assistant. You help users by..."
        .value="${this.systemPrompt}" @input="${this._onInput}"></textarea>
      <p style="margin-top:var(--space-2);font-size:var(--font-size-xs);color:var(--text-muted);line-height:1.5">
        💡 Be specific about tone, constraints, and what the agent should or shouldn't do.
      </p>
      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" ?disabled="${!this.systemPrompt?.trim()}" @click="${this._next}">
          Save & Continue →
        </button>
      </div>
    `;
  }
}
