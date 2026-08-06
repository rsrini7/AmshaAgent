import { LitElement, css, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { AgentStatusDto, AgentEvent } from '../api/generated/models';
import { PendingApprovalDto } from '../api/types';
import { ArcClient } from '../api/client';
import { SseManager } from '../api/sse';
import { provide } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ROUTES } from '../routes';


import './arc-onboarding';
import './arc-nav';
import './arc-chat-interface';
import './arc-mcp-panel';
import './arc-agent-status';
import './arc-hitl-panel';
import './arc-skills-panel';
import './arc-scheduler-panel';
import './arc-audit-panel';
import './arc-settings';
import './arc-theme-toggle';

@customElement('arc-shell')
export class ArcShell extends LitElement {
  @provide({ context: arcContext })
  @state()
  // @ts-ignore - Used by Lit @provide decorator to supply context to children
  private _appContext: ArcAppContext = {
    apiKey: '',
    sessionId: '',
    platformId: '',
    sseStatus: 'disconnected'
  };

  @state() private _apiKey = '';
  @state() private _sessionId = sessionStorage.getItem('amsha-chat-session') || '';
  @state() private _platformId = ''; // Stable ID for WebSocket
  @state() private _agents: AgentStatusDto[] = [];
  @state() private _pendingApprovals: PendingApprovalDto[] = [];
  @state() private _sseStatus: 'connected' | 'disconnected' | 'error' = 'disconnected';
  @state() private _route = this._routeFromHash(window.location.hash || '#/onboarding');
  @state() private _isNavOpen = false;
  @state() private _chatWsStatus: string = 'closed';

