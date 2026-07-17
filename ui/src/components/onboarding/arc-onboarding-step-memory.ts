import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { MemoryConfig } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-memory')
export class ArcOnboardingStepMemory extends LitElement {
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

  @property({ type: Object }) memory: MemoryConfig = {
    tieredEnabled: true,
    coreMaxBlocks: 10,
    recallRetention: 100,
    archivalEnabled: true,
    selfEvolveEnabled: false,
    embedDimensions: 1536,
    ragVecPath: 'data/amsha-fts.db'
  };

  private _onChange(key: keyof MemoryConfig, value: any) {
    const updated = { ...this.memory, [key]: value };
    this.dispatchEvent(
      new CustomEvent('update-memory', {
        detail: { memory: updated },
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
      <span class="field-label">Memory & Self-Evolve (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Configure tiered cognitive memory blocks, RAG search indices, and self-improvement loops.
      </p>

      <div style="display:flex;flex-direction:column;gap:var(--space-4);margin-bottom:var(--space-5)">
        <div style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-3)">
          <label style="display:flex;align-items:center;gap:var(--space-2);font-size:var(--font-size-sm);color:var(--text-primary)">
            <input type="checkbox" ?checked="${this.memory.tieredEnabled}"
                   @change="${(e: any) => this._onChange('tieredEnabled', e.target.checked)}" />
            Enable Tiered Memory Architecture
          </label>
        </div>

        <div class="grid-2">
          <div>
            <span class="field-label">Core Memory Max Blocks</span>
            <input class="field-input" type="number" .value="${this.memory.coreMaxBlocks ?? 10}"
                   @input="${(e: any) => this._onChange('coreMaxBlocks', Number(e.target.value))}" />
          </div>
          <div>
            <span class="field-label">Recall Retention (events)</span>
            <input class="field-input" type="number" .value="${this.memory.recallRetention ?? 100}"
                   @input="${(e: any) => this._onChange('recallRetention', Number(e.target.value))}" />
          </div>
        </div>

        <div class="grid-2">
          <div>
            <span class="field-label">Embedding Dimensions</span>
            <input class="field-input" type="number" .value="${this.memory.embedDimensions ?? 1536}"
                   @input="${(e: any) => this._onChange('embedDimensions', Number(e.target.value))}" />
          </div>
          <div>
            <span class="field-label">RAG Database Path</span>
            <input class="field-input" type="text" .value="${this.memory.ragVecPath ?? 'data/amsha-fts.db'}"
                   @input="${(e: any) => this._onChange('ragVecPath', e.target.value)}" />
          </div>
        </div>

        <div style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-3)">
          <label style="display:flex;align-items:center;gap:var(--space-2);font-size:var(--font-size-sm);color:var(--text-primary)">
            <input type="checkbox" ?checked="${this.memory.selfEvolveEnabled}"
                   @change="${(e: any) => this._onChange('selfEvolveEnabled', e.target.checked)}" />
            Enable Self-Evolving Prompts & Reflection
          </label>
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
