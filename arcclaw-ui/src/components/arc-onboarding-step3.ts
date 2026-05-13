import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { formStyles } from './arc-onboarding-styles';

@customElement('arc-onboarding-step3')
export class ArcOnboardingStep3 extends LitElement {
  static styles = [formStyles, css`
    :host { display: block; }
  `];

  @property({ type: String }) provider = '';
  @property({ type: String }) apiKey = '';
  @property({ type: Boolean }) loading = false;
  @property({ type: String }) error = '';
  @property({ type: Boolean }) validationOk = false;

  private _onKeyInput(e: Event) {
    const value = (e.target as HTMLInputElement).value;
    this.dispatchEvent(new CustomEvent('key-change', {
      detail: { apiKey: value }, bubbles: true, composed: true
    }));
  }

  private _validate() {
    this.dispatchEvent(new CustomEvent('validate', {
      detail: { apiKey: this.apiKey }, bubbles: true, composed: true
    }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  private _toggleVisible(e: Event) {
    const inp = (e.currentTarget as HTMLElement).previousElementSibling as HTMLInputElement;
    if (inp) inp.type = inp.type === 'password' ? 'text' : 'password';
  }

  render() {
    const isOllama = this.provider === 'ollama';
    const isClaudeCode = this.provider === 'claude-code';
    const canValidate = this.loading || (!this.apiKey?.trim() && !isClaudeCode);

    return html`
      <div style="margin-bottom:var(--space-5)">
        <p style="font-size:var(--font-size-sm);color:var(--text-muted);line-height:1.6;margin-bottom:var(--space-5)">
          Provide your key for
          <strong style="color:var(--text-primary);font-weight:var(--font-weight-semi)">${this.provider}</strong>.
          We'll validate it live now.
        </p>
        <label class="field-label" for="api-key-input">${this.provider} API Key</label>
        <div class="api-key-field">
          <input id="api-key-input" class="field-input has-trailing-action"
            type="password" autocomplete="off" spellcheck="false"
            placeholder="sk-••••••••••••••••••••••••••••••••"
            .value="${this.apiKey ?? ''}" @input="${this._onKeyInput}"
            @keydown="${(e: KeyboardEvent) => e.key === 'Enter' && this._validate()}"
            style="font-family:'Courier New',monospace;letter-spacing:0.04em;padding-right:5.5rem!important;box-sizing:border-box!important" />
          <button type="button" class="field-action-btn" @click="${this._toggleVisible}" aria-label="Toggle key visibility">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
              <circle cx="12" cy="12" r="3"/>
            </svg>
          </button>
        </div>
        ${isOllama ? html`
          <p style="margin-top:var(--space-2);font-size:var(--font-size-xs);color:var(--text-muted)">
            💡 Ollama runs locally - enter any value (e.g. <code>local</code>)
          </p>
        ` : ''}
        ${isClaudeCode ? html`
          <p style="margin-top:var(--space-2);font-size:var(--font-size-xs);color:var(--text-muted)">
            💡 Claude Code uses the CLI - no API key required
          </p>
        ` : ''}
      </div>
      ${this.error || this.validationOk ? html`
        <div class="status-msg ${this.validationOk ? 'success' : 'error'}">
          ${this.validationOk
            ? html`<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>`
            : html`<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`}
          ${this.error}
        </div>
      ` : ''}
      <div class="btn-row" style="margin-top:var(--space-5)">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" ?disabled="${canValidate}" @click="${this._validate}">
          ${this.loading ? html`
            <span style="display:flex;align-items:center;gap:var(--space-2);justify-content:center">
              <span class="spinner"></span> Validating…
            </span>
          ` : 'Validate Key →'}
        </button>
      </div>
    `;
  }
}
