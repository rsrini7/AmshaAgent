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
import './arc-theme-toggle';

@customElement('arc-shell')
export class ArcShell extends LitElement {
  @state() private _apiKey = localStorage.getItem('arc-api-key') || '';
  @state() private _sessionId = sessionStorage.getItem('arcclaw-chat-session') || '';
  @state() private _platformId = ''; // Stable ID for WebSocket
  @state() private _agents: AgentStatusDto[] = [];
  @state() private _pendingApprovals: PendingApprovalDto[] = [];
  @state() private _sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';
  @state() private _route = window.location.hash || (localStorage.getItem('arc-api-key') ? '#/chat' : '#/onboarding');
  @state() private _isNavOpen = false;
  @state() private _chatWsStatus: string = 'closed';

  private _client: ArcClient | null = null;
  private _sseManager: SseManager | null = null;

  static styles = css`
    :host { 
      display: flex; height: 100vh; overflow: hidden; 
      background: var(--bg-base); 
      color: var(--text-primary);
    }
    main { 
      flex: 1; overflow: hidden; display: flex; flex-direction: column; 
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
      .connection-pill { max-width: 220px; font-size: var(--font-size-xs); }
    }
    @media (max-width: 640px) {
      .connection-pill { display: none; }
      .menu-toggle { display: block; }
    }
    .menu-toggle {
      display: none; background: transparent; border: none; color: var(--text-primary);
      font-size: 1.25rem; cursor: pointer; padding: 0.5rem; border-radius: 8px;
    }
    .menu-toggle:hover { background: var(--bg-elevated); }
    .nav-overlay {
      position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 90;
      backdrop-filter: blur(4px);
    }
  `;

  constructor() {
    super();
    this.addEventListener('api-key-set', this._onApiKeySet as EventListener);
    this.addEventListener('session-ready', this._onSessionReady as EventListener);
    this.addEventListener('approval-action', this._onApprovalAction as EventListener);
    this.addEventListener('ws-status-change', ((e: CustomEvent) => this._chatWsStatus = e.detail.status) as EventListener);
    window.addEventListener('hashchange', () => {
      this._route = window.location.hash || '#/onboarding';
      this._isNavOpen = false; // Close nav on route change
      if (!this._apiKey && this._route !== '#/onboarding') {
        window.location.hash = '#/onboarding';
        return;
      }

      // Proactively refresh when navigating if not connected
      if (this._apiKey) {
        if (this._sseStatus !== 'connected') {
          this._sseManager?.connect();
        }
        this._fetchAgents();
        this._fetchApprovals();
      }
    });

    const storedKey = localStorage.getItem('arc-api-key');
    if (storedKey) {
      this._apiKey = storedKey;
      this._client = new ArcClient('/api/v1', storedKey);
      this._platformId = this._resolvePlatformId();
      // _sessionId is already loaded from sessionStorage in @state()
      this.updateComplete.then(() => {
        this._fetchAgents();
        this._fetchApprovals();
        this._startSse();
      });
    }
  }

  private _resolvePlatformId() {
    let id = sessionStorage.getItem('arcclaw-platform-id');
    if (!id) {
      id = crypto.randomUUID();
      sessionStorage.setItem('arcclaw-platform-id', id);
    }
    return id;
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._sseManager?.disconnect();
  }

  // ─── SSE ─────────────────────────────────────────────────────────────────



  // ─── Lifecycle / Auth ────────────────────────────────────────────────────

  private _onApiKeySet(e: CustomEvent<{ key: string }>) {
    const key = e.detail.key;
    this._apiKey = key;
    localStorage.setItem('arc-api-key', key);
    this._client = new ArcClient('/api/v1', this._apiKey);

    // Close stale SSE connections bound to the previous key.
    // They will be re-opened with the new key once session-ready fires.
    this._sseManager?.disconnect();

    window.location.hash = '#/chat';
    this._fetchAgents();
    this._fetchApprovals();
    this._startSse();
  }

  private _handleUnauthorized() {
    console.warn('[ArcClaw] Stale API key detected (401). Redirecting to onboarding...');
    this._apiKey = '';
    localStorage.removeItem('arc-api-key');
    window.location.hash = '#/onboarding';
  }

  private _onSessionReady(e: CustomEvent<{ sessionId: string; platformId: string }>) {
    // Only update if server assigned a different ID (fallback UUID case)
    if (this._sessionId !== e.detail.sessionId) {
        this._sessionId = e.detail.sessionId;
        sessionStorage.setItem('arcclaw-chat-session', this._sessionId);
    }

    if (!this._sessionId) return;
    this._startSse(); // Re-start all SSEs with new session context
  }

