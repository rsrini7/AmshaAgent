import { LitElement, css, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';

interface CronJob {
  id: string;
  sessionId: string;
  userId: string;
  chatId: string;
  channelType: string;
  channelPlatformId: string;
  cronExpression: string;
  timezone: string;
  runOnce: boolean;
  goal: string;
  nextFireTime: string;
  lastFireTime: string;
  status: string;
  executionRole: string;
  errorReason: string;
  lastDeliveryId: string;
  createdAt: string;
  updatedAt: string;
}

interface CronListResponse {
  crons: CronJob[];
  total: number;
  page: number;
  size: number;
}

@customElement('arc-scheduler-panel')
export class ArcSchedulerPanel extends LitElement {
  @state() private crons: CronJob[] = [];
  @state() private total = 0;
  @state() private page = 0;
  @state() private loading = false;
  @state() private statusFilter = '';
  @state() private selectedCron: CronJob | null = null;

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
      display: flex; gap: var(--space-2); align-items: center;
    }
    select, button {
      padding: 8px 12px; border-radius: 6px;
      border: 1px solid var(--border); background: var(--bg-surface);
      color: var(--text-primary); font-size: 13px; cursor: pointer;
    }
    button:hover { border-color: var(--accent); }
    button.danger { color: var(--error, #ef4444); border-color: var(--error, #ef4444); }
    button.danger:hover { background: var(--error, #ef4444); color: white; }
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
    .status {
      display: inline-block; padding: 2px 8px;
      border-radius: 12px; font-size: 11px; font-weight: 600;
    }
    .status-ACTIVE { background: #065f46; color: #6ee7b7; }
    .status-RUNNING { background: #1e3a5f; color: #60a5fa; }
    .status-AWAITING_APPROVAL { background: #713f12; color: #fbbf24; }
    .status-COMPLETED { background: #374151; color: #9ca3af; }
    .status-FAILED { background: #7f1d1d; color: #fca5a5; }
    .status-SKIPPED { background: #4c1d95; color: #c4b5fd; }
    .goal { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
      width: 480px; max-width: 90vw;
      background: var(--bg-surface); border-left: 1px solid var(--border);
      z-index: 201; overflow-y: auto;
      padding: var(--space-6);
    }
    .drawer h3 { margin: 0 0 var(--space-4); }
    .drawer-field { margin-bottom: var(--space-3); }
    .drawer-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }
    .drawer-value { font-size: 14px; margin-top: 2px; word-break: break-all; }
    .drawer-actions { display: flex; gap: var(--space-2); margin-top: var(--space-4); }
    .error-text { color: var(--error, #ef4444); font-size: 13px; }
  `;

  connectedCallback() {
    super.connectedCallback();
    this.loadCrons();
  }

  private async loadCrons() {
    this.loading = true;
    try {
      const params = new URLSearchParams({ page: String(this.page), size: '50' });
      if (this.statusFilter) params.set('status', this.statusFilter);
      const r = await fetch(`/api/v1/cron?${params}`, {
        headers: { 'Content-Type': 'application/json' }
      });
      if (!r.ok) throw new Error(`Failed to load crons: ${r.status}`);
      const data: CronListResponse = await r.json();
      this.crons = data.crons;
      this.total = data.total;
    } catch (e) {
      console.error('Failed to load crons:', e);
    } finally {
      this.loading = false;
    }
  }

  private async deleteCron(id: string) {
    if (!confirm('Delete this scheduled job?')) return;
    try {
      const r = await fetch(`/api/v1/cron/${id}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' }
      });
      if (!r.ok) throw new Error('Delete failed');
      this.selectedCron = null;
      this.loadCrons();
    } catch (e) {
      console.error('Delete failed:', e);
    }
  }

  private async triggerCron(id: string) {
    try {
      const r = await fetch(`/api/v1/cron/${id}/trigger`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      });
      if (!r.ok) throw new Error('Trigger failed');
      this.loadCrons();
    } catch (e) {
      console.error('Trigger failed:', e);
    }
  }

  private async pauseCron(id: string) {
    try {
      const r = await fetch(`/api/v1/cron/${id}/pause`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      });
      if (!r.ok) throw new Error('Pause failed');
      this.loadCrons();
    } catch (e) {
      console.error('Pause failed:', e);
    }
  }

  private async resumeCron(id: string) {
    try {
      const r = await fetch(`/api/v1/cron/${id}/resume`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      });
      if (!r.ok) throw new Error('Resume failed');
      this.loadCrons();
    } catch (e) {
      console.error('Resume failed:', e);
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

  private renderStatus(status: string) {
    return html`<span class="status status-${status}">${status}</span>`;
  }

  private renderDrawer() {
    const cron = this.selectedCron;
    if (!cron) return nothing;

    return html`
      <div class="drawer-overlay" @click=${() => this.selectedCron = null}></div>
      <div class="drawer">
        <h3>Scheduled Job Details</h3>
        <div class="drawer-field">
          <div class="drawer-label">ID</div>
          <div class="drawer-value">${cron.id}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Goal</div>
          <div class="drawer-value">${cron.goal}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Status</div>
          <div class="drawer-value">${this.renderStatus(cron.status)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Schedule</div>
          <div class="drawer-value">${cron.runOnce ? 'One-shot' : cron.cronExpression} (${cron.timezone})</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Next Fire</div>
          <div class="drawer-value">${this.formatDate(cron.nextFireTime)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Last Fire</div>
          <div class="drawer-value">${this.formatDate(cron.lastFireTime)}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Channel</div>
          <div class="drawer-value">${cron.channelType || '—'} / ${cron.channelPlatformId || '—'}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Session</div>
          <div class="drawer-value">${cron.sessionId}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Execution Role</div>
          <div class="drawer-value">${cron.executionRole || '—'}</div>
        </div>
        <div class="drawer-field">
          <div class="drawer-label">Created</div>
          <div class="drawer-value">${this.formatDate(cron.createdAt)}</div>
        </div>
        ${cron.errorReason ? html`
          <div class="drawer-field">
            <div class="drawer-label">Error</div>
            <div class="drawer-value error-text">${cron.errorReason}</div>
          </div>
        ` : nothing}
        <div class="drawer-actions">
          ${cron.status === 'ACTIVE' || cron.status === 'RUNNING' ? html`
            <button @click=${() => this.triggerCron(cron.id)}>Trigger Now</button>
            <button @click=${() => this.pauseCron(cron.id)}>Pause</button>
          ` : nothing}
          ${cron.status === 'COMPLETED' || cron.status === 'FAILED' ? html`
            <button @click=${() => this.resumeCron(cron.id)}>Resume</button>
          ` : nothing}
          <button class="danger" @click=${() => this.deleteCron(cron.id)}>Delete</button>
          <button @click=${() => this.selectedCron = null}>Close</button>
        </div>
      </div>
    `;
  }

  render() {
    return html`
      <div class="header">
        <h2>Scheduler</h2>
        <div class="filters">
          <select @change=${(e: Event) => {
            this.statusFilter = (e.target as HTMLSelectElement).value;
            this.page = 0;
            this.loadCrons();
          }}>
            <option value="">All Statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="RUNNING">Running</option>
            <option value="AWAITING_APPROVAL">Awaiting Approval</option>
            <option value="COMPLETED">Completed</option>
            <option value="FAILED">Failed</option>
          </select>
          <button @click=${() => this.loadCrons()}>Refresh</button>
        </div>
      </div>

      ${this.crons.length === 0 ? html`
        <div class="empty">
          ${this.loading ? 'Loading...' : 'No scheduled jobs found.'}
        </div>
      ` : html`
        <table>
          <thead>
            <tr>
              <th>Goal</th>
              <th>Status</th>
              <th>Next Fire</th>
              <th>Last Fire</th>
              <th>Channel</th>
              <th>Schedule</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            ${this.crons.map(cron => html`
              <tr @click=${() => this.selectedCron = cron} style="cursor:pointer">
                <td class="goal" title="${cron.goal}">${cron.goal}</td>
                <td>${this.renderStatus(cron.status)}</td>
                <td>${this.formatDate(cron.nextFireTime)}</td>
                <td>${this.formatDate(cron.lastFireTime)}</td>
                <td>${cron.channelType || '—'}</td>
                <td>${cron.runOnce ? 'One-shot' : cron.cronExpression}</td>
                <td class="actions" @click=${(e: Event) => e.stopPropagation()}>
                  ${cron.status === 'ACTIVE' ? html`
                    <button @click=${() => this.triggerCron(cron.id)} title="Trigger now">▶</button>
                    <button @click=${() => this.pauseCron(cron.id)} title="Pause">⏸</button>
                  ` : nothing}
                  ${cron.status === 'COMPLETED' || cron.status === 'FAILED' ? html`
                    <button @click=${() => this.resumeCron(cron.id)} title="Resume">▶</button>
                  ` : nothing}
                  <button class="danger" @click=${() => this.deleteCron(cron.id)} title="Delete">✕</button>
                </td>
              </tr>
            `)}
          </tbody>
        </table>
        <div class="pagination">
          <span>Showing ${this.crons.length} of ${this.total} jobs</span>
          <div>
            <button ?disabled=${this.page === 0} @click=${() => { this.page--; this.loadCrons(); }}>Prev</button>
            <button ?disabled=${(this.page + 1) * 50 >= this.total} @click=${() => { this.page++; this.loadCrons(); }}>Next</button>
          </div>
        </div>
      `}

      ${this.renderDrawer()}
    `;
  }
}
