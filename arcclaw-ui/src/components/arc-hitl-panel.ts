import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { PendingApprovalDto } from '../api/types';
import { ArcClient } from '../api/client';

@customElement('arc-hitl-panel')
export class ArcHitlPanel extends LitElement {
  @property({ type: String }) apiKey = '';
  @property({ type: Array }) approvals: PendingApprovalDto[] = [];
  
  @state() private _processing = new Set<string>();
  @state() private _rejectReason: Record<string, string> = {};
  @state() private _rejecting = new Set<string>();
  @state() private _error: Record<string, string> = {};

  static styles = css`
    :host { display: block; padding: 32px; overflow-y: auto; height: 100%; box-sizing: border-box; background: var(--bg-base); }
    h2 { color: var(--text-primary); margin-top: 0; margin-bottom: 24px; font-size: 20px; font-weight: 700; letter-spacing: -0.02em; }
    .empty { color: var(--text-muted); font-style: italic; font-size: 14px; }
    
    .card {
      background: var(--bg-surface); border: 1px solid var(--border); border-radius: 16px; 
      padding: 24px; margin-bottom: 20px;
      display: flex; flex-direction: column; gap: 16px;
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
  `;

  private async _approve(taskId: string) {
    this._processing.add(taskId);
    this._error = { ...this._error, [taskId]: '' };
    this.requestUpdate();

    try {
      const client = new ArcClient('/api/v1', this.apiKey);
      await client.approveHitl(taskId);
      this.dispatchEvent(new CustomEvent('approval-action', {
        detail: { taskId, action: 'approved' },
        bubbles: true, composed: true
      }));
    } catch (e: any) {
      this._error = { ...this._error, [taskId]: e.message || 'Approval failed' };
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
    this._processing.add(taskId);
    this._error = { ...this._error, [taskId]: '' };
    this.requestUpdate();

    try {
      const client = new ArcClient('/api/v1', this.apiKey);
      const reason = this._rejectReason[taskId] || '';
      await client.rejectHitl(taskId, reason);
      this.dispatchEvent(new CustomEvent('approval-action', {
        detail: { taskId, action: 'rejected' },
        bubbles: true, composed: true
      }));
    } catch (e: any) {
      this._error = { ...this._error, [taskId]: e.message || 'Rejection failed' };
    } finally {
      this._processing.delete(taskId);
      if (!this._error[taskId]) {
        this._rejecting.delete(taskId);
      }
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

  render() {
    return html`
      <h2>Pending Approvals (${this.approvals.length})</h2>
      
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
            
            <pre class="params">${Object.keys(a.params).length === 0 
                ? 'No parameters'
                : Object.entries(a.params).map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`).join('\n')}</pre>
            
            <div class="actions">
              ${this._processing.has(a.taskId)
                ? html`<span class="empty">Syncing with cluster...</span>`
                : html`
                  <button class="btn btn-approve" @click="${() => this._approve(a.taskId)}">Approve Sequence</button>
                  <button class="btn btn-reject" @click="${() => this._toggleReject(a.taskId)}" ?disabled="${this._rejecting.has(a.taskId)}">Abort</button>
                `
              }
            </div>
            
            ${this._rejecting.has(a.taskId) && !this._processing.has(a.taskId)
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
            
            ${this._error[a.taskId] ? html`<div class="error-msg">${this._error[a.taskId]}</div>` : ''}
          </div>
        `)
      }
    `;
  }
}