  // SSE event handlers
  private _handleSseEvent(event: any) {
    if (event.type === 'status_change') {
       this._fetchAgents();
       this._fetchApprovals();
    } else if (event.type === 'AWAITING_APPROVAL') {
       this._handleHitlEvent(event);
    } else if (event.type === 'agent_status') {
       this._handleAgentEvent(event);
    }
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
      // APPROVED, DENIED, RESUMED - remove from pending list
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
      // Unknown agent - re-fetch full list
      this._fetchAgents();
    }
  }

  private _onApprovalAction(_e: CustomEvent<{ taskId: string; action: string }>) {
    // Re-fetch after user acts - SSE will also push update but this is a safety net
    this._fetchApprovals();
  }

  // ─── Data Fetching ───────────────────────────────────────────────────────

  private async _fetchAgents() {
    if (!this._client) return;
    try {
      this._agents = await this._client.getAgents();
    } catch (err: any) {
      if (err.message?.includes('401')) {
        this._handleUnauthorized();
      }
    }
  }

  private async _fetchApprovals() {
    if (!this._client) return;
    try {
      this._pendingApprovals = await this._client.getHitl();
    } catch (err: any) {
      if (err.message?.includes('401')) {
        this._handleUnauthorized();
      }
    }
  }

  // ─── Chat SSE (existing) ─────────────────────────────────────────────────

  private _startSse() {
    this._sseManager?.disconnect();
    this._sseManager = new SseManager(
      `/api/v1/chat/stream?sessionId=${encodeURIComponent(this._sessionId)}`,
      this._apiKey,
      (event: AgentEvent) => {
        if (event.eventType === 'status_change') {
          this._fetchAgents();
          this._fetchApprovals();
        }
        this._handleSseEvent(event);
      },
      (status) => {
        const wasDown = this._sseStatus !== 'connected';
        this._sseStatus = status;
        if (wasDown && status === 'connected') {
          this._fetchAgents();
          this._fetchApprovals();
        }
      }
    );
    this._sseManager.connect();
  }

  // ─── Routing ─────────────────────────────────────────────────────────────

  private _renderViews() {
    if (!this._apiKey) {
      if (this._route !== '#/onboarding') {
        window.setTimeout(() => (window.location.hash = '#/onboarding'), 0);
      }
      return html`<arc-onboarding class="content-area"></arc-onboarding>`;
    }

    return html`
      <arc-chat-interface
        class="content-area"
        style="display: ${this._route === '#/chat' ? 'flex' : 'none'}"
        .apiKey="${this._apiKey}"
        .sessionId="${this._sessionId}"
        .platformId="${this._platformId}"
      ></arc-chat-interface>

      ${this._route === '#/agents' ? html`
        <arc-agent-status class="content-area" .agents="${this._agents}"></arc-agent-status>
      ` : nothing}

      ${this._route === '#/hitl' ? html`
        <arc-hitl-panel class="content-area" .apiKey="${this._apiKey}" .approvals="${this._pendingApprovals}"></arc-hitl-panel>
      ` : nothing}

      ${this._route === '#/skills' ? html`
        <arc-skills-panel class="content-area" .apiKey="${this._apiKey}"></arc-skills-panel>
      ` : nothing}

      ${this._route === '#/settings' ? html`
        <arc-settings class="content-area" .apiKey="${this._apiKey}"></arc-settings>
      ` : nothing}
    `;
  }

  // ─── Render ──────────────────────────────────────────────────────────────

  render() {
    return html`
      ${this._apiKey
        ? html`
            <arc-nav .route="${this._route}" .sseStatus="${this._sseStatus}" ?open="${this._isNavOpen}"></arc-nav>
            ${this._isNavOpen ? html`<div class="nav-overlay" @click="${() => this._isNavOpen = false}"></div>` : ''}
          `
        : ''}
      <main>
        ${this._apiKey
          ? html`
              <header class="topbar">
                <div style="display: flex; align-items: center; gap: 0.5rem;">
                   <button class="menu-toggle" aria-label="Toggle menu" @click="${() => this._isNavOpen = !this._isNavOpen}">☰</button>
                   <div style="font-weight: 600; font-size: 0.9rem; display: flex; align-items: center; gap: 8px;">
                     ${this._route === '#/chat'
                       ? html`
                         Chat Session 
                         <span class="status-badge status-${this._chatWsStatus}" style="font-size: 10px; padding: 2px 8px; border-radius: 4px; text-transform: uppercase;">${this._chatWsStatus}</span>
                         ${this._chatWsStatus === 'error' || this._chatWsStatus === 'closed' ? html`
                            <button @click="${() => (this.shadowRoot?.querySelector('arc-chat-interface') as any)?.reconnect()}" 
                                    style="background:transparent; border:1px solid var(--border); color:var(--text-muted); font-size:10px; padding:2px 8px; border-radius:4px; cursor:pointer; margin-left:4px;">
                              Reconnect
                            </button>
                         ` : nothing}
                       `
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
                </div>
                <div class="topbar-right">
                  <div class="connection-pill">
                    <div
                      class="status-dot"
                      style="background: ${this._sseStatus === 'connected'
                        ? 'var(--accent-secondary)'
                        : this._sseStatus === 'error' ? '#ef4444' : 'var(--text-muted)'};
                        box-shadow: 0 0 0 6px color-mix(in srgb, ${this._sseStatus === 'connected' ? 'var(--accent-secondary)' : this._sseStatus === 'error' ? '#ef4444' : 'var(--text-muted)'} 18%, transparent);"
                    ></div>
                    ${this._sseStatus === 'connected' ? 'Connected to ArcClaw Agent Cluster' : 'ArcClaw Agent Cluster (Offline)'}
                  </div>
                  <arc-theme-toggle></arc-theme-toggle>
                </div>
              </header>
            `
          : html`
              <arc-theme-toggle style="position: fixed; top: 16px; right: 16px; z-index: 1000;"></arc-theme-toggle>
            `}
        ${this._renderViews()}
      </main>
    `;
  }
}
