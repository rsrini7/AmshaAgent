import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { AgentTraceEvent, TraceStage, StationCode } from '../types/trace';
import { normalizeEventStatus } from './arc-harness-conveyor';

export interface FlowStageSummary {
  stage: TraceStage;
  label: string;
  observed: boolean;
  status: string;
  station: StationCode | null;
  detail: string | null;
  riskLevel: string | null;
}

export interface TurnStats {
  guardDecision: string;
  latencyMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  runId: string;
  turnNumber: number;
}

export const CANONICAL_FLOW_STAGES: ReadonlyArray<{ stage: TraceStage; label: string }> = [
  { stage: 'turn_start', label: 'Inbound' },
  { stage: 'preflight', label: 'Preflight' },
  { stage: 'route', label: 'Routing' },
  { stage: 'memory_decision', label: 'Memory Decision' },
  { stage: 'guard_check', label: 'Tool Guard' },
  { stage: 'llm_reasoning', label: 'Reasoning' },
  { stage: 'tool_start', label: 'Tool Exec' },
  { stage: 'quality_check', label: 'Quality' },
  { stage: 'consolidation', label: 'Consolidation' },
  { stage: 'turn_end', label: 'Outbound' },
];

/**
 * Derives the stage breakdown from actual events.
 * Missing stages are explicitly marked observed: false and status: 'NOT_OBSERVED'.
 * Never invents or fabricates steps.
 */
export function computeFlowStages(events: AgentTraceEvent[]): FlowStageSummary[] {
  const eventsByStage = new Map<TraceStage, AgentTraceEvent>();
  for (const ev of events) {
    if (!eventsByStage.has(ev.stage)) {
      eventsByStage.set(ev.stage, ev);
    }
  }

  return CANONICAL_FLOW_STAGES.map(({ stage, label }) => {
    const ev = eventsByStage.get(stage);
    if (ev) {
      return {
        stage,
        label,
        observed: true,
        status: ev.status || 'OBSERVED',
        station: ev.station,
        detail: ev.detail,
        riskLevel: ev.riskLevel,
      };
    }
    return {
      stage,
      label,
      observed: false,
      status: 'NOT_OBSERVED',
      station: null,
      detail: null,
      riskLevel: null,
    };
  });
}

/**
 * Derives turn statistics directly from recorded metrics and timestamps.
 */
export function computeTurnStats(events: AgentTraceEvent[]): TurnStats {
  let guardDecision = 'NOT_OBSERVED';
  let promptTokens = 0;
  let completionTokens = 0;
  let runId = '';
  let turnNumber = 0;
  let minTime = Infinity;
  let maxTime = -Infinity;
  let durationMsFromMetrics = 0;

  for (const ev of events) {
    if (!runId && ev.runId) runId = ev.runId;
    if (typeof ev.turnNumber === 'number' && ev.turnNumber > turnNumber) {
      turnNumber = ev.turnNumber;
    }

    if (
      (ev.stage === 'guard_check' ||
        ev.stage === 'hitl_requested' ||
        ev.stage === 'hitl_resolved' ||
        ev.stage === 'hitl_timeout') &&
      ev.status
    ) {
      guardDecision = ev.status;
    }

    if (ev.metrics) {
      if (typeof ev.metrics.promptTokens === 'number') {
        promptTokens += ev.metrics.promptTokens;
      }
      if (typeof ev.metrics.completionTokens === 'number') {
        completionTokens += ev.metrics.completionTokens;
      }
      if (typeof ev.metrics.durationMs === 'number' && ev.metrics.durationMs > 0) {
        durationMsFromMetrics += ev.metrics.durationMs;
      }
    }

    if (ev.timestamp) {
      const ts = new Date(ev.timestamp).getTime();
      if (!isNaN(ts)) {
        if (ts < minTime) minTime = ts;
        if (ts > maxTime) maxTime = ts;
      }
    }
  }

  const timestampLatency = maxTime >= minTime && minTime !== Infinity ? maxTime - minTime : 0;
  const latencyMs = durationMsFromMetrics > 0 ? durationMsFromMetrics : timestampLatency;

  return {
    guardDecision,
    latencyMs,
    promptTokens,
    completionTokens,
    totalTokens: promptTokens + completionTokens,
    runId,
    turnNumber: turnNumber || 1,
  };
}

