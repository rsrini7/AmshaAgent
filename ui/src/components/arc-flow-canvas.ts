import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { AgentTraceEvent } from '../types/trace';
import './arc-harness-conveyor';
import './arc-topology-graph';
import './arc-overflow-ledger';
import { computeAnomalyCounts } from './arc-overflow-ledger';
import { isParadigmSupported } from './arc-topology-graph';

@customElement('arc-flow-canvas')
export class ArcFlowCanvas extends LitElement {
  @property({ type: Array }) events: AgentTraceEvent[] = [];
  @property({ type: String }) runId = '';
  @property({ type: String }) paradigm = '';

  @state() private _viewMode: 'conveyor' | 'react' | 'multi_agent' = 'conveyor';
  @state() private _ledgerOpen = false;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--bg-base, #0a0b0f);
      color: var(--text-primary, #ffffff);
      overflow-y: auto;
      scrollbar-width: thin;
      scrollbar-color: var(--border, #242938) transparent;
    }

    :host::-webkit-scrollbar {
      width: 6px;
      height: 6px;
    }

    :host::-webkit-scrollbar-track {
      background: transparent;
    }

    :host::-webkit-scrollbar-thumb {
      background: var(--border, #242938);
      border-radius: 999px;
    }

    :host::-webkit-scrollbar-thumb:hover {
      background: var(--text-faint, #4a5568);
    }

    .canvas-toolbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 24px;
      border-bottom: 1px solid var(--border, #242938);
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 70%, transparent);
      flex-shrink: 0;
      gap: 16px;
      flex-wrap: wrap;
    }

    .toolbar-left {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }

    .run-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      padding: 3px 8px;
      border-radius: 6px;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
    }

    .run-id-value {
      font-family: monospace;
      color: var(--text-primary, #ffffff);
    }

    .toolbar-combo-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      padding: 3px 8px;
      border-radius: 999px;
      text-transform: uppercase;
    }

    .toolbar-badge-retry {
      background: rgba(245, 158, 11, 0.2);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }

    .toolbar-badge-error {
      background: rgba(239, 68, 68, 0.2);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }

    .toolbar-badge-stuck {
      background: rgba(239, 68, 68, 0.3);
      color: #ff6b6b;
      border: 1px solid #ef4444;
    }

    .toolbar-right {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .ledger-toggle-btn {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: background-color 0.2s, color 0.2s, border-color 0.2s;
    }

    .ledger-toggle-btn.active {
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      color: var(--text-primary, #ffffff);
      border-color: var(--accent, #6c63ff);
    }

    .ledger-toggle-btn:hover {
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
    }

    .view-toggle-group {
      display: inline-flex;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      padding: 2px;
      gap: 2px;
    }

    .view-btn {
      background: transparent;
      border: none;
      color: var(--text-muted, #8b949e);
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      transition: background-color 0.2s, color 0.2s;
    }

    .view-btn.active {
      background: var(--accent, #6c63ff);
      color: #ffffff;
    }

    .view-btn:hover:not(.active):not(:disabled) {
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 80%, transparent);
      color: var(--text-primary, #ffffff);
    }

    .view-btn:disabled {
      opacity: 0.35;
      cursor: not-allowed;
    }

    .canvas-body {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }

    .ledger-section {
      padding: 16px 24px;
      border-bottom: 1px solid var(--border, #242938);
      background: color-mix(in srgb, var(--bg-surface, #131722) 40%, transparent);
    }
  `;

  public setViewMode(mode: 'conveyor' | 'react' | 'multi_agent') {
    const norm = (this.paradigm || '').trim().toUpperCase();
    if (mode === 'react' && norm !== 'REACT') {
      this._viewMode = 'conveyor';
      return;
    }
    if (mode === 'multi_agent' && norm !== 'MULTI_AGENT' && norm !== 'GROUP_CONSULTATION') {
      this._viewMode = 'conveyor';
      return;
    }
    this._viewMode = mode;
  }

  get viewMode(): 'conveyor' | 'react' | 'multi_agent' {
    return this._viewMode;
  }

  render() {
    const activeRun = this.runId || (this.events.length > 0 ? this.events[0].runId : 'idle');
    const norm = (this.paradigm || '').trim().toUpperCase();
    const reactAvailable = norm === 'REACT';
    const multiAgentAvailable = norm === 'MULTI_AGENT' || norm === 'GROUP_CONSULTATION';
    const anomalyCounts = computeAnomalyCounts(this.events);

    return html`
      <div class="canvas-toolbar" role="toolbar" aria-label="Visual Cockpit Controls">
        <div class="toolbar-left">
          <div class="run-badge">
            <span>Execution Run:</span>
            <span class="run-id-value">${activeRun ? activeRun.slice(0, 10) : 'none'}</span>
          </div>
          <div class="run-badge">
            <span>Observed Events:</span>
            <span class="run-id-value">${this.events.length}</span>
          </div>

          ${anomalyCounts.retryCount > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-retry">⚡ ${anomalyCounts.retryCount}x RETRY</span>`
            : nothing}

          ${anomalyCounts.errorCount > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-error">⚠ ${anomalyCounts.errorCount}x TOOL ERROR</span>`
            : nothing}

          ${anomalyCounts.stuckCount > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-stuck">🔥 LOOP STUCK</span>`
            : nothing}
        </div>

        <div class="toolbar-right">
          <button
            class="ledger-toggle-btn ${this._ledgerOpen ? 'active' : ''}"
            @click="${() => (this._ledgerOpen = !this._ledgerOpen)}"
            aria-label="Toggle overflow ledger"
            aria-expanded="${this._ledgerOpen}"
          >
            <span>Overflow Ledger</span>
            <span style="font-family: monospace;">(${anomalyCounts.totalAnomalies})</span>
          </button>

          <div class="view-toggle-group" role="radiogroup" aria-label="Topology View Mode">
            <button
              class="view-btn ${this._viewMode === 'conveyor' ? 'active' : ''}"
              role="radio"
              aria-checked="${this._viewMode === 'conveyor'}"
              @click="${() => this.setViewMode('conveyor')}"
            >
              Harness Conveyor
            </button>
            <button
              class="view-btn ${this._viewMode === 'react' ? 'active' : ''}"
              role="radio"
              aria-checked="${this._viewMode === 'react'}"
              ?disabled="${!reactAvailable}"
              title="${reactAvailable ? 'View ReAct execution loop' : 'Requires backend REACT paradigm metadata'}"
              @click="${() => reactAvailable && this.setViewMode('react')}"
            >
              ReAct Loop
            </button>
            <button
              class="view-btn ${this._viewMode === 'multi_agent' ? 'active' : ''}"
              role="radio"
              aria-checked="${this._viewMode === 'multi_agent'}"
              ?disabled="${!multiAgentAvailable}"
              title="${multiAgentAvailable ? 'View Multi-Agent hub-and-spoke graph' : 'Requires backend MULTI_AGENT paradigm metadata'}"
              @click="${() => multiAgentAvailable && this.setViewMode('multi_agent')}"
            >
              Multi-Agent
            </button>
          </div>
        </div>
      </div>

      ${this._ledgerOpen
        ? html`
            <div class="ledger-section">
              <arc-overflow-ledger .events=${this.events} ?open=${true}></arc-overflow-ledger>
            </div>
          `
        : nothing}

      <div class="canvas-body" role="main">
        ${this._viewMode === 'conveyor'
          ? html`<arc-harness-conveyor .events=${this.events}></arc-harness-conveyor>`
          : html`<arc-topology-graph
              .events=${this.events}
              .viewMode=${this._viewMode}
              .paradigm=${this.paradigm}
            ></arc-topology-graph>`}
      </div>
    `;
  }
}
