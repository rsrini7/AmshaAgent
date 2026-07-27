import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { ConcernRoutes, ConnectionDraft } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-concern-routing')
export class ArcOnboardingStepConcernRouting extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }

      .routing-title {
        font-size: var(--font-size-md);
        font-weight: var(--font-weight-bold);
        color: var(--text-primary);
        margin-bottom: var(--space-4);
      }

      .routing-grid {
        display: flex;
        flex-direction: column;
        gap: var(--space-4);
        margin-bottom: var(--space-6);
      }

      .routing-card {
        padding: var(--space-4);
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
      }

      .routing-card-title {
        font-size: var(--font-size-sm);
        font-weight: var(--font-weight-bold);
        color: var(--text-primary);
        margin-bottom: var(--space-2);
      }

      .routing-card-desc {
        font-size: 11px;
        color: var(--text-muted);
        margin-bottom: var(--space-3);
      }
    `
  ];

  @property({ type: Array }) connections: ConnectionDraft[] = [];
  @property({ type: Array }) catalog: any[] = [];
  @property({ type: Object }) concernRoutes: ConcernRoutes = {
    agentLoop: [],
    compaction: [],
    classifier: [],
    embedding: []
  };

  private _onChange(concern: keyof ConcernRoutes, connectionIndex: number) {
    const updated = { ...this.concernRoutes };
    updated[concern] = [connectionIndex];
    this.dispatchEvent(
      new CustomEvent('update-routes', {
        detail: { concernRoutes: updated },
        bubbles: true,
        composed: true
      })
    );
  }

  private _onNext() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  render() {
    // Determine embedding capable connections
    const embeddingConnections = this.connections.map((c, idx) => {
      const prov = this.catalog.find(p => p.id === c.provider) || {};
      return { conn: c, index: idx, capable: !!prov.embeddingCapable, prov };
    }).filter(item => item.capable);

    // General connections (all LLMs)
    const llmConnections = this.connections.map((c, idx) => {
      const prov = this.catalog.find(p => p.id === c.provider) || {};
      return { conn: c, index: idx, prov };
    });

    const activeAgentLoop = this.concernRoutes.agentLoop[0] !== undefined ? this.concernRoutes.agentLoop[0] : 0;
    const activeCompaction = this.concernRoutes.compaction[0] !== undefined ? this.concernRoutes.compaction[0] : 0;
    const activeClassifier = this.concernRoutes.classifier[0] !== undefined ? this.concernRoutes.classifier[0] : 0;
    const activeEmbedding = this.concernRoutes.embedding[0] !== undefined ? this.concernRoutes.embedding[0] : 
      (embeddingConnections.length > 0 ? embeddingConnections[0].index : 0);

    return html`
      <div class="routing-title">Assign Connections to Core Concerns</div>

      <div class="routing-grid">
        <!-- Agent Loop -->
        <div class="routing-card">
          <div class="routing-card-title">Agent Execution Loop</div>
          <div class="routing-card-desc">The primary model used to orchestrate complex reasoning steps and tools.</div>
          <select class="field-select" .value="${activeAgentLoop}"
                  @change="${(e: any) => this._onChange('agentLoop', Number(e.target.value))}">
            ${llmConnections.map(item => html`
              <option value="${item.index}">${item.prov.name} — ${item.conn.modelId}</option>
            `)}
          </select>
        </div>

        <!-- Compaction -->
        <div class="routing-card">
          <div class="routing-card-title">Memory Compaction</div>
          <div class="routing-card-desc">The model responsible for summarization, synthesis, and compressing chat logs.</div>
          <select class="field-select" .value="${activeCompaction}"
                  @change="${(e: any) => this._onChange('compaction', Number(e.target.value))}">
            ${llmConnections.map(item => html`
              <option value="${item.index}">${item.prov.name} — ${item.conn.modelId}</option>
            `)}
          </select>
        </div>

        <!-- Classifier -->
        <div class="routing-card">
          <div class="routing-card-title">Task Classifier</div>
          <div class="routing-card-desc">Lightweight decision model used to parse user intent and classify tasks.</div>
          <select class="field-select" .value="${activeClassifier}"
                  @change="${(e: any) => this._onChange('classifier', Number(e.target.value))}">
            ${llmConnections.map(item => html`
              <option value="${item.index}">${item.prov.name} — ${item.conn.modelId}</option>
            `)}
          </select>
        </div>

        <!-- Embedding -->
        <div class="routing-card">
          <div class="routing-card-title">Embedding Engine</div>
          <div class="routing-card-desc">Used to generate vector embeddings for memory search and RAG indices (requires embedding capabilities).</div>
          <select class="field-select" .value="${activeEmbedding}"
                  @change="${(e: any) => this._onChange('embedding', Number(e.target.value))}">
            ${embeddingConnections.length === 0
              ? html`<option value="0" disabled>No embedding-capable connection configured. Using default.</option>`
              : embeddingConnections.map(item => html`
                  <option value="${item.index}">${item.prov.name} — ${item.conn.modelId}</option>
                `)}
          </select>
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-primary" @click="${this._onNext}">
          Continue to Optional Steps →
        </button>
      </div>
    `;
  }
}
