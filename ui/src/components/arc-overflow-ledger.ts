import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { AgentTraceEvent } from '../types/trace';

export interface AnomalyCounts {
  retryCount: number;
  errorCount: number;
  stuckCount: number;
  totalAnomalies: number;
}

export function isAnomalyEvent(e: AgentTraceEvent): boolean {
  if (e.stage === 'tool_error' || e.stage === 'retry' || e.stage === 'loop_stuck') {
    return true;
  }
  const normStatus = (e.status || '').toUpperCase();
  if (normStatus === 'LOOP_STUCK' || normStatus === 'ALERT' || normStatus === 'RETRY') {
    return true;
  }
  if (e.station === 'station_07_tool_execution' && (normStatus === 'ERROR' || normStatus === 'FAILURE')) {
    return true;
  }
  return false;
}

export function extractAnomalies(events: AgentTraceEvent[]): AgentTraceEvent[] {
  return events.filter(isAnomalyEvent);
}

export function computeAnomalyCounts(events: AgentTraceEvent[]): AnomalyCounts {
  let retryCount = 0;
  let errorCount = 0;
  let stuckCount = 0;

  for (const e of events) {
    const normStatus = (e.status || '').toUpperCase();
    if (e.stage === 'retry' || normStatus === 'RETRY') {
      retryCount++;
    } else if (e.stage === 'loop_stuck' || normStatus === 'LOOP_STUCK' || normStatus === 'ALERT') {
      stuckCount++;
    } else if (e.stage === 'tool_error' || normStatus === 'ERROR' || normStatus === 'FAILURE') {
      errorCount++;
    }
  }

  return {
    retryCount,
    errorCount,
    stuckCount,
    totalAnomalies: retryCount + errorCount + stuckCount,
  };
}

@customElement('arc-overflow-ledger')
export class ArcOverflowLedger extends LitElement {
  @property({ type: Array }) events: AgentTraceEvent[] = [];
  @property({ type: Boolean }) open = false;

  @state() private _isOpen = false;

  connectedCallback() {
    super.connectedCallback();
    this._isOpen = this.open;
  }

