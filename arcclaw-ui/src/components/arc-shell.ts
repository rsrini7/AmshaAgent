import { LitElement, css, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { AgentStatusDto, PendingApprovalDto, AgentEvent } from '../api/types';
import { ArcClient } from '../api/client';
import { SseManager } from '../api/sse';

import './arc-onboarding';
import './arc-nav';
import './arc-chat-interface';
import './arc-agent-status';
import './arc-hitl-panel';
import './arc-skills-panel';

@customElement('arc-shell')
export class ArcShell extends LitElement {
  @state() private _apiKey = localStorage.getItem('arc-api-key') || '';
  @state() private _sessionId = '';

  @state() private _agents: AgentStatusDto[] = [];
  @state() private _pendingApprovals: PendingApprovalDto[] = [];
  @state() private _sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';
  @state() private _route = window.location.hash || (localStorage.getItem('arc-api-key') ? '#/chat' : '#/onboarding');

  private _client: ArcClient | null = null;
  private _sseManager: SseManager | null = null;
  private _pollingInterval: number | null = null;

  static styles = css`
    :host { 
      display: flex; height: 100vh; overflow: hidden; 
      background: var(--bg-base); 
      color: var(--text-primary);
    }
    main { 
      flex: 1; overflow-y: auto; display: flex; flex-direction: column; 
      position: relative; 
    }
    .content-area { flex: 1; min-height: 0; }
  `;

  constructor() {
    super();
    this.addEventListener('api-key-set', this._onApiKeySet as EventListener);
    this.addEventListener('session-ready', this._onSessionReady as EventListener);
    this.addEventListener('approval-action', this._onApprovalAction as EventListener);
    window.addEventListener('hashchange', () => {
      this._route = window.location.hash || '#/onboarding';
      if (!this._apiKey && this._route !== '#/onboarding') {
        window.location.hash = '#/onboarding';
      }
    });

    const storedKey = localStorage.getItem('arc-api-key');
    if (storedKey) {
      this._client = new ArcClient('/api/v1', storedKey);
      this.updateComplete.then(() => {
        this._fetchAgents();
        this._fetchApprovals();
        this._startSse();
        this._startPolling();
      });
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._stopPolling();
    this._sseManager?.disconnect();
  }

  private _onApiKeySet(e: CustomEvent<{ key: string }>) {
    const key = e.detail.key;
    
    this._apiKey = key;
    localStorage.setItem('arc-api-key', key);
    this._client = new ArcClient('/api/v1', this._apiKey);
    window.location.hash = '#/chat';
    
    // Initial fetch
    this._fetchAgents();
    this._fetchApprovals();
    
    // Start SSE and Polling
    this._startSse();
    this._startPolling();
  }

  private _onSessionReady(e: CustomEvent<{ sessionId: string, platformId: string }>) {
    this._sessionId = e.detail.sessionId;
  }
  
  private _onApprovalAction(_e: CustomEvent<{ taskId: string, action: string }>) {
    // Refresh approvals list when user acts on one
    this._fetchApprovals();
  }

  private async _fetchAgents() {
    if (!this._client) return;
    try {
      this._agents = await this._client.getAgents();
    } catch { /* Suppress errors from background fetch */ }
  }

  private async _fetchApprovals() {
    if (!this._client) return;
    try {
      this._pendingApprovals = await this._client.getHitl();
    } catch { /* Suppress errors from background fetch */ }
  }

  private _startPolling() {
    this._stopPolling();
    // Poll /hitl every 10 seconds
    this._pollingInterval = window.setInterval(() => {
      if (this._sseStatus === 'connected') {
        this._fetchApprovals();
      }
    }, 10000);
  }

  private _stopPolling() {
    if (this._pollingInterval !== null) {
      clearInterval(this._pollingInterval);
      this._pollingInterval = null;
    }
  }

  private _startSse() {
    this._sseManager?.disconnect();
    this._sseManager = new SseManager(
      '/api/v1/chat/stream',
      this._apiKey,
      (event: AgentEvent) => {
        if (event.eventType === 'status_change') {
          // Re-fetch all agents on any status change to ensure full state sync
          this._fetchAgents();
        } else if (event.eventType === 'heartbeat') {
          // Just confirms connection is alive, maybe update a timestamp if needed
        }
      },
      (status) => {
        this._sseStatus = status;
        if (status === 'disconnected' || status === 'error') {
          // Attempt reconnect logic could go here
        }
      }
    );
    this._sseManager.connect();
  }

  private _provideContext() {
    return {
      apiKey: this._apiKey,
      sessionId: this._sessionId,
      sseStatus: this._sseStatus
    };
  }

  private _renderRoute() {
    if (!this._apiKey && this._route !== '#/onboarding') {
      window.setTimeout(() => window.location.hash = '#/onboarding', 0);
      return nothing;
    }
    
    switch (this._route) {
      case '#/chat':
        return html`<arc-chat-interface class="content-area" .apiKey="${this._apiKey}" .sessionId="${this._sessionId}"></arc-chat-interface>`;
      case '#/agents':
        return html`<arc-agent-status class="content-area" .agents="${this._agents}"></arc-agent-status>`;
      case '#/hitl':
        return html`<arc-hitl-panel class="content-area" .apiKey="${this._apiKey}" .approvals="${this._pendingApprovals}"></arc-hitl-panel>`;
      case '#/skills':
        return html`<arc-skills-panel class="content-area" .apiKey="${this._apiKey}"></arc-skills-panel>`;
      case '#/onboarding':
      default:
        return html`<arc-onboarding class="content-area"></arc-onboarding>`;
    }
  }

  render() {
    const ctx = this._provideContext();
    return html`
      <div 
        @arc-app-context="${(e: any) => e.detail.callback(ctx)}"
        style="display: contents;"
      >
        ${this._apiKey ? html`<arc-nav .route="${this._route}" .sseStatus="${this._sseStatus}"></arc-nav>` : ''}
        <main>
          ${this._renderRoute()}
        </main>
      </div>
    `;
  }
}