@customElement('arc-run-flow-card')
export class ArcRunFlowCard extends LitElement {
  @property({ type: Array }) events: AgentTraceEvent[] = [];
  @property({ type: String }) runId = '';
  @property({ type: Number }) turnNumber = 1;
  @state() private _showStats = false;

  static styles = css`
    :host {
      display: block;
      margin: 8px 0;
      font-family: inherit;
    }

    .card {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 12px;
      padding: 12px 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 11px;
      color: var(--text-muted, #8b949e);
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
    }

    .turn-badge {
      background: color-mix(in srgb, var(--accent, #6c63ff) 15%, transparent);
      color: var(--accent, #6c63ff);
      padding: 2px 6px;
      border-radius: 4px;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .stages-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      align-items: center;
    }

    .stage-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      padding: 3px 8px;
      border-radius: 999px;
      border: 1px solid var(--border, #242938);
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 80%, transparent);
      color: var(--text-muted, #8b949e);
      transition: border-color 0.2s, background-color 0.2s;
    }

    .stage-chip.observed {
      border-color: color-mix(in srgb, var(--accent-secondary, #00d4aa) 40%, transparent);
      background: color-mix(in srgb, var(--accent-secondary, #00d4aa) 10%, transparent);
      color: var(--text-primary, #ffffff);
    }

    .stage-chip.not-observed {
      border-style: dashed;
      opacity: 0.5;
      background: transparent;
    }

    .stage-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--text-muted, #8b949e);
    }

    .stage-chip.observed .stage-dot {
      background: var(--accent-secondary, #00d4aa);
    }

    .stage-status {
      font-size: 9px;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .toggle-btn {
      background: transparent;
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 6px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .toggle-btn:hover {
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
    }

    .stats-panel {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
      gap: 8px;
      padding-top: 8px;
      border-top: 1px solid var(--border, #242938);
      font-size: 11px;
    }

    .stat-item {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .stat-label {
      color: var(--text-muted, #8b949e);
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .stat-value {
      color: var(--text-primary, #ffffff);
      font-weight: 600;
    }

    @media (prefers-reduced-motion: reduce) {
      .stage-chip, .toggle-btn {
        transition: none !important;
        animation: none !important;
      }
    }
  `;

  render() {
    const stages = computeFlowStages(this.events);
    const stats = computeTurnStats(this.events);
    const displayRunId = this.runId || stats.runId || 'live-run';
    const displayTurn = this.turnNumber || stats.turnNumber;

    return html`
      <div class="card" role="region" aria-label="Run Flow Summary">
        <div class="card-header">
          <div class="header-left">
            <span class="turn-badge">Turn ${displayTurn}</span>
            <span class="run-id" title="${displayRunId}">Run: ${displayRunId.slice(0, 8)}</span>
          </div>
          <button
            class="toggle-btn turn-stats-toggle"
            @click="${() => (this._showStats = !this._showStats)}"
            aria-expanded="${this._showStats}"
          >
            ${this._showStats ? '▲ Hide Stats' : '▼ Turn Stats'}
          </button>
        </div>

        <div class="stages-bar">
          ${stages.map(
            (s) => html`
              <span
                class="stage-chip ${s.observed ? 'observed' : 'not-observed'}"
                title="${s.label}: ${s.status}"
              >
                <span class="stage-dot"></span>
                <span class="stage-name">${s.label}</span>
                <span class="stage-status">(${s.status})</span>
              </span>
            `
          )}
        </div>

        ${this._showStats
          ? html`
              <div class="stats-panel">
                <div class="stat-item">
                  <span class="stat-label">Guard Decision</span>
                  <span class="stat-value stat-guard">${stats.guardDecision}</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">Latency</span>
                  <span class="stat-value stat-latency">${stats.latencyMs}ms</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">Prompt Tokens</span>
                  <span class="stat-value stat-prompt-tokens">${stats.promptTokens}</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">Completion Tokens</span>
                  <span class="stat-value stat-completion-tokens">${stats.completionTokens}</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">Total Tokens</span>
                  <span class="stat-value stat-total-tokens">${stats.totalTokens}</span>
                </div>
              </div>
            `
          : nothing}
      </div>
    `;
  }
}
