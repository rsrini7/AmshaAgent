import { LitElement, css, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';

interface AuditEvent {
  id: string;
  sessionId: string;
  userId: string;
  agentRole: string;
  goalHash: string;
  outcome: string;
  eventType: string;
  eventDetail: string;
  inputTokens: number;
  outputTokens: number;
  usdCost: number;
  iterationsUsed: number;
  timestamp: string;
  hmacHash: string;
}

interface AuditListResponse {
  events: AuditEvent[];
  total: number;
  page: number;
  size: number;
}

@customElement('arc-audit-panel')
export class ArcAuditPanel extends LitElement {
  @state() private events: AuditEvent[] = [];
  @state() private total = 0;
  @state() private page = 0;
  @state() private loading = false;
  @state() private selectedEvent: AuditEvent | null = null;

  // Filters
  @state() private filterSessionId = '';
  @state() private filterEventType = '';
  @state() private filterFrom = '';
  @state() private filterTo = '';

  private static readonly EVENT_TYPES = [
    'LOOPSTART', 'LOOPFINISH', 'LOOPTIMEOUT', 'LOOPERROR', 'LOOPMAXITERATIONS',
    'TOOLINVOKED', 'TOOLAPPROVED', 'TOOLDENIED',
    'CRONCREATED', 'CRONFIRED', 'CRONFAILED', 'CRONDELETED',
    'MEMORYINGESTED', 'HMACCHAINBREAK', 'GENESIS'
  ];

  static styles = css`
    :host {
      display: flex; flex-direction: column;
      height: 100%; padding: var(--space-6);
      box-sizing: border-box; gap: var(--space-4);
    }
    .header {
      display: flex; justify-content: space-between; align-items: center;
      flex-wrap: wrap; gap: var(--space-3);
    }
    h2 { margin: 0; font-size: 1.25rem; }
    .filters {
      display: flex; gap: var(--space-2); align-items: center; flex-wrap: wrap;
    }
    input, select, button {
      padding: 8px 12px; border-radius: 6px;
      border: 1px solid var(--border); background: var(--bg-surface);
      color: var(--text-primary); font-size: 13px; cursor: pointer;
    }
    input { cursor: text; min-width: 140px; }
    button:hover { border-color: var(--accent); }
    table {
      width: 100%; border-collapse: collapse;
      font-size: 13px;
    }
    th {
      text-align: left; padding: 10px 12px;
      border-bottom: 2px solid var(--border);
      color: var(--text-muted); font-weight: 600;
      font-size: 11px; text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    td {
      padding: 10px 12px; border-bottom: 1px solid var(--border);
      vertical-align: middle;
    }
    tr:hover td { background: var(--bg-hover, rgba(255,255,255,0.02)); }
    .event-type {
      display: inline-block; padding: 2px 8px;
      border-radius: 12px; font-size: 11px; font-weight: 600;
      background: #1e3a5f; color: #60a5fa;
    }
    .event-type-TOOL { background: #065f46; color: #6ee7b7; }
    .event-type-CRON { background: #713f12; color: #fbbf24; }
    .event-type-ERROR { background: #7f1d1d; color: #fca5a5; }
    .event-type-MEMORY { background: #4c1d95; color: #c4b5fd; }
    .event-type-SECURITY { background: #7f1d1d; color: #fca5a5; }
    .outcome {
      display: inline-block; padding: 2px 8px;
      border-radius: 12px; font-size: 11px; font-weight: 600;
    }
    .outcome-FINISH { background: #065f46; color: #6ee7b7; }
    .outcome-ERROR { background: #7f1d1d; color: #fca5a5; }
    .outcome-AWAITING_APPROVAL { background: #713f12; color: #fbbf24; }
    .outcome-BUDGET_EXCEEDED { background: #7f1d1d; color: #fca5a5; }
    .detail { max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .actions { display: flex; gap: 4px; }
    .actions button { padding: 4px 8px; font-size: 11px; }
    .empty {
      text-align: center; padding: var(--space-8);
      color: var(--text-muted);
    }
    .pagination {
      display: flex; justify-content: space-between; align-items: center;
      padding: var(--space-3) 0; color: var(--text-muted); font-size: 13px;
    }
    .drawer-overlay {
      position: fixed; inset: 0; background: rgba(0,0,0,0.5);
      z-index: 200;
    }
    .drawer {
      position: fixed; right: 0; top: 0; bottom: 0;
      width: 520px; max-width: 90vw;
      background: var(--bg-surface); border-left: 1px solid var(--border);
      z-index: 201; overflow-y: auto;
      padding: var(--space-6);
    }
    .drawer h3 { margin: 0 0 var(--space-4); }
    .drawer-field { margin-bottom: var(--space-3); }
    .drawer-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }
    .drawer-value { font-size: 14px; margin-top: 2px; word-break: break-all; }
    .drawer-json {
      background: var(--bg-code, #1a1a2e);
      border: 1px solid var(--border);
      border-radius: 6px; padding: var(--space-3);
      font-family: monospace; font-size: 12px;
      overflow-x: auto; white-space: pre-wrap;
      max-height: 300px; overflow-y: auto;
    }
    .drawer-actions { display: flex; gap: var(--space-2); margin-top: var(--space-4); }
    .hmac-valid { color: #6ee7b7; }
    .hmac-chain { font-family: monospace; font-size: 11px; color: var(--text-muted); word-break: break-all; }
    .cost { font-family: monospace; }
  `;

  connectedCallback() {
    super.connectedCallback();
    this.loadEvents();
  }

  private async loadEvents() {
    this.loading = true;
    try {
      const params = new URLSearchParams({ page: String(this.page), size: '50' });
      if (this.filterSessionId) params.set('sessionId', this.filterSessionId);
      if (this.filterEventType) params.set('eventType', this.filterEventType);
      if (this.filterFrom) params.set('from', this.filterFrom);
      if (this.filterTo) params.set('to', this.filterTo);

      const r = await fetch(`/api/v1/audit?${params}`, {
        headers: { 'Content-Type': 'application/json' }
      });
      if (!r.ok) throw new Error(`Failed to load audit events: ${r.status}`);
      const data: AuditListResponse = await r.json();
      this.events = data.events;
      this.total = data.total;
    } catch (e) {
      console.error('Failed to load audit events:', e);
    } finally {
      this.loading = false;
    }
  }

  private formatDate(iso: string | null): string {
    if (!iso) return '—';
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return iso;
    }
  }

  private formatCost(cost: number): string {
    if (cost === 0) return '—';
    return '$' + cost.toFixed(4);
  }

  private eventTypeCategory(type: string): string {
    if (type.startsWith('TOOL')) return 'TOOL';
    if (type.startsWith('CRON')) return 'CRON';
    if (type.startsWith('LOOP') && (type.includes('ERROR') || type.includes('TIMEOUT'))) return 'ERROR';
    if (type.startsWith('MEMORY')) return 'MEMORY';
    if (type.includes('HMAC') || type === 'GENESIS') return 'SECURITY';
    return '';
  }

  private renderEventType(type: string) {
    const cat = this.eventTypeCategory(type);
    return html`<span class="event-type event-type-${cat}">${type}</span>`;
  }

  private renderOutcome(outcome: string) {
    return html`<span class="outcome outcome-${outcome}">${outcome}</span>`;
  }

  private renderPayloadJson(detail: string | null) {
    if (!detail) return nothing;
    try {
      const parsed = JSON.parse(detail);
      return html`
        <div class="drawer-field">
          <div class="drawer-label">Structured Payload</div>
          <pre class="drawer-json">${JSON.stringify(parsed, null, 2)}</pre>
        </div>
      `;
    } catch {
      return nothing;
    }
  }

  private renderDrawer() {
    const event = this.selectedEvent;
    if (!event) return nothing;

    return html`
      <div class="drawer-overlay" @click=${() => this.selectedEvent = null}></div>
      <div class="drawer">
        <h3>Audit Event Details</h3>
        <div class="drawer-field">
          <div class="drawer-label">Event ID</div>
          <div class="drawer-value">${event.id}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Timestamp</div>
          <div class="drawer-value">${this.formatDate(event.timestamp)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Event Type</div>
          <div class="drawer-value">${this.renderEventType(event.eventType)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Outcome</div>
          <div class="drawer-value">${this.renderOutcome(event.outcome)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Session ID</div>
          <div class="drawer-value">${event.sessionId}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Agent Role</div>
          <div class="drawer-value">${event.agentRole}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">User ID</div>
          <div class="drawer-value">${event.userId || '—'}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Event Detail</div>
          <div class="drawer-value">${event.eventDetail || '—'}</div>
        </div>
        ${this.renderPayloadJson(event.eventDetail)}
        <div class="drawer-field">
          <div class="drawer-label">Tokens (In / Out)</div>
          <div class="drawer-value">${event.inputTokens.toLocaleString()} / ${event.outputTokens.toLocaleString()}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Cost</div>
          <div class="drawer-value cost">${this.formatCost(event.usdCost)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Iterations</div>
          <div class="drawer-value">${event.iterationsUsed}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Goal Hash</div>
          <div class="drawer-value hmac-chain">${event.goalHash}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">HMAC Chain</div>
          <div class="drawer-value hmac-chain">${event.hmacHash}</div>
        </div>
        <h3 style="margin-top: var(--space-4)">Related</h3>
        <div style="display: flex; gap: var(--space-2); flex-wrap: wrap">
          <a href="#/chat" @click=${() => { this.selectedEvent = null; }}
            style="color: var(--accent); text-decoration: underline; cursor: pointer; font-size: 13px">Chat</a>
          <a href="#/hitl" @click=${() => { this.selectedEvent = null; }}
            style="color: var(--accent); text-decoration: underline; cursor: pointer; font-size: 13px">Approvals</a>
          <a href="#/scheduler" @click=${() => { this.selectedEvent = null; }}
            style="color: var(--accent); text-decoration: underline; cursor: pointer; font-size: 13px">Scheduler</a>
        </div>
        <div class="drawer-actions">
          <button @click=${() => {
            this.filterSessionId = event.sessionId;
            this.selectedEvent = null;
            this.page = 0;
            this.loadEvents();
          }}>Filter by Session</button>
          <button @click=${() => this.selectedEvent = null}>Close</button>
        </div>
      </div>
    `;
  }

  render() {
    return html`
      <div class="header">
        <h2>Audit Log</h2>
        <div class="filters">
          <input type="text" placeholder="Session ID"
            .value=${this.filterSessionId}
            @change=${(e: Event) => { this.filterSessionId = (e.target as HTMLInputElement).value; this.page = 0; this.loadEvents(); }}>
          <select @change=${(e: Event) => {
            this.filterEventType = (e.target as HTMLSelectElement).value;
            this.page = 0;
            this.loadEvents();
          }}>
            <option value="">All Event Types</option>
            ${ArcAuditPanel.EVENT_TYPES.map(t => html`<option value=${t}>${t}</option>`)}
          </select>
          <input type="datetime-local" placeholder="From"
            @change=${(e: Event) => {
              const v = (e.target as HTMLInputElement).value;
              this.filterFrom = v ? new Date(v).toISOString() : '';
              this.page = 0;
              this.loadEvents();
            }}>
          <input type="datetime-local" placeholder="To"
            @change=${(e: Event) => {
              const v = (e.target as HTMLInputElement).value;
              this.filterTo = v ? new Date(v).toISOString() : '';
              this.page = 0;
              this.loadEvents();
            }}>
          <button @click=${() => { this.filterSessionId = ''; this.filterEventType = ''; this.filterFrom = ''; this.filterTo = ''; this.page = 0; this.loadEvents(); }}>Clear</button>
          <button @click=${() => this.loadEvents()}>Refresh</button>
        </div>
      </div>

      ${this.events.length === 0 ? html`
        <div class="empty">
          ${this.loading ? 'Loading...' : 'No audit events found.'}
        </div>
      ` : html`
        <table>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Event Type</th>
              <th>Outcome</th>
              <th>Session</th>
              <th>Role</th>
              <th>Tokens</th>
              <th>Cost</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            ${this.events.map(event => html`
              <tr @click=${() => this.selectedEvent = event} style="cursor:pointer">
                <td>${this.formatDate(event.timestamp)}</td>
                <td>${this.renderEventType(event.eventType)}</td>
                <td>${this.renderOutcome(event.outcome)}</td>
                <td title="${event.sessionId}">${event.sessionId.substring(0, 12)}...</td>
                <td>${event.agentRole}</td>
                <td>${(event.inputTokens + event.outputTokens).toLocaleString()}</td>
                <td class="cost">${this.formatCost(event.usdCost)}</td>
                <td class="detail" title="${event.eventDetail || ''}">${event.eventDetail || '—'}</td>
              </tr>
            `)}
          </tbody>
        </table>
        <div class="pagination">
          <span>Showing ${this.events.length} of ${this.total} events</span>
          <div>
            <button ?disabled=${this.page === 0} @click=${() => { this.page--; this.loadEvents(); }}>Prev</button>
            <button ?disabled=${(this.page + 1) * 50 >= this.total} @click=${() => { this.page++; this.loadEvents(); }}>Next</button>
          </div>
        </div>
      `}

      ${this.renderDrawer()}
    `;
  }
}
