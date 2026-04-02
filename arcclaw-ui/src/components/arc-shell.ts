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
import './arc-settings';

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
  private _hitlEs: EventSource | null = null;
  private _agentEs: EventSource | null = null;

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

    .topbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      min-height: 64px;
      padding: 0 1.5rem;
      padding-right: 5.5rem; /* reserves space for fixed theme toggle */
      border-bottom: 1px solid var(--border);
      flex-shrink: 0;
      background: var(--bg-base);
      z-index: 10;
    }

    .topbar-right {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      min-width: 0;
      flex-shrink: 0;
    }

    .connection-pill {
      display: inline-flex;
      align-items: center;
      gap: 0.625rem;
      max-width: min(420px, calc(100vw - 180px));
      padding: 0.5rem 0.875rem;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: color-mix(in srgb, var(--bg-elevated) 90%, transparent);
      color: var(--text-muted);
      font-size: var(--font-size-sm);
      line-height: 1;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      backdrop-filter: blur(10px);
    }

    .connection-pill .status-dot {
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 999px;
      background: var(--accent-secondary);
      box-shadow: 0 0 0 6px color-mix(in srgb, var(--accent-secondary) 18%, transparent);
      flex-shrink: 0;
    }

    @media (max-width: 900px) {
      .topbar {
        padding-right: 4.75rem;
      }

      .connection-pill {
        max-width: 220px;
        font-size: var(--font-size-xs);
      }
    }

    @media (max-width: 640px) {
      .connection-pill {
        display: none;
      }
    }
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
    this._hitlEs?.close();
    this._agentEs?.close();
  }

  private _connectSse(url: string, onEvent: (data: any) => void, onFallback: () => void) {
    if (typeof EventSource === 'undefined') {
        onFallback();
        return null;
    }
    const es = new EventSource(url);
    let sseActive = true;
    es.onmessage = (e) => {
        onEvent(JSON.parse(e.data));
    };
    es.onerror = () => {
        if (sseActive) {
            sseActive = false;
            es.close();
            console.warn('SSE failed, falling back to polling:', url);
            onFallback();
        }
    };
    return es;
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
    
    // Start specific SSE push endpoints
    this._hitlEs?.close();
    this._agentEs?.close();

    const startPollingFallback = () => {
       this._startPolling();
    };

    if (this._sessionId) {
      this._hitlEs = this._connectSse(
          `/api/v1/hitl/events?sessionId=${this._sessionId}`,
          (event) => this._handleHitlEvent(event),
          startPollingFallback
      );
      this._agentEs = this._connectSse(
          `/api/v1/agents/events?sessionId=${this._sessionId}`,
          (event) => this._handleAgentEvent(event),
          startPollingFallback
      );
    }
  }
  
  private _handleHitlEvent(event: any) {
    if (event.type === 'AWAITING_APPROVAL') {
      const existing = this._pendingApprovals.find(a => a.taskId === event.sessionId && a.toolName === event.toolName);
      if (!existing) {
         let paramsObj = {};
         try { paramsObj = JSON.parse(event.toolParams || '{}'); } catch(e) {}
         this._pendingApprovals = [...this._pendingApprovals, {
            taskId: event.sessionId,
            toolName: event.toolName,
            params: paramsObj,
            requestedAt: event.timestamp || new Date().toISOString(),
            agentName: 'Agent'
         } as PendingApprovalDto];
      }
    } else {
      this._pendingApprovals = this._pendingApprovals.filter(a => !(a.taskId === event.sessionId && (a.toolName === event.toolName || !event.toolName)));
    }
  }

  private _handleAgentEvent(event: any) {
     const idx = this._agents.findIndex(a => a.agentName === event.agentRole || a.role === event.agentRole);
     if (idx >= 0) {
        const updated = [...this._agents];
        updated[idx] = { ...updated[idx], status: event.status };
        this._agents = updated;
     } else {
       this._fetchAgents();
     }
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
    // Fallback: poll every 10 seconds
    const poll = async () => {
      await this._fetchApprovals();
      await this._fetchAgents();
    };
    poll(); // immediate first call
    this._pollingInterval = window.setInterval(poll, 10000);
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
          // Re-fetch state immediately on any status change
          this._fetchAgents();
          this._fetchApprovals();
        } else if (event.eventType === 'heartbeat') {
          // Just confirms connection is alive
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
      case '#/settings':
        return html`<arc-settings class="content-area" .apiKey="${this._apiKey}"></arc-settings>`;
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
          ${this._apiKey ? html`
            <header class="topbar">
              <div style="font-weight: 600; font-size: 0.9rem;">
                ${this._route === '#/chat' ? 'Chat Session' : 
                  this._route === '#/agents' ? 'Agent Status' :
                  this._route === '#/hitl' ? 'Approvals' :
                  this._route === '#/skills' ? 'Skills' :
                  this._route === '#/settings' ? 'Settings' : ''}
              </div>
              <div class="topbar-right">
                <div class="connection-pill">
                  <div class="status-dot" style="background: ${this._sseStatus === 'connected' ? 'var(--accent-secondary)' : 'var(--text-muted)'}"></div>
                  Connected to ArcClaw Agent Cluster
                </div>
              </div>
            </header>
          ` : nothing}
          ${this._renderRoute()}
        </main>
      </div>
    `;
  }
}
