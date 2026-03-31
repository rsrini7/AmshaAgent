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
    :host { display: block; padding: var(--space-6); overflow-y: auto; height: 100%; box-sizing: border-box; }
    h2 { color: var(--color-text-primary); margin-top: 0; margin-bottom: var(--space-4); font-size: var(--font-size-lg); }
    .empty { color: var(--color-text-secondary); font-style: italic; }
    
    .card {
      background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md);
      padding: var(--space-4); margin-bottom: var(--space-4);
      display: flex; flex-direction: column; gap: var(--space-3);
    }
    .card-header { display: flex; justify-content: space-between; align-items: center; }
    .tool-group { display: flex; align-items: baseline; gap: var(--space-2); }
    .tool-name { color: var(--color-warning); font-weight: var(--font-weight-bold); font-size: var(--font-size-base); }
    .agent-name { color: var(--color-text-secondary); font-size: var(--font-size-sm); }
    .requested-at { color: var(--color-text-secondary); font-size: var(--font-size-sm); }
    
    .params {
      background: var(--color-bg-base); border-radius: var(--radius-sm); padding: var(--space-3);
      font-family: monospace; font-size: 0.8rem; color: var(--color-text-primary);
      margin: 0; white-space: pre-wrap; word-break: break-all;
    }
    
    .actions { display: flex; gap: var(--space-2); align-items: center; flex-wrap: wrap; margin-top: var(--space-2); }
    .btn {
      border: none; border-radius: var(--radius-sm); color: white;
      cursor: pointer; padding: var(--space-2) var(--space-4); font-size: var(--font-size-sm);
      font-weight: var(--font-weight-medium);
    }
    .btn:disabled { opacity: 0.6; cursor: not-allowed; }
    .btn-approve { background: var(--color-success); }
    .btn-approve:hover:not(:disabled) { background: #16a34a; }
    .btn-reject { background: transparent; border: 1px solid var(--color-error); color: var(--color-error); }
    .btn-reject:hover:not(:disabled) { background: rgba(239, 68, 68, 0.1); }
    
    .reason-form { display: flex; gap: var(--space-2); width: 100%; margin-top: var(--space-2); }
    .reason-input {
      flex: 1; background: var(--color-bg-base); border: 1px solid var(--color-border);
      border-radius: var(--radius-sm); color: var(--color-text-primary);
      padding: var(--space-2); font-size: var(--font-size-sm); outline: none;
    }
    .reason-input:focus { border-color: var(--color-error); }
    
    .error-msg { color: var(--color-error); font-size: var(--font-size-sm); margin-top: var(--space-2); }
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
      // Don't close reject form if error
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
                <span class="tool-name">🔧 ${a.toolName}</span>
                <span class="agent-name">by ${a.agentName}</span>
              </div>
              <span class="requested-at">${this._formatTime(a.requestedAt)}</span>
            </div>
            
            <pre class="params">${Object.keys(a.params).length === 0 
                ? 'No parameters'
                : Object.entries(a.params).map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`).join('\n')}</pre>
            
            <div class="actions">
              ${this._processing.has(a.taskId)
                ? html`<span class="empty">Processing...</span>`
                : html`
                  <button class="btn btn-approve" @click="${() => this._approve(a.taskId)}">Approve</button>
                  <button class="btn btn-reject" @click="${() => this._toggleReject(a.taskId)}" ?disabled="${this._rejecting.has(a.taskId)}">Deny</button>
                `
              }
            </div>
            
            ${this._rejecting.has(a.taskId) && !this._processing.has(a.taskId)
              ? html`
                <div class="reason-form">
                  <input class="reason-input" type="text" placeholder="Reason for denial (optional)..."
                    .value="${this._rejectReason[a.taskId] || ''}"
                    @input="${(e: Event) => this._rejectReason = { ...this._rejectReason, [a.taskId]: (e.target as HTMLInputElement).value }}"
                    @keydown="${(e: KeyboardEvent) => e.key === 'Enter' && this._submitReject(a.taskId)}"
                  />
                  <button class="btn btn-reject" @click="${() => this._submitReject(a.taskId)}" style="background: var(--color-error); color: white;">Confirm Deny</button>
                  <button class="btn btn-reject" @click="${() => this._toggleReject(a.taskId)}" style="border: none; color: var(--color-text-secondary);">Cancel</button>
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
