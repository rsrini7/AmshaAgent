import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { AgentTraceEvent, StationCode, TraceMetrics, TraceStage } from '../types/trace';
import './arc-retrieval-gate';
import './arc-node-card';
import { computeAnomalyCounts } from './arc-overflow-ledger';

export const STATION_BACKEND_SYMBOLS: Partial<Record<StationCode, string>> = {
  station_01_inbound: 'AgentLoop',
  station_02_preflight: 'AgentLoop',
  station_03_routing: 'AgentLoop',
  station_04_memory_decision: 'ChatHistoryAssembler',
  station_05_tool_guard: 'ToolInvocationGuard',
  station_06_reasoning: 'EngineStepHandler',
  station_07_tool_execution: 'SequentialToolExecutor',
  station_08_quality: 'FinishActionHandler',
  station_09_consolidation: 'CompactionEventBridge',
  station_10_outbound: 'AgentLoop',
};

export type StationStatus =
  | 'NOT_INSTRUMENTED'
  | 'IDLE'
  | 'ACTIVE'
  | 'COMPLETED'
  | 'DENIED'
  | 'HITL_REQUIRED'
  | 'ERROR'
  | 'LOOP_STUCK';

export interface StationInfo {
  code: StationCode;
  number: number;
  name: string;
  status: StationStatus;
  detail: string | null;
  riskLevel: string | null;
  metrics: TraceMetrics | null;
  timestamp?: string;
}

export const CONVEYOR_STATION_DEFINITIONS: ReadonlyArray<{
  code: StationCode;
  number: number;
  name: string;
}> = [
  { code: 'station_01_inbound', number: 1, name: 'Inbound' },
  { code: 'station_02_preflight', number: 2, name: 'Preflight' },
  { code: 'station_03_routing', number: 3, name: 'Intent / Routing' },
  { code: 'station_04_memory_decision', number: 4, name: 'Memory Decision' },
  { code: 'station_05_tool_guard', number: 5, name: 'Tool Guard / HITL' },
  { code: 'station_06_reasoning', number: 6, name: 'Reasoning' },
  { code: 'station_07_tool_execution', number: 7, name: 'Tool Execution' },
  { code: 'station_08_quality', number: 8, name: 'Quality Check' },
  { code: 'station_09_consolidation', number: 9, name: 'Consolidation' },
  { code: 'station_10_outbound', number: 10, name: 'Outbound' },
];

/**
 * Normalizes disparate producer wire statuses (ALLOW, APPROVED, DENIED, TIMEOUT,
 * REASONING, PASS, SALVAGED, FAILURE, etc.) into the canonical StationStatus contract.
 */
export function normalizeEventStatus(
  stage: TraceStage,
  rawStatus: string | null | undefined
): StationStatus {
  const s = (rawStatus || '').trim().toUpperCase();

  // Guard & HITL stages
  if (
    stage === 'guard_check' ||
    stage === 'hitl_requested' ||
    stage === 'hitl_resolved' ||
    stage === 'hitl_timeout'
  ) {
    if (
      s === 'DENIED' ||
      s === 'DENIED_RESTART' ||
      s === 'TIMEOUT' ||
      stage === 'hitl_timeout'
    ) {
      return 'DENIED';
    }
    if (s === 'HITL_REQUIRED' || s === 'AWAITING_APPROVAL' || stage === 'hitl_requested') {
      return 'HITL_REQUIRED';
    }
    if (s === 'ALLOW' || s === 'APPROVED' || s === 'RESUMED' || stage === 'hitl_resolved') {
      return 'COMPLETED';
    }
    return 'ACTIVE';
  }

  // Reasoning stages
  if (stage === 'loop_stuck' || s === 'STUCK') {
    return 'LOOP_STUCK';
  }
  if (stage === 'llm_reasoning' || s === 'REASONING') {
    return s === 'COMPLETED' ? 'COMPLETED' : 'ACTIVE';
  }

  // Tool execution stages
  if (stage === 'tool_error' || s === 'ERROR' || s === 'FAILURE') {
    return 'ERROR';
  }
  if (stage === 'retry' || s === 'RETRY' || stage === 'tool_start' || s === 'START') {
    return 'ACTIVE';
  }
  if (stage === 'tool_end' || s === 'END' || s === 'SUCCESS') {
    return 'COMPLETED';
  }

  // Quality check stage
  if (stage === 'quality_check') {
    if (s === 'FAILURE' || s === 'ERROR') {
      return 'ERROR';
    }
    if (s === 'RETRY') {
      return 'ACTIVE';
    }
    if (s === 'PASS' || s === 'SALVAGED' || s === 'COMPLETED') {
      return 'COMPLETED';
    }
  }

  // General wire statuses
  if (
    s === 'COMPLETED' ||
    s === 'END' ||
    s === 'ROUTED' ||
    s === 'PASS' ||
    s === 'SUCCESS' ||
    s === 'APPROVED' ||
    s === 'ALLOW'
  ) {
    return 'COMPLETED';
  }
  if (s === 'START' || s === 'RUNNING' || s === 'ACTIVE') {
    return 'ACTIVE';
  }
  if (s === 'DENIED' || s === 'TIMEOUT') {
    return 'DENIED';
  }
  if (s === 'ERROR' || s === 'FAILURE' || s === 'FAILED') {
    return 'ERROR';
  }

  return 'COMPLETED';
}

