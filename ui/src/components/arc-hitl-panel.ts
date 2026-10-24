import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { PendingApprovalDto } from '../api/types';
import { ArcClient } from '../api/client';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';


@customElement('arc-hitl-panel')
export class ArcHitlPanel extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @property({ type: Array }) approvals: PendingApprovalDto[] = [];
  @property({ type: Boolean, reflect: true }) compact = false;
  @property({ type: Boolean }) channelCommandMode = false;
  @property({ type: Boolean }) channelCommandPending = false;
  @property({ type: String }) channelCommandError = '';
  
  @state() private _processing = new Set<string>();
  @state() private _rejectReason: Record<string, string> = {};
  @state() private _rejecting = new Set<string>();
  @state() private _error: Record<string, string> = {};
  @state() private _statusUnknown = new Set<string>();
  @state() private _resolvedElsewhere = new Set<string>();
  @state() private _statusConflict = new Set<string>();
  @state() private _statusNotice: Record<string, string> = {};

  private _pollTimer?: number;
  private readonly _idempotencyKeys = new Map<string, string>();
  private readonly _lastActions = new Map<string, 'approved' | 'rejected'>();

  override connectedCallback() {
    super.connectedCallback();
    this._pollTimer = window.setInterval(() => {
      this.dispatchEvent(new CustomEvent('request-approvals-refresh', { bubbles: true, composed: true }));
    }, 4000);
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._pollTimer) {
      window.clearInterval(this._pollTimer);
      this._pollTimer = undefined;
    }
  }

  private _manualRefresh() {
    this.dispatchEvent(new CustomEvent('request-approvals-refresh', { bubbles: true, composed: true }));
  }

  static styles = css`
    :host { display: block; padding: var(--space-8); box-sizing: border-box; background: var(--bg-base); }
    :host([compact]) { padding: 0; background: transparent; }
    :host([compact]) > div { max-width: 100% !important; margin: 0 !important; }
    :host([compact]) .header-row { display: none; }
    :host([compact]) .card { margin-bottom: 0; padding: var(--space-4); }
    h2 { color: var(--text-primary); margin-top: 0; margin-bottom: var(--space-6); font-size: 20px; font-weight: 700; letter-spacing: -0.02em; }
    .header-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-6); }
    .header-row h2 { margin: 0; }
    .btn-refresh {
      background: var(--bg-surface); border: 1px solid var(--border); color: var(--text-primary);
      border-radius: 8px; padding: 6px 14px; font-size: 13px; font-weight: 600;
      cursor: pointer; transition: var(--transition); display: flex; align-items: center; gap: 6px;
    }
    .btn-refresh:hover { border-color: var(--accent-secondary); color: var(--accent-secondary); }
    .empty { color: var(--text-muted); font-style: italic; font-size: 14px; }
    
    .card {
      background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-xl); 
      padding: var(--space-6); margin-bottom: var(--space-5);
      display: flex; flex-direction: column; gap: var(--space-4);
      transition: var(--transition);
      box-shadow: 0 4px 12px rgba(0,0,0,0.1);
    }
    .card:hover { border-color: rgba(245, 158, 11, 0.3); box-shadow: 0 8px 24px rgba(0,0,0,0.2); }

    .card-header { display: flex; justify-content: space-between; align-items: flex-start; }
    .tool-group { display: flex; align-items: center; gap: 10px; }
    .tool-icon { font-size: 18px; color: #f59e0b; }
    .tool-name { color: var(--text-primary); font-weight: 700; font-size: 16px; letter-spacing: -0.01em; }
    .agent-name { color: var(--text-muted); font-size: 13px; font-weight: 500; }
    .requested-at { color: var(--text-muted); font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; }
    
    .params {
      background: rgba(0,0,0,0.2); border-radius: 12px; padding: 16px;
      font-family: 'Fira Code', monospace; font-size: 13px; color: var(--text-primary);
      margin: 0; white-space: pre-wrap; word-break: break-all;
      border: 1px solid var(--border);
    }
    
    .actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-top: 4px; }
    .btn {
      border: none; border-radius: 10px; color: #0a0b0f;
      cursor: pointer; padding: 10px 20px; font-size: 14px;
      font-weight: 700; transition: var(--transition);
    }
    .btn:disabled { opacity: 0.6; cursor: not-allowed; }
    .btn-approve { background: var(--accent-secondary); }
    .btn-approve:hover:not(:disabled) { brightness: 1.1; transform: translateY(-1px); }
    .btn-reject { background: transparent; border: 1px solid var(--border); color: var(--text-primary); }
    .btn-reject:hover:not(:disabled) { background: rgba(255, 255, 255, 0.05); }
    
    .reason-form { 
       width: 100%; margin-top: 12px; padding: 16px; background: rgba(239, 68, 68, 0.05); 
       border: 1px solid rgba(239, 68, 68, 0.1); border-radius: 12px;
       display: flex; flex-direction: column; gap: 12px;
    }
    .reason-input {
      background: var(--bg-base); border: 1px solid var(--border);
      border-radius: 8px; color: var(--text-primary);
      padding: 12px; font-size: 14px; outline: none; transition: var(--transition);
    }
    .reason-input:focus { border-color: #ef4444; box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.1); }
    .reason-actions { display: flex; gap: 10px; justify-content: flex-end; }
    
    .error-msg { color: #ef4444; font-size: 12px; font-weight: 600; margin-top: 8px; }
    .status-msg { color: var(--text-muted); font-size: 12px; font-weight: 600; margin-top: 8px; }
  `;

  private async _approve(target: PendingApprovalDto | string) {
    const approval = typeof target === 'string'
      ? this.approvals.find(it => it.taskId === target) || { taskId: target, toolName: '', params: {}, requestedAt: '', agentName: '' }
      : target;
    this._lastActions.set(approval.taskId, 'approved');
    await this._runApprovalAction(approval, 'approved');
  }

  private async _runApprovalAction(target: PendingApprovalDto | string, action: 'approved' | 'rejected') {
    const approval = typeof target === 'string'
      ? this.approvals.find(it => it.taskId === target) || { taskId: target, toolName: '', params: {}, requestedAt: '', agentName: '' }
      : target;
    const taskId = approval.taskId;
    const toolName = approval.toolName.trim().startsWith('parallel|') ? 'all' : approval.toolName;
    const requestedAt = approval.requestedAt;

    // Ignore a second click that arrives before Lit has re-rendered the
    // processing state. Concurrent approvals otherwise race the same
    // checkpoint and surface a misleading 409 "Already resolved" error.
    if (this._processing.has(taskId)) return;

    this._processing.add(taskId);
    this._statusUnknown.delete(taskId);
    this._statusConflict.delete(taskId);
    this._statusNotice = { ...this._statusNotice, [taskId]: '' };
    this._error = { ...this._error, [taskId]: '' };
    this.requestUpdate();

    if (this.channelCommandMode) {
      this._completeAction(taskId, action);
      this._processing.delete(taskId);
      this.requestUpdate();
      return;
    }

    const client = new ArcClient('/api/v1', this._appContext?.apiKey || '');
    const idempotencyKey = this._idempotencyKey(taskId, action, toolName, requestedAt);
    try {
      if (action === 'approved') {
        await client.approveHitl(taskId, 'Approved by user', { idempotencyKey, toolName });
      } else {
        await client.rejectHitl(taskId, this._rejectReason[taskId] || '', { idempotencyKey, toolName });
      }
      this._completeAction(taskId, action);
    } catch (error: unknown) {
      if (this._isAlreadyResolved(error)) {
        await this._reconcileResolvedConflict(client, approval);
      } else {
        const reconciled = this._isUncertainFailure(error)
          ? await this._reconcile(client, taskId, action)
          : false;
        if (!reconciled && !this._statusUnknown.has(taskId)) {
          this._error = { ...this._error, [taskId]: this._errorMessage(error, `${action} failed`) };
        }
      }
    } finally {
      this._processing.delete(taskId);
      this.requestUpdate();
    }
  }

  private _toggleReject(taskId: string) {
    if (this._rejecting.has(taskId)) {
      this._rejecting.delete(taskId);
    } else {
      this._rejecting.add(taskId);
    }
    this.requestUpdate();
  }

  private async _submitReject(taskId: string) {
    const approval = this.approvals.find(it => it.taskId === taskId)
      || { taskId, toolName: '', params: {}, requestedAt: '', agentName: '' };
    this._lastActions.set(taskId, 'rejected');
    await this._runApprovalAction(approval, 'rejected');
    if (!this._error[taskId]) {
      this._rejecting.delete(taskId);
    }
    this.requestUpdate();
  }

  private _idempotencyKey(taskId: string, action: 'approved' | 'rejected', toolName?: string, requestedAt?: string): string {
    const key = `${action}:${taskId}:${toolName || 'all'}:${requestedAt || ''}`;
    const existing = this._idempotencyKeys.get(key);
    if (existing) return existing;
    const nonce = Math.random().toString(36).substring(2, 8);
    const generated = `arc-hitl-${action}-${taskId}-${nonce}`;
    this._idempotencyKeys.set(key, generated);
    return generated;
  }

  private _completeAction(taskId: string, action: 'approved' | 'rejected') {
    this._statusUnknown.delete(taskId);
    this._statusConflict.delete(taskId);
    this._statusNotice = { ...this._statusNotice, [taskId]: '' };
    this._error = { ...this._error, [taskId]: '' };
    for (const k of Array.from(this._idempotencyKeys.keys())) {
      if (k.includes(`:${taskId}:`) || k.endsWith(`:${taskId}`)) {
        this._idempotencyKeys.delete(k);
      }
    }
    this.dispatchEvent(new CustomEvent('approval-action', {
      detail: { taskId, action },
      bubbles: true,
      composed: true,
    }));
  }

  private _markResolvedElsewhere(approval: PendingApprovalDto) {
    const taskId = approval.taskId;
    this._statusUnknown.delete(taskId);
    this._statusConflict.delete(taskId);
    this._statusNotice = { ...this._statusNotice, [taskId]: '' };
    this._resolvedElsewhere.add(this._approvalKey(approval));
    this._error = { ...this._error, [taskId]: '' };
    for (const key of Array.from(this._idempotencyKeys.keys())) {
      if (key.includes(`:${taskId}:`) || key.endsWith(`:${taskId}`)) {
        this._idempotencyKeys.delete(key);
      }
    }
    this.dispatchEvent(new CustomEvent('approval-action', {
      detail: { taskId, action: 'resolved' },
      bubbles: true,
      composed: true,
    }));
    this.requestUpdate();
  }

  private async _reconcileResolvedConflict(
    client: ArcClient,
    approval: PendingApprovalDto,
  ): Promise<boolean> {
    const taskId = approval.taskId;
    try {
      let result = await client.getHitl(0, 50);
      let stillPending = result.items.some((item) => this._sameApproval(item, approval));
      for (let page = 1; !stillPending && page < result.totalPages; page++) {
        result = await client.getHitl(page, 50);
        stillPending = result.items.some((item) => this._sameApproval(item, approval));
      }
      if (!stillPending) {
        this._markResolvedElsewhere(approval);
        return true;
      }
      this._statusNotice = {
        ...this._statusNotice,
        [taskId]: 'This approval is still pending. Check its status before retrying.',
      };
    } catch (_) {
      this._statusNotice = {
        ...this._statusNotice,
        [taskId]: 'Could not confirm this approval status. Check again or refresh the list.',
      };
    }
    this._statusUnknown.add(taskId);
    this._statusConflict.add(taskId);
    this.requestUpdate();
    return false;
  }

  private _approvalKey(approval: PendingApprovalDto): string {
    return `${approval.taskId}:${approval.toolName}:${approval.requestedAt}`;
  }

  private _sameApproval(candidate: PendingApprovalDto, approval: PendingApprovalDto): boolean {
    if (candidate.taskId === approval.taskId) return true;
    if (candidate.sessionId !== approval.taskId) return false;
    const pendingTool = candidate.toolName.trim().replace(/^parallel\|/, '');
    const requestedTool = approval.toolName.trim().replace(/^parallel\|/, '');
    return !pendingTool || !requestedTool || pendingTool === requestedTool;
  }

  private async _reconcile(
    client: ArcClient,
    taskId: string,
    action: 'approved' | 'rejected',
  ): Promise<boolean> {
    try {
      const result = await client.getHitl();
      const stillPending = result.items.some((approval) => approval.taskId === taskId);
      if (!stillPending) {
        this._completeAction(taskId, action);
        return true;
      }
      this._statusUnknown.add(taskId);
      this._error = {
        ...this._error,
        [taskId]: 'Approval is still pending; status is unknown. Retry or check status again.',
      };
    } catch (_) {
      this._statusUnknown.add(taskId);
      this._error = {
        ...this._error,
        [taskId]: 'Could not confirm approval status. Retry or check status again.',
      };
    }
    this.requestUpdate();
    return false;
  }

  private _isUncertainFailure(error: unknown): boolean {
    if (!error || typeof error !== 'object' || !('status' in error)) return true;
    const status = (error as { status?: unknown }).status;
    return typeof status !== 'number' || status === 408 || status === 429 || status >= 500;
  }

  private _isAlreadyResolved(error: unknown): boolean {
    return !!error && typeof error === 'object' && 'status' in error &&
      (error as { status?: unknown }).status === 409;
  }

  private _errorMessage(error: unknown, fallback: string): string {
    return error instanceof Error && error.message ? error.message : fallback;
  }

  private async _retry(taskId: string) {
    await this._runApprovalAction(taskId, this._lastActions.get(taskId) || 'approved');
  }

  private async _checkStatus(taskId: string) {
    const action = this._lastActions.get(taskId) || 'approved';
    const approval = this.approvals.find((item) => item.taskId === taskId);
    this._processing.add(taskId);
    this.requestUpdate();
    try {
      const client = new ArcClient('/api/v1', this._appContext?.apiKey || '');
      if (this._statusConflict.has(taskId) && approval) {
        await this._reconcileResolvedConflict(client, approval);
      } else {
        await this._reconcile(client, taskId, action);
      }
    } finally {
      this._processing.delete(taskId);
      this.requestUpdate();
    }
  }

  private _formatTime(isoString: string): string {
    const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
    const diffMs = new Date(isoString).getTime() - Date.now();
    const diffSecs = Math.round(diffMs / 1000);
    const diffMins = Math.round(diffSecs / 60);
    
    if (Math.abs(diffSecs) < 60) return rtf.format(diffSecs, 'second');
    return rtf.format(diffMins, 'minute');
  }

  private _formatParams(rawParams: unknown): string {
    if (!rawParams) {
      return 'No parameters';
    }
    let paramsObj: Record<string, unknown>;
    if (typeof rawParams === 'string') {
      const trimmed = rawParams.trim();
      if (!trimmed || trimmed === '{}') {
        return 'No parameters';
      }
      try {
        const parsed = JSON.parse(trimmed);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          paramsObj = parsed;
        } else if (Array.isArray(parsed)) {
          return JSON.stringify(parsed, null, 2);
        } else {
          return trimmed;
        }
      } catch (_) {
        return trimmed;
      }
    } else if (typeof rawParams === 'object') {
      if (Array.isArray(rawParams)) {
        return JSON.stringify(rawParams, null, 2);
      }
      paramsObj = rawParams as Record<string, unknown>;
    } else {
      return String(rawParams);
    }

    if (Array.isArray(paramsObj.batch)) {
      const items = paramsObj.batch as Array<Record<string, unknown>>;
      if (items.length === 0) return 'No parameters';
      return items
        .map((item, index) => {
          const tool = item.tool || item.toolName || `Tool #${index + 1}`;
          const p = item.params ? JSON.stringify(item.params, null, 2) : 'No parameters';
          return `[${index + 1}] ${tool}:\n${p}`;
        })
        .join('\n\n');
    }

    const entries = Object.entries(paramsObj);
    if (entries.length === 0) {
      return 'No parameters';
    }

    return entries
      .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v, null, 2) : v}`)
      .join('\n');
  }

  render() {
    return html`
      <div style="max-width: 1200px; margin: 0 auto;">
        <div class="header-row">
          <h2>Pending Approvals (${this.approvals.length})</h2>
          <button class="btn-refresh" @click="${this._manualRefresh}" title="Refresh pending approvals">↻ Refresh</button>
        </div>
        
        ${this.approvals.length === 0
          ? html`<p class="empty">✓ No pending approvals. All agents running autonomously.</p>`
          : this.approvals.map(a => html`
            <div class="card">
              <div class="card-header">
                <div class="tool-group">
                  <span class="tool-icon">⬢</span>
                  <span class="tool-name">${a.toolName}</span>
                  <span class="agent-name">by ${a.agentName}</span>
                </div>
                <span class="requested-at">${this._formatTime(a.requestedAt)}</span>
              </div>
              
              <pre class="params">${this._formatParams(a.params)}</pre>
              
              <div class="actions">
          ${this._resolvedElsewhere.has(this._approvalKey(a))
            ? html`<span class="status-msg" role="status">This approval was already resolved.</span>`
            : this.channelCommandPending
            ? html`<span class="empty">Approval action submitted. Waiting for the result...</span>`
            : this._processing.has(a.taskId)
            ? html`<span class="empty">Syncing with cluster...</span>`
            : this._statusUnknown.has(a.taskId)
              ? html`
                  <span class="empty">${this._statusNotice[a.taskId] || 'Approval status unknown.'}</span>
                  ${this._statusConflict.has(a.taskId)
                    ? ''
                    : html`<button class="btn btn-approve" @click="${() => this._retry(a.taskId)}">Retry</button>`}
                  <button class="btn btn-reject" @click="${() => this._checkStatus(a.taskId)}">Check status</button>
                `
              : html`
          <button class="btn btn-approve" @click="${() => this._approve(a)}">Approve Sequence</button>
          <button class="btn btn-reject" @click="${() => this._toggleReject(a.taskId)}" ?disabled="${this._rejecting.has(a.taskId)}">Abort</button>
        `
        }
              </div>
              
        ${this._rejecting.has(a.taskId)
          && !this._processing.has(a.taskId)
          && !this._statusUnknown.has(a.taskId)
          ? html`
                  <div class="reason-form">
                    <div style="font-size: 13px; font-weight: 700; color: #ef4444;">ABORT SEQUENCE</div>
                    <input class="reason-input" type="text" placeholder="Provide reason for abortion (optional)..."
                      .value="${this._rejectReason[a.taskId] || ''}"
                      @input="${(e: Event) => this._rejectReason = { ...this._rejectReason, [a.taskId]: (e.target as HTMLInputElement).value }}"
                      @keydown="${(e: KeyboardEvent) => e.key === 'Enter' && this._submitReject(a.taskId)}"
                    />
                    <div class="reason-actions">
                       <button class="btn" style="background: transparent; border: none; color: var(--text-muted);" @click="${() => this._toggleReject(a.taskId)}">Cancel</button>
                       <button class="btn" style="background: #ef4444; color: white;" @click="${() => this._submitReject(a.taskId)}">Confirm Abort</button>
                    </div>
                  </div>
                ` : ''
              }
              
              ${this.channelCommandError || this._error[a.taskId]
                ? html`<div class="error-msg">${this.channelCommandError || this._error[a.taskId]}</div>`
                : ''}
            </div>
          `)
        }
      </div>
    `;
  }
}