  private _client: ArcClient | null = null;
  private _sseManager: SseManager | null = null;
  private _boundSessionReady = ((e: Event) => this._onSessionReady(e as CustomEvent)) as EventListener;
  private _pendingMcpSetupHash: string | null = null;

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
    .content-area { flex: 1; min-height: 0; overflow-y: auto; }
    .content-area[hidden] { display: none !important; }
    .content-area::-webkit-scrollbar { width: 6px; }
    .content-area::-webkit-scrollbar-track { background: transparent; }
    .content-area::-webkit-scrollbar-thumb {
      background: var(--border);
      border-radius: 999px;
    }
    .content-area::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }
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

  private _updateContext() {
    this._appContext = {
      apiKey: this._apiKey,
      sessionId: this._sessionId,
      platformId: this._platformId,
      sseStatus: this._sseStatus
    };
  }

  constructor() {
    super();
    this.addEventListener('api-key-set', this._onApiKeySet as EventListener);
    this.addEventListener('approval-action', this._onApprovalAction as EventListener);
    window.addEventListener('ws-status-change', ((e: CustomEvent) => this._chatWsStatus = e.detail.status) as EventListener);
    window.addEventListener('session-ready', this._boundSessionReady);

    this._platformId = this._resolvePlatformId();
    this._client = new ArcClient('/api/v1', '');
    this._updateContext();
  }

  private _boundHashChange = () => {
    const currentHash = window.location.hash || '#/onboarding';
    if (!this._apiKey && this._isMcpSetupHash(currentHash)) {
      this._pendingMcpSetupHash = currentHash;
    }
    this._route = this._routeFromHash(currentHash);
    this._isNavOpen = false; // Close nav on route change
    if (!this._apiKey && this._route !== '#/onboarding') {
      window.location.hash = '#/onboarding';
      return;
    }
    if (this._apiKey && this._route === '#/onboarding') {
      this._route = '#/chat';
      window.location.hash = '#/chat';
      return;
    }

    // Proactively refresh when navigating if not connected
    if (this._apiKey) {
      if (this._sseStatus !== 'connected') {
        this._sseManager?.connect();
      }
      if (this._route === '#/chat' && this._chatWsStatus !== 'open') {
        this._chatWsStatus = 'connecting';
        (this.shadowRoot?.querySelector('arc-chat-interface') as any)?.reconnect();
      }
      this._fetchAgents();
      this._fetchApprovals();
    }
  };

  private _resolvePlatformId() {
    let id = sessionStorage.getItem('amsha-platform-id');
    if (!id) {
      id = crypto.randomUUID();
      sessionStorage.setItem('amsha-platform-id', id);
    }
    return id;
  }

  private _routeFromHash(hash: string): string {
    const route = hash.split('?')[0] || '#/onboarding';
    return route === '#/mcp/setup' ? '#/mcp' : route;
  }

  private _isMcpSetupHash(hash: string): boolean {
    return hash.split('?')[0] === '#/mcp/setup';
  }

  async connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this._boundHashChange);
    if (this._isMcpSetupHash(window.location.hash)) {
      this._pendingMcpSetupHash = window.location.hash;
    }

    try {
      const status = await this._client!.onboardingStatus();
      if (status.credentialsOk || status.skipOnboarding) {
        this._apiKey = 'authenticated';
        this._client = new ArcClient('/api/v1', this._apiKey);
        this._updateContext();

        const targetHash = this._pendingMcpSetupHash || window.location.hash || '#/chat';
        this._pendingMcpSetupHash = null;
        const targetRoute = this._routeFromHash(targetHash);
        this._route = targetRoute === '#/onboarding' ? '#/chat' : targetRoute;
        if (targetRoute === '#/mcp' && targetHash !== window.location.hash) {
          window.location.hash = targetHash;
        } else if (this._route === '#/chat' && targetRoute === '#/onboarding') {
          window.location.hash = this._route;
        }

        this._fetchAgents();
        this._fetchApprovals();
        this._startSse();
      } else {
        this._apiKey = '';
        this._updateContext();
        window.location.hash = '#/onboarding';
      }
    } catch (err) {
      this._apiKey = '';
      this._updateContext();
      window.location.hash = '#/onboarding';
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('hashchange', this._boundHashChange);
    window.removeEventListener('session-ready', this._boundSessionReady);
    this._sseManager?.disconnect();
  }

  // ─── SSE ─────────────────────────────────────────────────────────────────



  // ─── Lifecycle / Auth ────────────────────────────────────────────────────

  private _onApiKeySet(e: CustomEvent<{ key: string }>) {
    this._apiKey = e.detail.key ? 'authenticated' : '';
    this._client = new ArcClient('/api/v1', this._apiKey);
    this._updateContext();

    // Close stale SSE connections bound to the previous key.
    // They will be re-opened with the new key once session-ready fires.
    this._sseManager?.disconnect();

    if (this._apiKey) {
      const setupHash = this._pendingMcpSetupHash;
      this._pendingMcpSetupHash = null;
      if (setupHash) {
        window.location.hash = setupHash;
      } else {
        window.location.hash = '#/chat';
      }
      this._fetchAgents();
      this._fetchApprovals();
      this._startSse();
    } else {
      window.location.hash = '#/onboarding';
    }
  }

  private _handleUnauthorized() {
    console.warn('[Amsha Agent] Stale API key detected (401). Redirecting to onboarding...');
    this._apiKey = '';
    this._updateContext();
    window.location.hash = '#/onboarding';
  }

  private _onSessionReady(e: CustomEvent<{ sessionId: string; platformId: string }>) {
    // Only update if server assigned a different ID (fallback UUID case)
    if (this._sessionId !== e.detail.sessionId) {
        this._sessionId = e.detail.sessionId;
        sessionStorage.setItem('amsha-chat-session', this._sessionId);
        this._updateContext();
    }

    if (!this._sessionId) return;
    this._startSse(); // Re-start all SSEs with new session context
  }

  // SSE event handlers
  private _handleSseEvent(event: any) {
    if (event.sessionId && this._sessionId && event.sessionId !== this._sessionId) {
      return;
    }

    const eventType = event.eventType ?? event.type;
    if (eventType === 'status_change') {
      this._fetchAgents();
      this._fetchApprovals();
    } else if (eventType === 'progress') {
      this._publishProgress(event.agentName || 'Working', event.payload || '');
    } else if (
      eventType === 'AWAITING_APPROVAL' ||
      eventType === 'APPROVED' ||
      eventType === 'DENIED' ||
      eventType === 'RESUMED'
    ) {
      this._handleHitlEvent(event);
    } else if (eventType === 'agent_status') {
      this._handleAgentEvent(event);
    }
  }

  private _publishProgress(phase: string, detail: string) {
    window.dispatchEvent(
      new CustomEvent('amsha-agent-progress', {
        detail: {
          phase,
          detail
        }
      })
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
      // APPROVED, DENIED, RESUMED - remove from pending list
      this._pendingApprovals = this._pendingApprovals.filter(
        (a) =>
          !(
            a.taskId === event.sessionId &&
            (a.toolName === event.toolName || !event.toolName)
          )
      );
    }

    this._publishHitlProgress(event);
  }

  private _publishHitlProgress(event: any) {
    switch (event?.type) {
      case 'AWAITING_APPROVAL':
        this._publishProgress('Waiting for approval', 'Approve or deny the tool request');
        break;
      case 'APPROVED':
        this._publishProgress('Approval granted', 'Tool request approved. Resuming work.');
        break;
      case 'DENIED':
        this._publishProgress('Approval denied', 'Tool request denied.');
        break;
      case 'RESUMED':
        this._publishProgress('Resuming work', 'Picking up where the agent left off.');
        break;
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
      const result = await this._client.getHitl();
      this._pendingApprovals = result.items;
    } catch (err: any) {
      if (err.message?.includes('401')) {
        this._handleUnauthorized();
      }
    }
  }

  // ─── Chat SSE (existing) ─────────────────────────────────────────────────

  private _startSse() {
    this._sseManager?.disconnect();
    if (!this._sessionId) return;
    this._sseManager = new SseManager(
      `/api/v1/chat/stream?sessionId=${encodeURIComponent(this._sessionId)}`,
      this._apiKey,
      (event: AgentEvent) => this._handleSseEvent(event),
      (status) => {
        const wasDown = this._sseStatus !== 'connected';
        this._sseStatus = status;
        this._updateContext();
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
      <arc-chat-interface class="content-area" style="display: ${this._route === '#/chat' ? 'flex' : 'none'}"></arc-chat-interface>
      ${this._route === '#/mcp' ? html`
        <arc-mcp-panel class="content-area"></arc-mcp-panel>
      ` : nothing}
      ${this._route === '#/agents' ? html`
        <arc-agent-status class="content-area" .agents="${this._agents}"></arc-agent-status>
      ` : nothing}
      ${this._route === '#/hitl' ? html`
        <arc-hitl-panel class="content-area" .approvals="${this._pendingApprovals}"></arc-hitl-panel>
      ` : nothing}
      ${this._route === '#/skills' ? html`
        <arc-skills-panel class="content-area"></arc-skills-panel>
      ` : nothing}
      ${this._route === '#/scheduler' ? html`
        <arc-scheduler-panel class="content-area"></arc-scheduler-panel>
      ` : nothing}
      ${this._route === '#/audit' ? html`
        <arc-audit-panel class="content-area"></arc-audit-panel>
      ` : nothing}
      ${this._route === '#/settings' ? html`
        <arc-settings class="content-area"></arc-settings>
      ` : nothing}
    `;
  }

  // ─── Render ──────────────────────────────────────────────────────────────

  render() {
    return html`
      ${this._apiKey
        ? html`
            <arc-nav .route="${this._route}" ?open="${this._isNavOpen}"></arc-nav>
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
                        : ROUTES.find(r => r.hash === this._route)?.label || ''}
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
                    ${this._sseStatus === 'connected' ? 'Connected to Amsha Agent' : 'Amsha Agent (Offline)'}
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
