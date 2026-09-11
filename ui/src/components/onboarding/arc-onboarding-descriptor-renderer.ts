import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { OnboardingStepDescriptorDto, OnboardingFieldDescriptorDto } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

/**
 * Accessible, schema-driven Lit renderer for typed onboarding step descriptors.
 * Invariant (§4, §6):
 * The onboarding module provides one Java validator and one small Lit renderer for that contract;
 * adapters do not supply markup, client validation code, or arbitrary regular expressions.
 */
@customElement('arc-onboarding-descriptor-renderer')
export class ArcOnboardingDescriptorRenderer extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }

      .field-group {
        margin-bottom: var(--space-4);
      }

      .field-desc {
        display: block;
        font-size: var(--font-size-xs);
        color: var(--text-muted);
        margin-top: var(--space-1);
        margin-bottom: var(--space-2);
      }

      .field-error {
        display: block;
        font-size: var(--font-size-xs);
        color: var(--error);
        margin-top: var(--space-1);
      }

      .required {
        color: var(--error);
        margin-left: 2px;
      }

      .checkbox-group {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }

      .button-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-top: var(--space-6);
        gap: var(--space-3);
      }
    `
  ];

  @property({ type: Object }) descriptor?: OnboardingStepDescriptorDto;
  @property({ type: Object }) values: Record<string, any> = {};
  @property({ type: Object }) errors: Record<string, string> = {};

  private _handleInput(fieldName: string, value: any) {
    const updated = { ...this.values, [fieldName]: value };
    this.dispatchEvent(
      new CustomEvent('update-values', {
        detail: { values: updated, fieldName, value },
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
    if (!this.descriptor) {
      return html`<p class="status-msg">No descriptor provided.</p>`;
    }

    const { id, title, description, fields = [] } = this.descriptor;

    return html`
      <div role="form" aria-label="${title || id}">
        <h2 class="step-title" id="step-heading-${id}">${title}</h2>
        ${description ? html`<p class="step-desc" id="step-desc-${id}">${description}</p>` : ''}

        <div class="fields-container">
          ${fields.map((f) => this._renderField(f))}
        </div>

        <div class="button-row">
          <button
            type="button"
            class="btn-ghost"
            @click="${this._back}"
            aria-label="Go back to previous step"
          >
            Back
          </button>
          <button
            type="button"
            class="btn-primary"
            @click="${this._next}"
            aria-label="Continue to next step"
          >
            Continue
          </button>
        </div>
      </div>
    `;
  }

  private _renderField(f: OnboardingFieldDescriptorDto) {
    const val = this.values[f.name] ?? '';
    const err = this.errors[f.name];
    const inputId = `field-${f.name}`;
    const descId = `desc-${f.name}`;
    const errId = `err-${f.name}`;

    const describedBy = [
      f.description ? descId : null,
      err ? errId : null
    ].filter(Boolean).join(' ');

    return html`
      <div class="field-group">
        <label for="${inputId}" class="field-label">
          ${f.title || f.name}
          ${f.required ? html`<span class="required" aria-hidden="true">*</span>` : ''}
        </label>

        ${f.description ? html`<span id="${descId}" class="field-desc">${f.description}</span>` : ''}

        ${this._renderInputField(f, inputId, val, describedBy, Boolean(err))}

        ${err ? html`<span id="${errId}" class="field-error" role="alert">${err}</span>` : ''}
      </div>
    `;
  }

  private _renderInputField(
    f: OnboardingFieldDescriptorDto,
    inputId: string,
    val: any,
    describedBy: string,
    hasError: boolean
  ) {
    switch (f.type) {
      case 'SECRET':
        return html`
          <input
            id="${inputId}"
            type="password"
            class="field-input"
            autocomplete="new-password"
            .value="${val}"
            @input="${(e: any) => this._handleInput(f.name, e.target.value)}"
            aria-describedby="${describedBy || undefined}"
            ?aria-required="${f.required}"
            aria-invalid="${hasError}"
          />
        `;
      case 'URL':
        return html`
          <input
            id="${inputId}"
            type="url"
            class="field-input"
            placeholder="https://..."
            .value="${val}"
            @input="${(e: any) => this._handleInput(f.name, e.target.value)}"
            aria-describedby="${describedBy || undefined}"
            ?aria-required="${f.required}"
            aria-invalid="${hasError}"
          />
        `;
      case 'SELECT':
        return html`
          <select
            id="${inputId}"
            class="field-input"
            .value="${val}"
            @change="${(e: any) => this._handleInput(f.name, e.target.value)}"
            aria-describedby="${describedBy || undefined}"
            ?aria-required="${f.required}"
            aria-invalid="${hasError}"
          >
            <option value="">Select an option</option>
            ${(f.options || []).map(
              (opt) => html`<option value="${opt}" ?selected="${val === opt}">${opt}</option>`
            )}
          </select>
        `;
      case 'CHECKBOX':
        return html`
          <div class="checkbox-group">
            <input
              id="${inputId}"
              type="checkbox"
              ?checked="${Boolean(val)}"
              @change="${(e: any) => this._handleInput(f.name, e.target.checked)}"
              aria-describedby="${describedBy || undefined}"
              ?aria-required="${f.required}"
              aria-invalid="${hasError}"
            />
            <label for="${inputId}">${f.title || f.name}</label>
          </div>
        `;
      case 'MODEL_SELECTOR':
      case 'TEXT':
      default:
        return html`
          <input
            id="${inputId}"
            type="text"
            class="field-input"
            .value="${val}"
            @input="${(e: any) => this._handleInput(f.name, e.target.value)}"
            aria-describedby="${describedBy || undefined}"
            ?aria-required="${f.required}"
            aria-invalid="${hasError}"
          />
        `;
    }
  }
}
