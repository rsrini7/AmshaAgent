import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { ConnectionDraft, ProviderDescriptor } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-persona-routing')
export class ArcOnboardingStepPersonaRouting extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }
    `
  ];

  @property({ type: Array }) connections: ConnectionDraft[] = [];
  @property({ type: Array }) catalog: ProviderDescriptor[] = [];
  @property({ type: Object }) personaRoutes: Record<string, { primary: number; failover: number[] }> = {};

  private _onChange(roleId: string, primaryIdx: number) {
    const updated = { ...this.personaRoutes };
    updated[roleId] = { primary: primaryIdx, failover: [] };
    this.dispatchEvent(
      new CustomEvent('update-persona-routes', {
        detail: { personaRoutes: updated },
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
    const roles = [
      { id: 'research', name: 'Codebase Researcher' },
      { id: 'self', name: 'Self Developer Agent' }
    ];

    return html`
      <span class="field-label">Persona Routing (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Assign specific connections/models to specialized agent persona roles.
      </p>

      <div style="display:flex;flex-direction:column;gap:var(--space-4);margin-bottom:var(--space-5)">
        ${roles.map(r => {
          const route = this.personaRoutes[r.id] || { primary: 0, failover: [] };
          return html`
            <div style="background:var(--bg-elevated);border:1px solid var(--border-card);border-radius:var(--radius-md);padding:var(--space-3)">
              <div style="font-size:var(--font-size-sm);font-weight:var(--font-weight-semi);color:var(--text-primary);margin-bottom:var(--space-2)">
                ${r.name}
              </div>
              <select class="field-select" .value="${route.primary}"
                      @change="${(e: any) => this._onChange(r.id, Number(e.target.value))}">
                ${this.connections.map((c, idx) => {
                  const prov: Partial<ProviderDescriptor> = this.catalog.find(p => p.id === c.provider) || {};
                  return html`
                    <option value="${idx}">${prov.displayName} — ${c.modelId}</option>
                  `;
                })}
              </select>
            </div>
          `;
        })}
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
