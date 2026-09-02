import { LitElement, css, html, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { AgentTraceEvent, TraceMetrics } from '../types/trace';

export type GateDecision = 'OPEN' | 'CLOSED' | 'BLOCKED' | 'ERROR' | 'NOT_INSTRUMENTED';

export interface GateState {
  decision: GateDecision;
  statusText: string;
  detail: string | null;
  metrics: TraceMetrics | null;
  observed: boolean;
}

export interface RetrievalCounts {
  retrieveCount: number;
  skipCount: number;
  deniedCount: number;
  errorCount: number;
  totalDecisions: number;
}

export function computeRetrievalCounts(events: AgentTraceEvent[]): RetrievalCounts {
  let retrieveCount = 0;
  let skipCount = 0;
  let deniedCount = 0;
  let errorCount = 0;

  if (Array.isArray(events)) {
    for (const ev of events) {
      if (ev.stage === 'memory_decision') {
        const state = resolveGateState(ev);
        switch (state.decision) {
          case 'OPEN':
            retrieveCount++;
            break;
          case 'CLOSED':
            skipCount++;
            break;
          case 'BLOCKED':
            deniedCount++;
            break;
          case 'ERROR':
            errorCount++;
            break;
        }
      }
    }
  }

  return {
    retrieveCount,
    skipCount,
    deniedCount,
    errorCount,
    totalDecisions: retrieveCount + skipCount + deniedCount + errorCount,
  };
}

export function resolveGateState(event: AgentTraceEvent | null | undefined): GateState {
  if (!event || event.stage !== 'memory_decision') {
    return {
      decision: 'NOT_INSTRUMENTED',
      statusText: 'NOT INSTRUMENTED',
      detail: 'No memory decision observed in this execution turn.',
      metrics: null,
      observed: false,
    };
  }

  const rawStatus = (event.status || '').toUpperCase();
  let decision: GateDecision = 'OPEN';
  let statusText = 'RETRIEVE';

  if (rawStatus === 'ERROR' || rawStatus === 'FAILURE') {
    decision = 'ERROR';
    statusText = 'ERROR';
  } else if (rawStatus === 'DENIED' || rawStatus === 'BLOCKED') {
    decision = 'BLOCKED';
    statusText = 'DENIED';
  } else if (rawStatus === 'SKIP' || rawStatus === 'BYPASS' || rawStatus === 'NO_RETRIEVAL') {
    decision = 'CLOSED';
    statusText = 'SKIP';
  } else if (rawStatus === 'RETRIEVE' || rawStatus === 'PASS' || rawStatus === 'ALLOW' || rawStatus === 'COMPLETED') {
    decision = 'OPEN';
    statusText = rawStatus === 'PASS' ? 'PASS' : 'RETRIEVE';
  } else {
    decision = 'OPEN';
    statusText = rawStatus;
  }

  return {
    decision,
    statusText,
    detail: event.detail || null,
    metrics: event.metrics || null,
    observed: true,
  };
}

@customElement('arc-retrieval-gate')
export class ArcRetrievalGate extends LitElement {
  @property({ type: Object }) event: AgentTraceEvent | null = null;
  @property({ type: Array }) events: AgentTraceEvent[] = [];

  static styles = css`
    :host {
      display: block;
      font-family: inherit;
    }

    .gate-container {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 12px;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      position: relative;
      transition: border-color 0.25s ease, box-shadow 0.25s ease;
    }

    .gate-container.decision-OPEN {
      border-color: color-mix(in srgb, var(--accent-secondary, #00d4aa) 60%, transparent);
      box-shadow: 0 0 14px color-mix(in srgb, var(--accent-secondary, #00d4aa) 15%, transparent);
    }

    .gate-container.decision-CLOSED {
      border-color: color-mix(in srgb, #f59e0b 60%, transparent);
      box-shadow: 0 0 12px rgba(245, 158, 11, 0.15);
    }

    .gate-container.decision-BLOCKED,
    .gate-container.decision-ERROR {
      border-color: #ef4444;
      box-shadow: 0 0 14px rgba(239, 68, 68, 0.2);
    }

    .gate-container.decision-NOT_INSTRUMENTED {
      opacity: 0.55;
      border-style: dashed;
      background: color-mix(in srgb, var(--bg-surface, #131722) 50%, transparent);
    }

    .gate-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .gate-title {
      font-size: 13px;
      font-weight: 700;
      color: var(--text-primary, #ffffff);
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .gate-badge {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      padding: 2px 8px;
      border-radius: 999px;
    }

    .badge-OPEN {
      background: color-mix(in srgb, var(--accent-secondary, #00d4aa) 15%, transparent);
      color: var(--accent-secondary, #00d4aa);
      border: 1px solid color-mix(in srgb, var(--accent-secondary, #00d4aa) 40%, transparent);
    }

    .badge-CLOSED {
      background: rgba(245, 158, 11, 0.15);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }

    .badge-BLOCKED,
    .badge-ERROR {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }

    .badge-NOT_INSTRUMENTED {
      background: transparent;
      color: var(--text-muted, #8b949e);
      border: 1px solid var(--border, #242938);
    }

    .gate-visual {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 10px 0;
    }

    .gate-svg {
      width: 140px;
      height: 48px;
    }

    .gate-detail {
      font-size: 11px;
      color: var(--text-muted, #8b949e);
      line-height: 1.4;
      background: color-mix(in srgb, var(--bg-base, #0a0b0f) 50%, transparent);
      padding: 8px 10px;
      border-radius: 6px;
      border: 1px solid var(--border, #242938);
      word-break: break-word;
    }

    .metrics-row {
      display: flex;
      gap: 12px;
      font-size: 10px;
      font-family: monospace;
      color: var(--text-muted, #8b949e);
    }

    .metric-chip {
      background: var(--bg-base, #0a0b0f);
      padding: 2px 6px;
      border-radius: 4px;
      border: 1px solid var(--border, #242938);
    }

    .gate-counters {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      margin-top: 8px;
    }

    .counter-badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 9999px;
      font-size: 10px;
      font-weight: 600;
      letter-spacing: 0.02em;
    }

    .counter-retrieve {
      background: rgba(0, 212, 170, 0.15);
      color: #00d4aa;
      border: 1px solid rgba(0, 212, 170, 0.3);
    }

    .counter-skip {
      background: rgba(245, 158, 11, 0.15);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.3);
    }

    .counter-denied {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.3);
    }

    .counter-error {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.3);
    }

    @media (prefers-reduced-motion: reduce) {
      .gate-container {
        transition: none !important;
      }
    }
  `;

  render() {
    const effectiveEvents =
      this.events && this.events.length > 0
        ? this.events
        : this.event
        ? [this.event]
        : [];

    const effectiveEvent =
      this.event ||
      (effectiveEvents.length > 0
        ? [...effectiveEvents].reverse().find((e) => e.stage === 'memory_decision') || null
        : null);

    const state = resolveGateState(effectiveEvent);
    const counts = computeRetrievalCounts(effectiveEvents);

    return html`
      <div
        class="gate-container decision-${state.decision}"
        role="region"
        aria-label="Visual Memory and Retrieval Gate: ${state.statusText}"
      >
        <div class="gate-header">
          <div class="gate-title">
            <span>Memory / Retrieval Gate</span>
          </div>
          <span class="gate-badge badge-${state.decision}">
            ${state.decision === 'NOT_INSTRUMENTED' ? 'NOT INSTRUMENTED' : `GATE: ${state.statusText}`}
          </span>
        </div>

        <div class="gate-visual" aria-hidden="true">
          ${this._renderGateSvg(state.decision)}
        </div>

        <div class="gate-detail">
          ${state.detail || 'No memory decision observed in this execution turn.'}
        </div>

        ${counts.totalDecisions > 0
          ? html`
              <div class="gate-counters" aria-label="Retrieval Decision Counters">
                <span class="counter-badge counter-retrieve">RETRIEVE: ${counts.retrieveCount}</span>
                <span class="counter-badge counter-skip">SKIP: ${counts.skipCount}</span>
                ${counts.deniedCount > 0
                  ? html`<span class="counter-badge counter-denied">DENIED: ${counts.deniedCount}</span>`
                  : nothing}
                ${counts.errorCount > 0
                  ? html`<span class="counter-badge counter-error">ERROR: ${counts.errorCount}</span>`
                  : nothing}
              </div>
            `
          : nothing}

        ${state.metrics && Object.keys(state.metrics).length > 0
          ? html`
              <div class="metrics-row" aria-label="Retrieval Metrics">
                ${Object.entries(state.metrics).map(([k, v]) =>
                  v !== null && v !== undefined
                    ? html`<span class="metric-chip">${k}: ${v}</span>`
                    : nothing
                )}
              </div>
            `
          : nothing}
      </div>
    `;
  }

  private _renderGateSvg(decision: GateDecision) {
    if (decision === 'NOT_INSTRUMENTED') {
      return html`
        <svg class="gate-svg" viewBox="0 0 140 48" fill="none" xmlns="http://www.w3.org/2000/svg">
          <line x1="10" y1="24" x2="50" y2="24" stroke="#8b949e" stroke-dasharray="4 4" stroke-width="2"/>
          <rect x="52" y="10" width="8" height="28" rx="2" stroke="#8b949e" stroke-dasharray="2 2" fill="none"/>
          <rect x="80" y="10" width="8" height="28" rx="2" stroke="#8b949e" stroke-dasharray="2 2" fill="none"/>
          <line x1="90" y1="24" x2="130" y2="24" stroke="#8b949e" stroke-dasharray="4 4" stroke-width="2"/>
        </svg>
      `;
    }

    if (decision === 'OPEN') {
      return html`
        <svg class="gate-svg" viewBox="0 0 140 48" fill="none" xmlns="http://www.w3.org/2000/svg">
          <line x1="10" y1="24" x2="50" y2="24" stroke="#00d4aa" stroke-width="2"/>
          <!-- Gate barriers open outward -->
          <rect x="50" y="8" width="6" height="20" rx="2" transform="rotate(-30 50 8)" fill="#00d4aa"/>
          <rect x="84" y="20" width="6" height="20" rx="2" transform="rotate(30 84 20)" fill="#00d4aa"/>
          <path d="M50 24 Q 70 24 90 24" stroke="#00d4aa" stroke-width="2" stroke-dasharray="3 3"/>
          <line x1="90" y1="24" x2="130" y2="24" stroke="#00d4aa" stroke-width="2"/>
        </svg>
      `;
    }

    if (decision === 'CLOSED') {
      return html`
        <svg class="gate-svg" viewBox="0 0 140 48" fill="none" xmlns="http://www.w3.org/2000/svg">
          <line x1="10" y1="24" x2="60" y2="24" stroke="#f59e0b" stroke-width="2"/>
          <!-- Gate barriers closed blocking the channel -->
          <rect x="62" y="12" width="6" height="24" rx="2" fill="#f59e0b"/>
          <rect x="72" y="12" width="6" height="24" rx="2" fill="#f59e0b"/>
          <line x1="80" y1="24" x2="130" y2="24" stroke="#f59e0b" stroke-dasharray="3 3" stroke-width="2"/>
        </svg>
      `;
    }

    // BLOCKED or ERROR
    return html`
      <svg class="gate-svg" viewBox="0 0 140 48" fill="none" xmlns="http://www.w3.org/2000/svg">
        <line x1="10" y1="24" x2="60" y2="24" stroke="#ef4444" stroke-width="2"/>
        <!-- Barrier locked with cross -->
        <rect x="66" y="10" width="8" height="28" rx="2" fill="#ef4444"/>
        <line x1="60" y1="14" x2="80" y2="34" stroke="#ffffff" stroke-width="2"/>
        <line x1="80" y1="14" x2="60" y2="34" stroke="#ffffff" stroke-width="2"/>
        <line x1="76" y1="24" x2="130" y2="24" stroke="#ef4444" stroke-dasharray="2 2" stroke-width="1.5"/>
      </svg>
    `;
  }
}