/**
 * Derives the active state of each station on the 10-station conveyor.
 * If no events were observed for a station, it renders NOT_INSTRUMENTED.
 * Never implies backend execution that was not observed.
 */
export function computeConveyorStations(events: AgentTraceEvent[]): StationInfo[] {
  return CONVEYOR_STATION_DEFINITIONS.map((def) => {
    // Find all events associated with this station code or corresponding stages
    const stationEvents = events.filter(
      (ev) =>
        ev.station === def.code ||
        (def.code === 'station_01_inbound' && ev.stage === 'turn_start') ||
        (def.code === 'station_02_preflight' && ev.stage === 'preflight') ||
        (def.code === 'station_03_routing' && ev.stage === 'route') ||
        (def.code === 'station_04_memory_decision' && ev.stage === 'memory_decision') ||
        (def.code === 'station_05_tool_guard' &&
          (ev.stage === 'guard_check' ||
            ev.stage === 'hitl_requested' ||
            ev.stage === 'hitl_resolved' ||
            ev.stage === 'hitl_timeout')) ||
        (def.code === 'station_06_reasoning' &&
          (ev.stage === 'llm_reasoning' || ev.stage === 'loop_stuck')) ||
        (def.code === 'station_07_tool_execution' &&
          (ev.stage === 'tool_start' ||
            ev.stage === 'tool_end' ||
            ev.stage === 'tool_error' ||
            ev.stage === 'retry')) ||
        (def.code === 'station_08_quality' && ev.stage === 'quality_check') ||
        (def.code === 'station_09_consolidation' && ev.stage === 'consolidation') ||
        (def.code === 'station_10_outbound' && ev.stage === 'turn_end')
    );

    if (stationEvents.length === 0) {
      return {
        code: def.code,
        number: def.number,
        name: def.name,
        status: 'NOT_INSTRUMENTED',
        detail: null,
        riskLevel: null,
        metrics: null,
      };
    }

    // Determine latest status across producers and stages
    const latest = stationEvents[stationEvents.length - 1];
    let status: StationStatus = 'COMPLETED';
    const latestStatusNorm = (latest.status || '').toUpperCase();
    const latestStage = latest.stage;

    if (def.code === 'station_01_inbound') {
      status = latestStatusNorm === 'START' ? 'ACTIVE' : 'COMPLETED';
    } else if (def.code === 'station_02_preflight') {
      if (latestStatusNorm === 'ERROR' || latestStatusNorm === 'FAILURE') {
        status = 'ERROR';
      } else if (latestStatusNorm === 'ABORTED_STALE') {
        status = 'DENIED';
      } else {
        status = 'COMPLETED';
      }
    } else if (def.code === 'station_03_routing') {
      if (latestStatusNorm === 'ERROR' || latestStatusNorm === 'FAILURE') {
        status = 'ERROR';
      } else if (latestStatusNorm === 'ROUTED' || latestStatusNorm === 'COMPLETED' || latestStatusNorm === 'DEFAULT') {
        status = 'COMPLETED';
      } else {
        const isLatestInStream = events[events.length - 1] === latest;
        status = isLatestInStream ? 'ACTIVE' : 'COMPLETED';
      }
    } else if (def.code === 'station_04_memory_decision') {
      if (latestStatusNorm === 'ERROR' || latestStatusNorm === 'FAILURE') {
        status = 'ERROR';
      } else {
        status = 'COMPLETED';
      }
    } else if (def.code === 'station_05_tool_guard') {
      status = normalizeEventStatus(latestStage, latest.status);
    } else if (def.code === 'station_06_reasoning') {
      if (latestStage === 'loop_stuck' || latestStatusNorm === 'STUCK') {
        status = 'LOOP_STUCK';
      } else if (latestStage === 'llm_reasoning') {
        const isLatestInStream = events[events.length - 1] === latest;
        status = isLatestInStream ? 'ACTIVE' : 'COMPLETED';
      } else {
        status = 'COMPLETED';
      }
    } else if (def.code === 'station_07_tool_execution') {
      status = normalizeEventStatus(latestStage, latest.status);
    } else if (def.code === 'station_08_quality') {
      status = normalizeEventStatus(latestStage, latest.status);
    } else if (def.code === 'station_09_consolidation') {
      if (latestStatusNorm === 'ERROR' || latestStatusNorm === 'FAILURE') {
        status = 'ERROR';
      } else {
        status = 'COMPLETED';
      }
    } else if (def.code === 'station_10_outbound') {
      status = 'COMPLETED';
    } else {
      if (latestStatusNorm === 'START') {
        status = 'ACTIVE';
      } else {
        status = 'COMPLETED';
      }
    }

    return {
      code: def.code,
      number: def.number,
      name: def.name,
      status,
      detail: latest.detail,
      riskLevel: latest.riskLevel,
      metrics: latest.metrics,
      timestamp: latest.timestamp,
    };
  });
}

