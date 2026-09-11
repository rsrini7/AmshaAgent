import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { ConcernRoutes, ConnectionDraft, McpServerDraft, ProviderDescriptor } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-review')
export class ArcOnboardingStepReview extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }

      .review-title {
        font-size: var(--font-size-md);
        font-weight: var(--font-weight-bold);
        color: var(--text-primary);
        margin-bottom: var(--space-4);
      }

      .summary-box {
        padding: var(--space-4);
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
        margin-bottom: var(--space-6);
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }

      .summary-item {
        display: flex;
        justify-content: space-between;
        font-size: var(--font-size-sm);
        border-bottom: 1px solid var(--border);
        padding-bottom: var(--space-2);
      }

      .summary-item:last-child {
        border-bottom: none;
        padding-bottom: 0;
      }

      .label {
        color: var(--text-muted);
        font-weight: var(--font-weight-medium);
      }

      .value {
        color: var(--text-primary);
        font-weight: var(--font-weight-bold);
      }
    `
  ];

  @property({ type: Array }) connections: ConnectionDraft[] = [];
  @property({ type: Array }) catalog: ProviderDescriptor[] = [];
  @property({ type: Array }) mcpServers: McpServerDraft[] = [];
  @property({ type: Object }) concernRoutes: ConcernRoutes = {
    agentLoop: [],
    compaction: [],
    classifier: [],
    embedding: []
  };

  private _onCommit() {
    this.dispatchEvent(new CustomEvent('finish', { bubbles: true, composed: true }));
  }

  private _getConnName(idx: number): string {
    const c = this.connections[idx];
    if (!c) return 'Default';
    const prov: Partial<ProviderDescriptor> = this.catalog.find(p => p.id === c.provider) || {};
    return `${prov.displayName || c.provider} (${c.modelId})`;
  }

  render() {
    return html`
      <div class="review-title">Review & Finalize Onboarding</div>

      <div style="font-size:12px;color:var(--text-muted);line-height:1.6;margin-bottom:var(--space-4)">
        Please review your AI agent infrastructure setup. Once committed, your credentials will be durably sealed in the secure vault and your router will activate.
      </div>

      <div class="summary-box">
        <div class="summary-item">
          <span class="label">Total API Connections</span>
          <span class="value">${this.connections.length}</span>
        </div>
        <div class="summary-item">
          <span class="label">Agent Loop Route</span>
          <span class="value">${this._getConnName(this.concernRoutes.agentLoop[0])}</span>
        </div>
        <div class="summary-item">
          <span class="label">Compaction Route</span>
          <span class="value">${this._getConnName(this.concernRoutes.compaction[0])}</span>
        </div>
        <div class="summary-item">
          <span class="label">Classifier Route</span>
          <span class="value">${this._getConnName(this.concernRoutes.classifier[0])}</span>
        </div>
        <div class="summary-item">
          <span class="label">Embedding Route</span>
          <span class="value">${this._getConnName(this.concernRoutes.embedding[0])}</span>
        </div>
        <div class="summary-item">
          <span class="label">MCP Servers</span>
          <span class="value">${this.mcpServers.length ? `${this.mcpServers.length} configured (${this.mcpServers.map(s => s.name).join(', ')})` : 'None'}</span>
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-primary" @click="${this._onCommit}">
          Commit Configuration & Start Chat →
        </button>
      </div>
    `;
  }
}
