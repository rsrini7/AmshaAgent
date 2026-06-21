import { LitElement, css, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';


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

@customElement('arc-audit-panel')
export class ArcAuditPanel extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;


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

  @state() private eventTypes: string[] = [];
  private static readonly EVENT_TYPES_CACHE_KEY = 'amsha_audit_event_types';

  private static readonly EVENT_TYPES_FALLBACK = [
    'LOOP_FINISH', 'LOOP_ERROR', 'LOOP_BUDGET_EXCEEDED', 'LOOP_MAX_ITERATIONS',
    'LOOP_AWAITING_APPROVAL', 'LOOP_AWAITING_RETRY',
    'INVALID_ROLE_FALLBACK', 'LEGACY_CHANNEL_FALLBACK',
    'TOOL_INVOKED', 'TOOL_EXECUTED', 'TOOL_BLOCKED', 'TOOL_FAILED',
    'APPROVAL_GRANTED', 'APPROVAL_DENIED',
    'PROMPT_INJECTION_BLOCKED', 'PII_SCAN_REDACTED', 'REFLEX_COMMAND',
    'VAULT_ACCESS', 'VAULT_STORE', 'VAULT_RETRIEVE', 'VAULT_RETRIEVE_MISS',
    'CHECKPOINT_CREATED', 'CHECKPOINT_COMPLETED', 'CHECKPOINT_FAILED', 'CHECKPOINT_ORPHANED',
    'SECURITY', 'CONTEXT_COMPACTED', 'BUDGET_EXCEEDED',
    'CRONCREATED', 'CRONFIRED', 'CRONFAILED', 'CRONDELETED',
    'CRON_PAUSED', 'CRON_RESUMED', 'CRON_FORCE_TRIGGERED',
    'SKILL_LOADED', 'SKILL_REFRESHED', 'SKILL_CREATED', 'SKILL_DELETED',
    'MCP_TOOL_INVOKED', 'MCP_SERVER_CONNECTED', 'MCP_SERVER_DISCONNECTED',
    'STRUCTURED_OUTPUT_RETRY', 'DELEGATE_APPROVAL', 'DELEGATION_DISPATCHED',
    'UNAUTHORIZED_ACCESS', 'AUTH_REJECTED',
    'SESSION_CREATED', 'SESSION_TERMINATED',
    'SETTINGS_CHANGED', 'SETTINGS_RESET', 'ONBOARDING_COMPLETED',
    'WS_TOKEN_ISSUED', 'MEMORY_INGESTED'
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
    .table-wrap {
      flex: 1; overflow-y: auto; overflow-x: auto;
      min-height: 0; max-height: calc(100vh - 200px);
      scrollbar-width: thin;
      scrollbar-color: var(--border) transparent;
    }
    .table-wrap::-webkit-scrollbar { width: 6px; height: 6px; }
    .table-wrap::-webkit-scrollbar-track { background: transparent; }
    .table-wrap::-webkit-scrollbar-thumb { background: var(--border); border-radius: 999px; }
    .table-wrap::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }
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
    .event-type-SESSION { background: #1e3a5f; color: #93c5fd; }
    .event-type-SETTINGS { background: #365314; color: #bef264; }
    .event-type-AUTH { background: #7f1d1d; color: #fca5a5; }
    .event-type-CHECKPOINT { background: #4c1d95; color: #c4b5fd; }
    .event-type-DELEGATION { background: #713f12; color: #fbbf24; }
    .event-type-MCP { background: #065f46; color: #6ee7b7; }
    .event-type-APPROVAL { background: #713f12; color: #fbbf24; }
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
    .drawer::-webkit-scrollbar { width: 6px; }
    .drawer::-webkit-scrollbar-track { background: transparent; }
    .drawer::-webkit-scrollbar-thumb { background: var(--border); border-radius: 999px; }
    .drawer h3 { margin: 0 0 var(--space-4); }
    .drawer-field { margin-bottom: var(--space-3); }
    .drawer-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }
    .drawer-value { font-size: 14px; margin-top: 2px; word-break: break-all; white-space: pre-wrap; }
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

  async connectedCallback() {
    super.connectedCallback();
    if (this._appContext?.apiKey) {
      this.loadEventTypes();
      this.loadEvents();
    }
    this._onKeyDown = this._onKeyDown.bind(this);
    document.addEventListener('keydown', this._onKeyDown);
  }

  updated(changedProperties: Map<string, any>) {
    if (changedProperties.has('_appContext') && this._appContext?.apiKey && !this.loading && this.events.length === 0) {
      this.loadEventTypes();
      this.loadEvents();
    }
  }

  private get _client(): ArcClient {
    return new ArcClient('/api/v1', this._appContext?.apiKey || '');
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('keydown', this._onKeyDown);
  }

  private _onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape' && this.selectedEvent) {
      this.selectedEvent = null;
    }
  }

  private async loadEventTypes() {
    // Check sessionStorage first
    const cached = sessionStorage.getItem(ArcAuditPanel.EVENT_TYPES_CACHE_KEY);
    if (cached) {
      try {
        this.eventTypes = JSON.parse(cached);
        return;
      } catch { /* ignore corrupted cache */ }
    }

    try {
      const data = await this._client.getAuditEventTypes();
      this.eventTypes = data.eventTypes;
      sessionStorage.setItem(ArcAuditPanel.EVENT_TYPES_CACHE_KEY, JSON.stringify(this.eventTypes));
    } catch {
      // Fallback to static list
      this.eventTypes = ArcAuditPanel.EVENT_TYPES_FALLBACK;
    }
  }

  private async loadEvents() {
    this.loading = true;
    try {
      const data = await this._client.getAuditEvents({
        page: this.page,
        size: 50,
        sessionId: this.filterSessionId || undefined,
        eventType: this.filterEventType || undefined,
        from: this.filterFrom || undefined,
        to: this.filterTo || undefined
      });
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
    if (type.startsWith('SESSION')) return 'SESSION';
    if (type.startsWith('SETTINGS') || type.startsWith('ONBOARDING')) return 'SETTINGS';
    if (type.startsWith('AUTH') || type === 'UNAUTHORIZED_ACCESS' || type === 'WS_TOKEN_ISSUED') return 'AUTH';
    if (type.startsWith('CHECKPOINT')) return 'CHECKPOINT';
    if (type.startsWith('DELEGAT')) return 'DELEGATION';
    if (type.startsWith('MCP')) return 'MCP';
    if (type.includes('APPROVAL')) return 'APPROVAL';
    if (type.includes('HMAC') || type === 'GENESIS' || type === 'SECURITY') return 'SECURITY';
    if (type.startsWith('SKILL')) return 'CRON';
    if (type.startsWith('PROMPT_INJECTION') || type.startsWith('PII')) return 'SECURITY';
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
            @input=${(e: Event) => { this.filterSessionId = (e.target as HTMLInputElement).value; this.page = 0; this.loadEvents(); }}>
          <select @change=${(e: Event) => {
            this.filterEventType = (e.target as HTMLSelectElement).value;
            this.page = 0;
            this.loadEvents();
          }}>
            <option value="">All Event Types</option>
            ${this.eventTypes.map(t => html`<option value=${t}>${t}</option>`)}
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
        <div class="table-wrap">
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
        </div>
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