@customElement('arc-harness-conveyor')
export class ArcHarnessConveyor extends LitElement {
  @property({ type: Array }) events: AgentTraceEvent[] = [];

  @state() private _xrayStation: StationCode | null = null;

  static styles = css`
    :host {
      display: block;
      width: 100%;
      padding: 24px;
      box-sizing: border-box;
    }

    .conveyor-container {
      display: flex;
      flex-direction: column;
      gap: 20px;
      max-width: 1100px;
      margin: 0 auto;
    }

    .conveyor-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border, #242938);
      padding-bottom: 12px;
    }

    .conveyor-title {
      font-size: 16px;
      font-weight: 700;
      color: var(--text-primary, #ffffff);
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .subtitle {
      font-size: 12px;
      color: var(--text-muted, #8b949e);
    }

    .station-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
    }

    .station-card {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 12px;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      position: relative;
      transition: border-color 0.25s ease, box-shadow 0.25s ease;
    }

    .station-card.status-COMPLETED {
      border-color: color-mix(in srgb, var(--accent-secondary, #00d4aa) 50%, transparent);
      box-shadow: 0 0 12px color-mix(in srgb, var(--accent-secondary, #00d4aa) 10%, transparent);
    }

    .station-card.status-ACTIVE {
      border-color: var(--accent, #6c63ff);
      box-shadow: 0 0 16px color-mix(in srgb, var(--accent, #6c63ff) 25%, transparent);
      animation: calm-pulse 2s infinite ease-in-out;
    }

    .station-card.status-HITL_REQUIRED {
      border-color: #f59e0b;
      box-shadow: 0 0 12px rgba(245, 158, 11, 0.2);
    }

    .station-card.status-DENIED,
    .station-card.status-ERROR,
    .station-card.status-LOOP_STUCK {
      border-color: #ef4444;
      box-shadow: 0 0 14px rgba(239, 68, 68, 0.2);
    }

    .station-card.status-NOT_INSTRUMENTED {
      opacity: 0.45;
      border-style: dashed;
      background: color-mix(in srgb, var(--bg-surface, #131722) 50%, transparent);
    }

    @keyframes calm-pulse {
      0%, 100% {
        box-shadow: 0 0 8px color-mix(in srgb, var(--accent, #6c63ff) 15%, transparent);
      }
      50% {
        box-shadow: 0 0 18px color-mix(in srgb, var(--accent, #6c63ff) 35%, transparent);
      }
    }

    .station-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .station-number {
      font-size: 10px;
      font-weight: 700;
      color: var(--text-muted, #8b949e);
      letter-spacing: 0.05em;
    }

    .status-pill {
      font-size: 9px;
      font-weight: 700;
      padding: 2px 6px;
      border-radius: 999px;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .pill-COMPLETED {
      background: color-mix(in srgb, var(--accent-secondary, #00d4aa) 15%, transparent);
      color: var(--accent-secondary, #00d4aa);
    }

    .pill-ACTIVE {
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      color: var(--accent, #6c63ff);
    }

    .pill-HITL_REQUIRED {
      background: rgba(245, 158, 11, 0.15);
      color: #f59e0b;
    }

    .pill-DENIED, .pill-ERROR, .pill-LOOP_STUCK {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
    }

    .pill-NOT_INSTRUMENTED {
      background: transparent;
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
    }

    .station-name {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-primary, #ffffff);
    }

    .station-detail {
      font-size: 11px;
      color: var(--text-muted, #8b949e);
      word-break: break-all;
      min-height: 1.3em;
    }

    .station-combo-badges {
      display: flex;
      gap: 4px;
      flex-wrap: wrap;
      margin-top: 4px;
    }

    .combo-pill {
      font-size: 9px;
      font-weight: 700;
      padding: 1px 5px;
      border-radius: 999px;
      letter-spacing: 0.03em;
    }

    .combo-pill.pill-retry {
      background: rgba(245, 158, 11, 0.2);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }

    .combo-pill.pill-error {
      background: rgba(239, 68, 68, 0.2);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }

    .combo-pill.pill-stuck {
      background: rgba(239, 68, 68, 0.25);
      color: #ff6b6b;
      border: 1px solid #ef4444;
    }

    .station-xray-btn {
      margin-top: 6px;
      background: transparent;
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      font-size: 10px;
      padding: 2px 6px;
      border-radius: 4px;
      cursor: pointer;
      align-self: flex-start;
      transition: background-color 0.2s, color 0.2s;
    }

    .station-xray-btn:hover {
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
    }

    /* Accessibility / reduced-motion override */
    @media (prefers-reduced-motion: reduce) {
      .station-card, .station-xray-btn {
        transition: none !important;
        animation: none !important;
      }
    }
  `;

