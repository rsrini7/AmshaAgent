import { LitElement, css, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { AgentStatusDto } from '../api/generated/models';
import { PendingApprovalDto } from '../api/types';
import {
  ArcApiError,
  ArcClient,
  clearPersistedOwnerApiKey,
  readPersistedOwnerApiKey,
} from '../api/client';
import {
  AGENT_SESSION_STATUS,
  AGENT_SESSION_STATUS_EVENT,
  SseManager,
  SsePayload,
  SseStatus,
  TERMINAL_AGENT_SESSION_STATUSES,
  isAgentStatusPayload,
  AgentSessionStatusNotice,
} from '../api/sse';
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
import './arc-db-console';
import './arc-model-arena';
import './arc-classifier-panel';
import './arc-health-dashboard';
import './arc-settings';
import './arc-theme-toggle';
import './arc-flow-canvas';
import { ResizeController } from '../composables/resize-controller';
import {
  AgentTraceEvent,
  isAgentTraceEvent,
  ExecutionRunSummaryDto,
  RunEvidenceDto,
  toAgentTraceEvent,
} from '../types/trace';

@customElement('arc-shell')
export class ArcShell extends LitElement {
  @provide({ context: arcContext })
  @state()
  // @ts-ignore - Used by Lit @provide decorator to supply context to children
  private _appContext: ArcAppContext = {
    apiKey: '',
    sessionId: '',
    platformId: '',
    workspaceId: typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('amsha-workspace-id') || undefined : undefined,
    sseStatus: SseStatus.DISCONNECTED
  };

  @state() private _apiKey = '';
  @state() private _authFailureNotice = '';
  @state() private _sessionId = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('amsha-chat-session') || '' : '';
  @state() private _platformId = ''; // Stable ID for WebSocket
  @state() private _workspaceId = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('amsha-workspace-id') || '' : '';
  @state() private _agents: AgentStatusDto[] = [];
  @state() private _pendingApprovals: PendingApprovalDto[] = [];
  @state() private _sseStatus: SseStatus = SseStatus.DISCONNECTED;
  @state() private _route =
    typeof window !== 'undefined' ? this._routeFromHash(window.location?.hash || '#/onboarding') : '#/chat';
  @state() private _isNavOpen = false;
  @state() private _chatWsStatus: string = 'closed';
  @state() private _dockWidth = 400;
  @state() private _showConveyor = false;
  @state() private _traceEvents: AgentTraceEvent[] = [];
  @state() private _currentRunId = '';
  @state() private _isReplayMode = false;
  @state() private _replayRunId = '';
  @state() private _replayTraceEvents: AgentTraceEvent[] = [];
  @state() private _replayEvidence: RunEvidenceDto | null = null;
  @state() private _replayRuns: ExecutionRunSummaryDto[] = [];
  /** Live trace events that arrived while replay was active, pending merge on exit. */
  @state() private _replayBufferedEvents: AgentTraceEvent[] = [];

  private _resizeController = new ResizeController({
    defaultWidth: 400,
    minWidth: 280,
    maxWidth: 800,
    onResize: (w) => {
      this._dockWidth = w;
    },
  });

  private _client: ArcClient | null = null;
  private _sseManager: SseManager | null = null;
  private _authRecoveryStarted = false;
  private _boundSessionReady = ((e: Event) => this._onSessionReady(e as CustomEvent)) as EventListener;
  private _boundWsStatusChange = (e: CustomEvent<{ status: string }>) => {
    this._chatWsStatus = e.detail.status;
  };
  private _pendingMcpSetupHash: string | null = null;

  static styles = css`
    :host { 
      display: flex; height: 100vh; overflow: hidden; 
      background: var(--bg-base); 
      color: var(--text-primary);
    }
    .cockpit-container {
      display: flex;
      flex: 1;
      min-height: 0;
      overflow: hidden;
      position: relative;
    }
    .cockpit-canvas-area {
      flex: 1;
      min-width: 0;
      min-height: 0;
      overflow: auto;
      display: flex;
      flex-direction: column;
      scrollbar-width: thin;
      scrollbar-color: var(--border) transparent;
    }

    .cockpit-canvas-area[hidden],
    .cockpit-splitter[hidden] {
      display: none;
    }
    .cockpit-canvas-area::-webkit-scrollbar { width: 6px; height: 6px; }
    .cockpit-canvas-area::-webkit-scrollbar-track { background: transparent; }
    .cockpit-canvas-area::-webkit-scrollbar-thumb {
      background: var(--border);
      border-radius: 999px;
    }
    .cockpit-canvas-area::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }
    .cockpit-splitter {
      width: 6px;
      cursor: col-resize;
      background: var(--border);
      transition: background-color 0.2s;
      flex-shrink: 0;
      position: relative;
      user-select: none;
    }
    .cockpit-splitter:hover, .cockpit-splitter:focus {
      background: var(--accent-secondary);
      outline: none;
    }
    .cockpit-dock-area {
      min-width: 280px;
      max-width: 800px;
      display: flex;
      flex-direction: column;
      flex-shrink: 0;
      min-height: 0;
      border-left: 1px solid var(--border);
      scrollbar-width: thin;
      scrollbar-color: var(--border) transparent;
    }

    .cockpit-dock-area.chat-only {
      flex: 1;
      min-width: 0;
      max-width: none;
      border-left: 0;
    }
    .cockpit-dock-area::-webkit-scrollbar { width: 6px; height: 6px; }
    .cockpit-dock-area::-webkit-scrollbar-track { background: transparent; }
    .cockpit-dock-area::-webkit-scrollbar-thumb {
      background: var(--border);
      border-radius: 999px;
    }
    .cockpit-dock-area::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }
    @media (max-width: 800px) {
      .cockpit-container {
        flex-direction: column;
      }
      .cockpit-splitter {
        display: none;
      }
      .cockpit-dock-area {
        width: 100% !important;
        max-width: 100%;
        flex: 1;
      }
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

    .layout-toggle {
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 0.5rem 0.75rem;
      background: var(--bg-elevated);
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      cursor: pointer;
      white-space: nowrap;
    }

    .layout-toggle:hover {
      border-color: var(--accent-secondary);
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
    if (!this._workspaceId && typeof sessionStorage !== 'undefined') {
      try {
        this._workspaceId = sessionStorage.getItem('amsha-workspace-id') || '';
      } catch (_) {}
    }
    this._appContext = {
      apiKey: this._apiKey,
      sessionId: this._sessionId,
      platformId: this._platformId,
      workspaceId: this._workspaceId || undefined,
      sseStatus: this._sseStatus
    };
  }

  constructor() {
    super();
    this.addEventListener('api-key-set', this._onApiKeySet as EventListener);
    this.addEventListener('workspace-set', this._boundWorkspaceSet as EventListener);
    this.addEventListener('approval-action', this._onApprovalAction as EventListener);
    this.addEventListener('request-approvals-refresh', () => this._fetchApprovals());
    window.addEventListener('ws-status-change', this._boundWsStatusChange as EventListener);
    window.addEventListener('session-ready', this._boundSessionReady);
    window.addEventListener('workspace-set', this._boundWorkspaceSet as EventListener);

    this._platformId = this._resolvePlatformId();
    const persistedKey = readPersistedOwnerApiKey();
    if (persistedKey) {
      this._apiKey = persistedKey;
    }
    this._client = new ArcClient('/api/v1', this._apiKey);
    this._updateContext();
    if (this._apiKey) {
      this._fetchActiveWorkspace();
    }
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
        this._handleReconnectChat();
      }
      this._fetchAgents();
      this._fetchApprovals();
    }
  };

  private _handleReconnectChat(): void {
    this._chatWsStatus = 'connecting';
    const chat = this.shadowRoot?.querySelector('arc-chat-interface') as any;
    if (chat?.reconnect) {
      chat.reconnect();
    }
  }

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

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this._boundHashChange);
    if (this._isMcpSetupHash(window.location.hash)) {
      this._pendingMcpSetupHash = window.location.hash;
    }
    // The onboarding component owns status/bootstrap initialization. A second
    // shell-level probe races first-owner claim and duplicates expected failures.
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('hashchange', this._boundHashChange);
    window.removeEventListener('session-ready', this._boundSessionReady);
    window.removeEventListener('workspace-set', this._boundWorkspaceSet as EventListener);
    this.removeEventListener('workspace-set', this._boundWorkspaceSet as EventListener);
    window.removeEventListener('ws-status-change', this._boundWsStatusChange as EventListener);
    this._sseManager?.disconnect();
  }

  // ─── SSE ─────────────────────────────────────────────────────────────────



  // ─── Lifecycle / Auth ────────────────────────────────────────────────────

  private _boundWorkspaceSet = (e: Event) => {
    const customEvent = e as CustomEvent<{ workspaceId: string }>;
    const wsId = customEvent.detail?.workspaceId?.trim();
    if (wsId) {
      this._setWorkspaceId(wsId);
    } else {
      this._clearWorkspaceId();
    }
  };

  private _setWorkspaceId(wsId: string) {
    const trimmed = wsId ? wsId.trim() : '';
    if (!trimmed || this._workspaceId === trimmed) return;
    this._workspaceId = trimmed;
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.setItem('amsha-workspace-id', trimmed);
      }
    } catch (_) {}
    this._updateContext();
  }

  private _clearWorkspaceId() {
    this._workspaceId = '';
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.removeItem('amsha-workspace-id');
      }
    } catch (_) {}
    this._updateContext();
  }

  private async _fetchActiveWorkspace() {
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      const apiKey =
        this._apiKey && this._apiKey !== 'authenticated' ? this._apiKey : readPersistedOwnerApiKey();
      if (apiKey) {
        headers['X-AMSHA-API-KEY'] = apiKey;
      }
      const res = await fetch('/api/v1/identity/me', {
        headers,
        credentials: 'include'
      });
      if (!res.ok) {
        if (res.status === 401) {
          this._handleUnauthorized();
          return;
        }
        if (res.status === 403) {
          this._clearWorkspaceId();
        }
        return;
      }
      const data = await res.json();
      const activeMemberships = Array.isArray(data.memberships)
        ? data.memberships.filter(
            (membership: any) =>
              membership.status?.toUpperCase() === 'ACTIVE' &&
              typeof membership.workspaceId === 'string' &&
              membership.workspaceId.trim(),
          )
        : [];
      const currentWorkspace = this._workspaceId?.trim();
      const selectedWorkspace = activeMemberships.find(
        (membership: any) => membership.workspaceId.trim() === currentWorkspace,
      );
      const ownerWorkspace = activeMemberships.find(
        (membership: any) => membership.role?.toUpperCase() === 'OWNER',
      );
      const active = selectedWorkspace || ownerWorkspace || activeMemberships[0];
      const wsId = active?.workspaceId?.trim();
      if (wsId) {
        this._setWorkspaceId(wsId);
      } else {
        this._clearWorkspaceId();
      }
    } catch (_) {}
  }

  private _onApiKeySet(e: CustomEvent<{ key: string }>) {
    const rawKey = e.detail?.key?.trim();
    this._apiKey = rawKey || readPersistedOwnerApiKey() || '';
    this._authFailureNotice = '';
    this._client = new ArcClient('/api/v1', this._apiKey);
    this._authRecoveryStarted = false;
    this._sseStatus = SseStatus.DISCONNECTED;
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
      this._fetchActiveWorkspace();
      this._fetchAgents();
      this._fetchApprovals();
      this._startSse();
    } else {
      window.location.hash = '#/onboarding';
    }
  }

  private _handleUnauthorized() {
    if (this._authRecoveryStarted) return;
    this._authRecoveryStarted = true;
    console.warn('[Amsha Agent] Authentication rejected (401). Redirecting to onboarding...');
    this._authFailureNotice = 'Authentication was rejected. Sign in or enter a current API key to continue.';
    this._sseManager?.disconnect();
    clearPersistedOwnerApiKey();
    this._apiKey = '';
    this._client = new ArcClient('/api/v1', undefined, false);
    this._clearWorkspaceId();
    this._sseStatus = SseStatus.UNAUTHORIZED;
    this._updateContext();
    window.location.hash = '#/onboarding';
  }

  private _isUnauthorized(error: unknown): boolean {
    return error instanceof ArcApiError && error.status === 401;
  }

  private _onSessionReady(e: CustomEvent<{ sessionId: string; platformId: string; workspaceId?: string }>) {
    const sessionId = e.detail?.sessionId?.trim();
    if (!sessionId) {
      return;
    }
    const sessionChanged = this._sessionId !== sessionId;
    this._sessionId = sessionId;
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.setItem('amsha-chat-session', sessionId);
      }
    } catch (_) {}
    const platformId = e.detail?.platformId?.trim();
    if (platformId && platformId !== this._platformId) {
      this._platformId = platformId;
      try {
        if (typeof sessionStorage !== 'undefined') {
          sessionStorage.setItem('amsha-platform-id', platformId);
        }
      } catch (_) {}
    }
    const workspaceId = e.detail?.workspaceId?.trim();
    if (workspaceId) {
      this._setWorkspaceId(workspaceId);
    } else {
      void this._fetchActiveWorkspace();
    }
    this._updateContext();
    if (sessionChanged) {
      this._traceEvents = [];
      this._currentRunId = '';
      this._isReplayMode = false;
      this._replayTraceEvents = [];
      this._replayEvidence = null;
      this._sseManager?.resetDedup();
      this._fetchSessionRuns(sessionId);
    }
    this._startSse();
  }

  // SSE event handlers
  private _handleSseEvent(event: SsePayload) {
    const rawType = (event as any).eventType ?? (event as any).type ?? '';
    const isHitlEvent =
      rawType === 'AWAITING_APPROVAL' ||
      rawType === 'APPROVED' ||
      rawType === 'DENIED' ||
      rawType === 'RESUMED' ||
      rawType === 'hitl_approval' ||
      rawType === 'hitl_resolved';

    if (
      event.sessionId &&
      this._sessionId &&
      event.sessionId !== this._sessionId &&
      !event.sessionId.startsWith(this._sessionId + ':delegate:')
    ) {
      if (rawType !== 'status_change' && !isHitlEvent) {
        return;
      }
    }

    if (isAgentTraceEvent(event)) {
      if (this._isReplayMode) {
        // While replay is active, live events do not overwrite or mutate the active replay view.
        // Buffer them so they can be merged back when replay ends (otherwise they are lost).
        this._replayBufferedEvents = [...this._replayBufferedEvents, event];
        return;
      }
      if (event.runId && this._currentRunId && event.runId !== this._currentRunId) {
        this._sseManager?.resetDedup();
        this._traceEvents = [];
      }
      if (event.runId) {
        this._currentRunId = event.runId;
      }
      this._traceEvents = [...this._traceEvents, event];
      return;
    }

    if (isAgentStatusPayload(event)) {
      this._handleAgentEvent({ agentRole: event.agentRole, status: event.status });
      const status = event.status.trim().toUpperCase();
    if (
      event.sessionId === this._sessionId &&
      (status === AGENT_SESSION_STATUS.AWAITING_RETRY || TERMINAL_AGENT_SESSION_STATUSES.has(status))
    ) {
      if (TERMINAL_AGENT_SESSION_STATUSES.has(status)) {
        void this._fetchSessionRuns(event.sessionId);
      }
      window.dispatchEvent(
        new CustomEvent<AgentSessionStatusNotice>(AGENT_SESSION_STATUS_EVENT, {
          detail: {
            sessionId: event.sessionId,
            status,
            timestamp: event.timestamp,
            retryAfterMs: event.retryAfterMs,
          },
        })
      );
    }
      return;
    }

    if (rawType === 'status_change') {
      this._fetchAgents();
      this._fetchApprovals();
    } else if (rawType === 'progress') {
      this._publishProgress(event.agentName || 'Working', event.payload || '', event.sessionId);
    } else if (isHitlEvent) {
      this._handleHitlEvent(event);
      this._fetchApprovals();
  } else if (rawType === 'agent_status' && !isAgentStatusPayload(event)) {
      this._handleAgentEvent(event);
    }
  }

  private get _activeRunEvents(): AgentTraceEvent[] {
    if (this._isReplayMode) {
      return this._replayTraceEvents;
    }
    if (!this._currentRunId) {
      return this._traceEvents;
    }
    return this._traceEvents.filter((e) => !e.runId || e.runId === this._currentRunId);
  }

  async _fetchSessionRuns(sessionId: string) {
    if (!sessionId) return;
    try {
      const client = this._client ?? new ArcClient('/api/v1', this._apiKey);
      const runs = await client.getSessionRuns(sessionId, 50);
      if (Array.isArray(runs)) {
        this._replayRuns = runs;
      }
    } catch {
      // Best-effort
    }
  }

  async _startReplay(runId: string) {
    if (!runId) return;
    this._replayEvidence = null;
    try {
      const client = this._client ?? new ArcClient('/api/v1', this._apiKey);
      const [traces, evidence] = await Promise.all([
        client.getRunTrace(runId, 500),
        client.getRunEvidence(runId).catch(() => null),
      ]);
      if (Array.isArray(traces)) {
        this._replayTraceEvents = traces.map(toAgentTraceEvent);
        this._replayRunId = runId;
        this._replayEvidence = evidence;
        this._isReplayMode = true;
      }
    } catch {
      // Best-effort
    }
  }

  _exitReplay() {
    this._isReplayMode = false;
    this._replayTraceEvents = [];
    this._replayEvidence = null;
    this._replayRunId = '';
    // Merge any live trace events that arrived during replay back into the live buffer so
    // they are not permanently lost.
    if (this._replayBufferedEvents.length > 0) {
      this._traceEvents = [...this._traceEvents, ...this._replayBufferedEvents];
      this._replayBufferedEvents = [];
    }
  }

  private get _activeParadigm(): string {
    // Strictly read authoritative backend paradigm metadata emitted on trace events (e.g. TURN_START, ROUTE)
    // Never guess or infer from arbitrary stage occurrences like llm_reasoning
    const events = this._activeRunEvents;
    for (let i = events.length - 1; i >= 0; i--) {
      const p = events[i].paradigm;
      if (p && typeof p === 'string' && p.trim().length > 0) {
        return p.trim().toUpperCase();
      }
    }
    return '';
  }

  private _publishProgress(phase: string, detail: string, sessionId?: string) {
    window.dispatchEvent(
      new CustomEvent('amsha-agent-progress', {
        detail: {
          phase,
          detail,
          ...(sessionId ? { sessionId } : {})
        }
      })
    );
  }

    // ─── SSE Event Handlers ──────────────────────────────────────────────────

  private _handleHitlEvent(event: any) {
    // The SSE event only identifies the session. The actionable task ID and
    // authoritative tool parameters come from GET /hitl, so do not fabricate a
    // PendingApprovalDto from the session ID here.
    this._publishHitlProgress(event);
  }

  private _publishHitlProgress(event: any) {
    const type = event?.type ?? event?.eventType;
    switch (type) {
      case 'AWAITING_APPROVAL':
        this._publishProgress('Waiting for approval', 'Approve or deny the tool request', event.sessionId);
        break;
      case 'APPROVED':
        this._publishProgress('Approval granted', 'Tool request approved. Resuming work.', event.sessionId);
        break;
      case 'DENIED':
        this._publishProgress('Approval denied', 'Tool request denied.', event.sessionId);
        break;
      case 'RESUMED':
        this._publishProgress('Resuming work', 'Picking up where the agent left off.', event.sessionId);
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
    } catch (error) {
      if (this._isUnauthorized(error)) this._handleUnauthorized();
    }
  }

  private async _fetchApprovals() {
    if (!this._client) return;
    try {
      const result = await this._client.getHitl();
      this._pendingApprovals = result.items;
    } catch (error) {
      if (this._isUnauthorized(error)) this._handleUnauthorized();
    }
  }

  // ─── Chat SSE (existing) ─────────────────────────────────────────────────

  private _startSse() {
    this._sseManager?.disconnect();
    if (!this._sessionId) return;
    this._sseManager = new SseManager(
      `/api/v1/chat/stream?sessionId=${encodeURIComponent(this._sessionId)}`,
      this._apiKey,
      (event: SsePayload) => this._handleSseEvent(event),
      (status) => {
        const wasDown = this._sseStatus !== SseStatus.CONNECTED;
        this._sseStatus = status;
        this._updateContext();
        if (status === SseStatus.UNAUTHORIZED) {
          this._handleUnauthorized();
          return;
        }
        if (wasDown && status === SseStatus.CONNECTED) {
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
      <div class="cockpit-container" style="display: ${this._route === '#/chat' ? 'flex' : 'none'}">
        <div class="cockpit-canvas-area" role="region" aria-label="Visual Cockpit Canvas" ?hidden=${!this._showConveyor}>
          <arc-flow-canvas
            .events=${this._activeRunEvents}
            .runId=${this._isReplayMode ? this._replayRunId : this._currentRunId}
            .paradigm=${this._activeParadigm}
            .isReplayMode=${this._isReplayMode}
            .replayRuns=${this._replayRuns}
            .runEvidence=${this._isReplayMode ? this._replayEvidence : null}
            @start-replay=${(e: CustomEvent) => this._startReplay(e.detail.runId)}
            @exit-replay=${() => this._exitReplay()}
            @refresh-runs=${() => this._fetchSessionRuns(this._sessionId)}
          ></arc-flow-canvas>
        </div>
        <div
          class="cockpit-splitter"
          ?hidden=${!this._showConveyor}
          role="separator"
          tabindex="0"
          aria-orientation="vertical"
          aria-valuenow="${Math.round(this._dockWidth)}"
          aria-valuemin="280"
          aria-valuemax="800"
          aria-label="Resize chat dock"
          @pointerdown=${(e: PointerEvent) => this._onSplitterPointerDown(e)}
          @keydown=${(e: KeyboardEvent) => this._onSplitterKeyDown(e)}
        ></div>
        <div class="cockpit-dock-area ${this._showConveyor ? '' : 'chat-only'}" style="width: ${this._showConveyor ? `${this._dockWidth}px` : '100%'};">
          <arc-chat-interface
            class="content-area"
            style="display: flex; flex: 1;"
            .traceEvents=${this._activeRunEvents}
            .pendingApprovals=${this._pendingApprovals}
          ></arc-chat-interface>
        </div>
      </div>
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
      ${this._route === '#/db' ? html`
        <arc-db-console class="content-area"></arc-db-console>
      ` : nothing}
      ${this._route === '#/arena' ? html`
        <arc-model-arena class="content-area"></arc-model-arena>
      ` : nothing}
      ${this._route === '#/classifiers' ? html`
        <arc-classifier-panel class="content-area"></arc-classifier-panel>
      ` : nothing}
      ${this._route === '#/health' ? html`
        <arc-health-dashboard class="content-area"></arc-health-dashboard>
      ` : nothing}
      ${this._route === '#/settings' ? html`
        <arc-settings class="content-area"></arc-settings>
      ` : nothing}
    `;
  }

  private _onSplitterPointerDown(e: PointerEvent) {
    this._resizeController.handlePointerDown(e);
    const onPointerMove = (ev: PointerEvent) => {
      this._resizeController.handlePointerMove(ev);
      this._dockWidth = this._resizeController.width;
    };
    const onPointerUp = () => {
      this._resizeController.handlePointerUp();
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
    };
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
  }

  private _onSplitterKeyDown(e: KeyboardEvent) {
    if (this._resizeController.handleKeyDown(e)) {
      this._dockWidth = this._resizeController.width;
    }
  }

  // ─── Render ──────────────────────────────────────────────────────────────

  render() {
    const sseStatusColor = this._sseStatus === SseStatus.CONNECTED
      ? 'var(--accent-secondary)'
      : this._sseStatus === SseStatus.ERROR || this._sseStatus === SseStatus.UNAUTHORIZED
        ? '#ef4444'
        : 'var(--text-muted)';
    const sseStatusLabel = this._sseStatus === SseStatus.CONNECTED
      ? 'Live updates connected'
      : this._sseStatus === SseStatus.UNAUTHORIZED
        ? 'Sign in to restore live updates'
        : this._sseStatus === SseStatus.ERROR
          ? 'Live updates unavailable'
          : 'Live updates disconnected';
    return html`
      ${this._apiKey
        ? html`
            <arc-nav .route="${this._route}" ?open="${this._isNavOpen}"></arc-nav>
            ${this._isNavOpen ? html`<div class="nav-overlay" @click="${() => this._isNavOpen = false}"></div>` : ''}
          `
        : ''}
      <main>
        ${this._authFailureNotice
          ? html`<div role="alert" style="margin: 12px 16px; padding: 12px 16px; border: 1px solid #ef444466; border-radius: 8px; color: #ef4444; background: #ef44441a;">
            ${this._authFailureNotice}
          </div>`
          : nothing}
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
                          <button @click="${() => (this.shadowRoot?.querySelector('arc-chat-interface') as any)?.startNewSession(crypto.randomUUID())}" 
                                  style="background:rgba(0,212,170,0.1); border:1px solid rgba(0,212,170,0.3); color:var(--accent-secondary); font-size:10px; font-weight:600; padding:2px 8px; border-radius:4px; cursor:pointer; margin-left:4px;"
                                  title="Start a new chat session">
                            + New Session
                          </button>
                          ${this._chatWsStatus === 'error' || this._chatWsStatus === 'closed' ? html`
                             <button @click="${() => this._handleReconnectChat()}"
                                     style="background:transparent; border:1px solid var(--border); color:var(--text-muted); font-size:10px; padding:2px 8px; border-radius:4px; cursor:pointer; margin-left:4px;"
                                     title="Reconnect WebSocket to Amsha Agent">
                               Reconnect
                             </button>
                          ` : nothing}
                          ${this._chatWsStatus === 'connecting' ? html`
                             <span style="font-size:10px; color:var(--text-muted); margin-left:4px; font-style:italic;">Connecting…</span>
                          ` : nothing}
                        `
                        : ROUTES.find(r => r.hash === this._route)?.label || ''}
                    </div>
                </div>
                <div class="topbar-right">
                  ${this._route === '#/chat' ? html`
                    <button
                      class="layout-toggle"
                      aria-label="${this._showConveyor ? 'Hide execution conveyor and expand chat' : 'Show execution conveyor'}"
                      aria-pressed="${!this._showConveyor}"
                      title="${this._showConveyor ? 'Expand chat to fill the center' : 'Show the execution conveyor'}"
                      @click=${() => (this._showConveyor = !this._showConveyor)}
                    >${this._showConveyor ? 'Chat only' : 'Show Conveyor'}</button>
                  ` : nothing}
                  <div class="connection-pill">
                    <div
                      class="status-dot"
                      style="background: ${sseStatusColor};
                        box-shadow: 0 0 0 6px color-mix(in srgb, ${sseStatusColor} 18%, transparent);"
                    ></div>
                    ${sseStatusLabel}
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
