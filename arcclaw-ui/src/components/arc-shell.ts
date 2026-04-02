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
      padding-right: 5.5rem;
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
      .topbar { padding-right: 4.75rem; }
      .connection-pill { max-width: 220px; font-size: var(--font-size-xs); }
    }
    @media (max-width: 640px) {
      .connection-pill { display: none; }
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
        // No polling on startup — SSE handles live updates.
        // Polling only starts as fallback if SSE fails.
      });
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._stopPolling();
    this._sseManager?.disconnect();
    this._hitlEs?.close();
    this._hitlEs = null;
    this._agentEs?.close();
    this._agentEs = null;
  }

  // ─── SSE ─────────────────────────────────────────────────────────────────

  private _connectSse(
    url: string,
    onEvent: (data: any) => void,
    onFallback: () => void
  ): EventSource | null {
    if (typeof EventSource === 'undefined') {
      console.warn('EventSource not supported, using polling fallback');
      onFallback();
      return null;
    }

    const es = new EventSource(url);
    let sseActive = true;
    let connected = false;

    es.onopen = () => {
      connected = true;
      console.debug('SSE connected:', url);
    };

    es.onmessage = (e) => {
      try {
        onEvent(JSON.parse(e.data));
      } catch (err) {
        console.warn('SSE message parse error:', err);
      }
    };

    es.onerror = () => {
      if (!sseActive) return;
      sseActive = false;
      es.close();
      console.warn(
        connected
          ? 'SSE connection lost, falling back to polling: ' + url
          : 'SSE initial connect failed, falling back to polling: ' + url
      );
      onFallback();
    };

    return es;
  }

  // ─── Lifecycle / Auth ────────────────────────────────────────────────────

  private _onApiKeySet(e: CustomEvent<{ key: string }>) {
    const key = e.detail.key;
    this._apiKey = key;
    localStorage.setItem('arc-api-key', key);
    this._client = new ArcClient('/api/v1', this._apiKey);
    window.location.hash = '#/chat';
    this._fetchAgents();
    this._fetchApprovals();
    this._startSse();
    // No polling started here — only on SSE fallback
  }

  private _onSessionReady(e: CustomEvent<{ sessionId: string; platformId: string }>) {
    this._sessionId = e.detail.sessionId;

    // Close any previous SSE connections
    this._hitlEs?.close();
    this._hitlEs = null;
    this._agentEs?.close();
    this._agentEs = null;

    if (!this._sessionId) return;

    // Single fallback function — only starts polling once even if both SSEs fail
    let pollingStarted = false;
    const startPollingOnce = () => {
      if (!pollingStarted) {
        pollingStarted = true;
        this._startPolling();
      }
    };

    this._hitlEs = this._connectSse(
      `/api/v1/hitl/events?sessionId=${this._sessionId}&apiKey=${this._apiKey}`,
      (event) => this._handleHitlEvent(event),
      startPollingOnce
    );

    this._agentEs = this._connectSse(
      `/api/v1/agents/events?sessionId=${this._sessionId}&apiKey=${this._apiKey}`,
      (event) => this._handleAgentEvent(event),
      startPollingOnce
    );
  }

    // ─── SSE Event Handlers ──────────────────────────────────────────────────

  private _handleHitlEvent(event: any) {
    if (event.type === 'AWAITING_APPROVAL') {
      // Add to pending if not already present
      const existing = this._pendingApprovals.find(
        (a) => a.taskId === event.sessionId && a.toolName === event.toolName
      );
      if (!existing) {
        let paramsObj = {};
        try { paramsObj = JSON.parse(event.toolParams || '{}'); } catch (_) {}
        this._pendingApprovals = [
          ...this._pendingApprovals,
          {
            taskId: event.sessionId,
            toolName: event.toolName,
            params: paramsObj,
            requestedAt: event.timestamp || new Date().toISOString(),
            agentName: 'Agent',
          } as PendingApprovalDto,
        ];
      }
    } else {
      // APPROVED, DENIED, RESUMED — remove from pending list
      this._pendingApprovals = this._pendingApprovals.filter(
        (a) =>
          !(
            a.taskId === event.sessionId &&
            (a.toolName === event.toolName || !event.toolName)
          )
      );
    }
  }

  private _handleAgentEvent(event: any) {
    const idx = this._agents.findIndex(
      (a) => (a as any).agentName === event.agentRole || (a as any).role === event.agentRole
    );
    if (idx >= 0) {
      const updated = [...this._agents];
      updated[idx] = { ...updated[idx], status: event.status } as AgentStatusDto;
      this._agents = updated;
    } else {
      // Unknown agent — re-fetch full list
      this._fetchAgents();
    }
  }

  private _onApprovalAction(_e: CustomEvent<{ taskId: string; action: string }>) {
    // Re-fetch after user acts — SSE will also push update but this is a safety net
    this._fetchApprovals();
  }

  // ─── Data Fetching ───────────────────────────────────────────────────────

  private async _fetchAgents() {
    if (!this._client) return;
    try {
      this._agents = await this._client.getAgents();
    } catch { /* suppress background fetch errors */ }
  }

  private async _fetchApprovals() {
    if (!this._client) return;
    try {
      this._pendingApprovals = await this._client.getHitl();
    } catch { /* suppress background fetch errors */ }
  }

  // ─── Polling (fallback only) ─────────────────────────────────────────────

  private _startPolling() {
    this._stopPolling();
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

  // ─── Chat SSE (existing) ─────────────────────────────────────────────────

  private _startSse() {
    this._sseManager?.disconnect();
    this._sseManager = new SseManager(
      '/api/v1/chat/stream',
      this._apiKey,
      (event: AgentEvent) => {
        if (event.eventType === 'status_change') {
          this._fetchAgents();
          this._fetchApprovals();
        }
        // heartbeat — no action needed
      },
      (status) => {
        this._sseStatus = status;
      }
    );
    this._sseManager.connect();
  }

  // ─── Context ─────────────────────────────────────────────────────────────

  private _provideContext() {
    return {
      apiKey: this._apiKey,
      sessionId: this._sessionId,
      sseStatus: this._sseStatus,
    };
  }

  // ─── Routing ─────────────────────────────────────────────────────────────

  private _renderRoute() {
    if (!this._apiKey && this._route !== '#/onboarding') {
      window.setTimeout(() => (window.location.hash = '#/onboarding'), 0);
      return nothing;
    }

    switch (this._route) {
      case '#/chat':
        return html`<arc-chat-interface
          class="content-area"
          .apiKey="${this._apiKey}"
          .sessionId="${this._sessionId}"
        ></arc-chat-interface>`;
      case '#/agents':
        return html`<arc-agent-status
          class="content-area"
          .agents="${this._agents}"
        ></arc-agent-status>`;
      case '#/hitl':
        return html`<arc-hitl-panel
          class="content-area"
          .apiKey="${this._apiKey}"
          .approvals="${this._pendingApprovals}"
        ></arc-hitl-panel>`;
      case '#/skills':
        return html`<arc-skills-panel
          class="content-area"
          .apiKey="${this._apiKey}"
        ></arc-skills-panel>`;
      case '#/settings':
        return html`<arc-settings
          class="content-area"
          .apiKey="${this._apiKey}"
        ></arc-settings>`;
      case '#/onboarding':
      default:
        return html`<arc-onboarding class="content-area"></arc-onboarding>`;
    }
  }

    // ─── Render ──────────────────────────────────────────────────────────────

  render() {
    const ctx = this._provideContext();
    return html`
      <div
        @arc-app-context="${(e: any) => e.detail.callback(ctx)}"
        style="display: contents;"
      >
        ${this._apiKey
          ? html`<arc-nav .route="${this._route}" .sseStatus="${this._sseStatus}"></arc-nav>`
          : ''}
        <main>
          ${this._apiKey
            ? html`
                <header class="topbar">
                  <div style="font-weight: 600; font-size: 0.9rem;">
                    ${this._route === '#/chat'
                      ? 'Chat Session'
                      : this._route === '#/agents'
                      ? 'Agent Status'
                      : this._route === '#/hitl'
                      ? 'Approvals'
                      : this._route === '#/skills'
                      ? 'Skills'
                      : this._route === '#/settings'
                      ? 'Settings'
                      : ''}
                  </div>
                  <div class="topbar-right">
                    <div class="connection-pill">
                      <div
                        class="status-dot"
                        style="background: ${this._sseStatus === 'connected'
                          ? 'var(--accent-secondary)'
                          : 'var(--text-muted)'}"
                      ></div>
                      Connected to ArcClaw Agent Cluster
                    </div>
                  </div>
                </header>
              `
            : nothing}
          ${this._renderRoute()}
        </main>
      </div>
    `;
  }
}