  render() {
    const stations = computeConveyorStations(this.events);
    const anomalyCounts = computeAnomalyCounts(this.events);

    return html`
      <div class="conveyor-container" role="region" aria-label="Ten-Station Governed Conveyor">
        <div class="conveyor-header">
          <div>
            <div class="conveyor-title">
              Governed Execution Conveyor
            </div>
            <div class="subtitle">10-station pipeline driven exclusively by observed trace events</div>
          </div>
        </div>

        <div class="station-grid">
          ${stations.map((st) => {
            const backendSymbol = STATION_BACKEND_SYMBOLS[st.code];
            const isObserved = st.status !== 'NOT_INSTRUMENTED';

            return html`
              <div
                class="station-card status-${st.status}"
                data-station-code="${st.code}"
                aria-label="Station ${st.number}: ${st.name} - ${st.status}"
              >
                <div class="station-header">
                  <span class="station-number">0${st.number}</span>
                  <span class="status-pill pill-${st.status}">${st.status.replace('_', ' ')}</span>
                </div>
                <div class="station-name">${st.name}</div>

                ${st.code === 'station_04_memory_decision'
                  ? html`<arc-retrieval-gate .events=${this.events}></arc-retrieval-gate>`
                  : html`
                      <div class="station-detail">
                        ${st.detail
                          ? st.detail
                          : st.status === 'NOT_INSTRUMENTED'
                          ? 'No events observed'
                          : ''}
                      </div>
                    `}

                ${st.code === 'station_07_tool_execution' && (anomalyCounts.retryCount > 0 || anomalyCounts.errorCount > 0)
                  ? html`
                      <div class="station-combo-badges">
                        ${anomalyCounts.retryCount > 0
                          ? html`<span class="combo-pill pill-retry">⚡ ${anomalyCounts.retryCount}x RETRY</span>`
                          : nothing}
                        ${anomalyCounts.errorCount > 0
                          ? html`<span class="combo-pill pill-error">⚠ ${anomalyCounts.errorCount}x ERROR</span>`
                          : nothing}
                      </div>
                    `
                  : nothing}

                ${st.code === 'station_06_reasoning' && anomalyCounts.stuckCount > 0
                  ? html`
                      <div class="station-combo-badges">
                        <span class="combo-pill pill-stuck">🔥 LOOP STUCK</span>
                      </div>
                    `
                  : nothing}

                ${isObserved && backendSymbol
                  ? html`
                      <button
                        class="station-xray-btn"
                        @click="${() => (this._xrayStation = this._xrayStation === st.code ? null : st.code)}"
                        aria-label="Inspect X-Ray for ${st.name}"
                        aria-expanded="${this._xrayStation === st.code}"
                      >
                        ${this._xrayStation === st.code ? 'Close X-Ray' : 'Inspect X-Ray'}
                      </button>
                    `
                  : nothing}

                ${this._xrayStation === st.code && isObserved && backendSymbol
                  ? html`
                      <div style="margin-top: 8px;">
                        <arc-node-card
                          label="${st.name}"
                          station="${st.code}"
                          status="${st.status}"
                          .symbol=${backendSymbol}
                        ></arc-node-card>
                      </div>
                    `
                  : nothing}
              </div>
            `;
          })}
        </div>
      </div>
    `;
  }
}