  willUpdate(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('open')) {
      this._isOpen = this.open;
    }
  }

  public toggleOpen(): void {
    this._isOpen = !this._isOpen;
  }

  static styles = css`
    :host {
      display: block;
      font-family: inherit;
    }

    .ledger-container {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 12px;
      overflow: hidden;
      transition: border-color 0.2s ease;
    }

    .ledger-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 16px;
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 70%, transparent);
      border-bottom: 1px solid var(--border, #242938);
      cursor: pointer;
      user-select: none;
    }

    .ledger-header:hover {
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 90%, transparent);
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .ledger-title {
      font-size: 13px;
      font-weight: 700;
      color: var(--text-primary, #ffffff);
    }

    .combo-badges-group {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }

    .combo-badge {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      padding: 2px 7px;
      border-radius: 999px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .badge-retry {
      background: rgba(245, 158, 11, 0.2);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }

    .badge-error {
      background: rgba(239, 68, 68, 0.2);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }

    .badge-stuck {
      background: rgba(239, 68, 68, 0.3);
      color: #ff6b6b;
      border: 1px solid #ef4444;
      animation: alert-pulse 2s infinite ease-in-out;
    }

    .badge-clean {
      background: color-mix(in srgb, var(--accent-secondary, #00d4aa) 15%, transparent);
      color: var(--accent-secondary, #00d4aa);
      border: 1px solid color-mix(in srgb, var(--accent-secondary, #00d4aa) 30%, transparent);
    }

    .toggle-btn {
      background: transparent;
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      font-size: 11px;
      padding: 3px 8px;
      border-radius: 4px;
      cursor: pointer;
    }

    .toggle-btn:hover {
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
    }

    .ledger-body {
      padding: 12px 16px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .clean-state {
      font-size: 12px;
      color: var(--accent-secondary, #00d4aa);
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 0;
    }

    .ledger-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 11px;
    }

    .ledger-table th {
      text-align: left;
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted, #8b949e);
      padding: 6px 8px;
      border-bottom: 1px solid var(--border, #242938);
    }

    .ledger-table td {
      padding: 8px;
      border-bottom: 1px solid color-mix(in srgb, var(--border, #242938) 50%, transparent);
      color: var(--text-primary, #ffffff);
      vertical-align: top;
    }

    .event-time {
      font-family: monospace;
      color: var(--text-muted, #8b949e);
      white-space: nowrap;
    }

    .event-station {
      font-family: monospace;
      color: var(--accent, #6c63ff);
    }

    .event-stage {
      font-weight: 600;
    }

    .event-detail {
      color: var(--text-muted, #8b949e);
      word-break: break-all;
    }

    @keyframes alert-pulse {
      0%, 100% {
        opacity: 1;
      }
      50% {
        opacity: 0.7;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .badge-stuck {
        animation: none !important;
      }
    }
  `;

  render() {
    const counts = computeAnomalyCounts(this.events);
    const anomalies = extractAnomalies(this.events);

    return html`
      <div class="ledger-container" role="region" aria-label="Anomaly and Overflow Ledger">
        <div class="ledger-header" @click="${this.toggleOpen}" role="button" aria-expanded="${this._isOpen}">
          <div class="header-left">
            <span class="ledger-title">Overflow & Failure Ledger</span>
            <div class="combo-badges-group">
              ${counts.totalAnomalies === 0
                ? html`<span class="combo-badge badge-clean">✓ Clean Run</span>`
                : nothing}

              ${counts.retryCount > 0
                ? html`<span class="combo-badge badge-retry">⚡ ${counts.retryCount}x RETRY</span>`
                : nothing}

              ${counts.errorCount > 0
                ? html`<span class="combo-badge badge-error">⚠ ${counts.errorCount}x TOOL ERROR</span>`
                : nothing}

              ${counts.stuckCount > 0
                ? html`<span class="combo-badge badge-stuck">🔥 ${counts.stuckCount}x LOOP STUCK</span>`
                : nothing}
            </div>
          </div>

          <button class="toggle-btn" aria-label="Toggle overflow ledger details">
            ${this._isOpen ? 'Collapse' : `Expand (${counts.totalAnomalies})`}
          </button>
        </div>

        ${this._isOpen
          ? html`
              <div class="ledger-body">
                ${anomalies.length === 0
                  ? html`
                      <div class="clean-state">
                        ✓ No tool errors, retries, or stuck loops observed in this run.
                      </div>
                    `
                  : html`
                      <table class="ledger-table" role="table" aria-label="Overflow Events Table">
                        <thead>
                          <tr>
                            <th>Time</th>
                            <th>Station</th>
                            <th>Stage</th>
                            <th>Status</th>
                            <th>Details</th>
                          </tr>
                        </thead>
                        <tbody>
                          ${anomalies.map((ev) => {
                            const timeStr = ev.timestamp ? ev.timestamp.slice(11, 19) : '--:--:--';
                            const stationStr = ev.station ? ev.station.replace('station_', 'S') : 'S??';
                            return html`
                              <tr class="ledger-row" data-stage="${ev.stage}">
                                <td class="event-time">${timeStr}</td>
                                <td class="event-station">${stationStr}</td>
                                <td class="event-stage">${ev.stage.toUpperCase()}</td>
                                <td>
                                  <span class="combo-badge badge-${ev.stage === 'retry' ? 'retry' : ev.stage === 'loop_stuck' ? 'stuck' : 'error'}">
                                    ${ev.status}
                                  </span>
                                </td>
                                <td class="event-detail">${ev.detail || 'No detail provided'}</td>
                              </tr>
                            `;
                          })}
                        </tbody>
                      </table>
                    `}
              </div>
            `
          : nothing}
      </div>
    `;
  }
}
