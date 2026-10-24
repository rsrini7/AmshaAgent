import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcApiError } from '../api/client';
import { TRACE_EVENT_TYPE, type ExecutionTraceDto } from '../types/trace';
import { ArcShell } from './arc-shell';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

function createShell() {
  const windowMock = {
    location: { hash: '#/chat' },
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
    setTimeout: vi.fn(() => 1),
    clearTimeout: vi.fn(),
  };
  vi.stubGlobal('sessionStorage', new MemoryStorage());
  vi.stubGlobal('localStorage', new MemoryStorage());
  vi.stubGlobal('window', windowMock);
  return { shell: new ArcShell(), windowMock };
}

function traceEvent(overrides: Record<string, unknown> = {}) {
  return {
    eventType: TRACE_EVENT_TYPE,
    eventId: 'event-1',
    runId: 'run-1',
    sessionId: 'session-1',
    turnNumber: 1,
    sequence: 1,
    stage: 'route',
    station: null,
    status: 'completed',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-10-24T00:00:00.000Z',
    paradigm: ' jev ',
    version: 1,
    ...overrides,
  };
}

describe('ArcShell routing and live cockpit behavior', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('normalizes routes and keeps workspace context synchronized with storage', () => {
    const { shell } = createShell();
    const component = shell as any;

    expect(component._routeFromHash('')).toBe('#/onboarding');
    expect(component._routeFromHash('#/mcp/setup?token=abc')).toBe('#/mcp');
    expect(component._isMcpSetupHash('#/mcp/setup?token=abc')).toBe(true);
    expect(component._isMcpSetupHash('#/mcp')).toBe(false);

    component._setWorkspaceId(' workspace-1 ');
    expect(sessionStorage.getItem('amsha-workspace-id')).toBe('workspace-1');
    expect(component._appContext.workspaceId).toBe('workspace-1');
    component._setWorkspaceId('   ');
    expect(component._appContext.workspaceId).toBe('workspace-1');
    component._clearWorkspaceId();
    expect(sessionStorage.getItem('amsha-workspace-id')).toBeNull();
    expect(component._appContext.workspaceId).toBeUndefined();
  });

  it('publishes progress for approval lifecycle events and updates known agents', () => {
    const { shell, windowMock } = createShell();
    const component = shell as any;
    const eventTypes = ['AWAITING_APPROVAL', 'APPROVED', 'DENIED', 'RESUMED'];

    component._publishProgress('Working', 'Looking up release notes');
    for (const type of eventTypes) {
      component._publishHitlProgress({ type, sessionId: 'session-1' });
    }
    const details = windowMock.dispatchEvent.mock.calls.map(([event]) => (event as CustomEvent).detail);
    expect(details.map((detail) => detail.phase)).toEqual([
      'Working', 'Waiting for approval', 'Approval granted', 'Approval denied', 'Resuming work',
    ]);
    expect(details[0]).not.toHaveProperty('sessionId');
    expect(details[1].sessionId).toBe('session-1');

    component._agents = [{ agentName: 'Researcher', status: 'RUNNING' }];
    component._handleAgentEvent({ agentRole: 'Researcher', status: 'COMPLETED' });
    expect(component._agents[0].status).toBe('COMPLETED');
    const fetchAgents = vi.spyOn(component, '_fetchAgents').mockResolvedValue(undefined);
    component._handleAgentEvent({ agentRole: 'Unknown', status: 'RUNNING' });
    expect(fetchAgents).toHaveBeenCalledOnce();
  });

  it('filters trace events by run, buffers live events during replay, and merges on exit', () => {
    const { shell } = createShell();
    const component = shell as any;
    const resetDedup = vi.fn();
    component._sessionId = 'session-1';
    component._sseManager = { resetDedup };

    component._handleSseEvent(traceEvent());
    expect(component._currentRunId).toBe('run-1');
    expect(component._activeParadigm).toBe('JEV');
    expect(component._activeRunEvents).toHaveLength(1);

    component._handleSseEvent(traceEvent({ eventId: 'event-2', runId: 'run-2', paradigm: ' prompt_directive ' }));
    expect(resetDedup).toHaveBeenCalledOnce();
    expect(component._activeParadigm).toBe('PROMPT_DIRECTIVE');
    expect(component._activeRunEvents.map((event: any) => event.runId)).toEqual(['run-2']);

    component._isReplayMode = true;
    const buffered = traceEvent({ eventId: 'event-3', runId: 'run-2', sequence: 3 });
    component._handleSseEvent(buffered);
    expect(component._replayBufferedEvents).toEqual([buffered]);
    component._exitReplay();
    expect(component._isReplayMode).toBe(false);
    expect(component._replayBufferedEvents).toEqual([]);
    expect(component._traceEvents.at(-1)).toEqual(buffered);
    component._exitReplay();
    expect(component._replayTraceEvents).toEqual([]);
  });

  it('loads run summaries and starts replay from persisted trace DTOs', async () => {
    const { shell } = createShell();
    const component = shell as any;
    const summary = { runId: 'run-1', sessionId: 'session-1' };
    const trace: ExecutionTraceDto = {
      eventId: 'event-1', runId: 'run-1', sessionId: 'session-1', turnNumber: 1, sequence: 1,
      stage: 'route', station: 'station_03_routing', status: 'completed', riskLevel: null,
      detail: null, promptTokens: 3, completionTokens: 4, durationMs: 9, iteration: 1,
      attempt: 1, timestamp: '2026-10-24T00:00:00.000Z', paradigm: 'jev', version: 1,
      createdAt: '2026-10-24T00:00:00.000Z',
    };
    const client = {
      getSessionRuns: vi.fn().mockResolvedValue([summary]),
      getRunTrace: vi.fn().mockResolvedValue([trace]),
    };
    component._client = client;

    await component._fetchSessionRuns('session-1');
    expect(component._replayRuns).toEqual([summary]);
    await component._fetchSessionRuns('');
    expect(client.getSessionRuns).toHaveBeenCalledOnce();

    await component._startReplay('run-1');
    expect(component._replayTraceEvents[0]).toMatchObject({ eventId: 'event-1', stage: 'route', metrics: { promptTokens: 3 } });
    expect(component._replayRunId).toBe('run-1');
    expect(component._isReplayMode).toBe(true);
    await component._startReplay('');
    expect(client.getRunTrace).toHaveBeenCalledOnce();

    client.getRunTrace.mockRejectedValueOnce(new Error('unavailable'));
    await expect(component._startReplay('missing-run')).resolves.toBeUndefined();
  });

  it('covers authenticated route templates and splitter keyboard and pointer controls', () => {
    const { shell, windowMock } = createShell();
    const component = shell as any;

    component._apiKey = '';
    expect(component._renderViews()).toBeDefined();
    expect(windowMock.setTimeout).toHaveBeenCalledOnce();

    component._apiKey = 'owner-key';
    for (const route of ['#/chat', '#/mcp', '#/agents', '#/hitl', '#/skills', '#/scheduler', '#/audit', '#/db', '#/arena', '#/classifiers', '#/health', '#/settings']) {
      component._route = route;
      expect(component._renderViews()).toBeDefined();
    }
    expect(component.render()).toBeDefined();

    component._resizeController.setWidth(400);
    const preventDefault = vi.fn();
    component._onSplitterKeyDown({ key: 'ArrowLeft', preventDefault } as unknown as KeyboardEvent);
    expect(component._dockWidth).toBe(416);
    expect(preventDefault).toHaveBeenCalledOnce();

    component._onSplitterPointerDown({ clientX: 500 } as PointerEvent);
    const moveListener = windowMock.addEventListener.mock.calls.find(([event]) => event === 'pointermove')?.[1] as EventListener;
    const upListener = windowMock.addEventListener.mock.calls.find(([event]) => event === 'pointerup')?.[1] as EventListener;
    moveListener({ clientX: 470 } as PointerEvent);
    expect(component._dockWidth).toBe(446);
    upListener(new Event('pointerup'));
    expect(windowMock.removeEventListener).toHaveBeenCalledWith('pointermove', moveListener);
    expect(windowMock.removeEventListener).toHaveBeenCalledWith('pointerup', upListener);
  });

  it('routes a newly provided key to the pending MCP setup route', () => {
    const { shell, windowMock } = createShell();
    const component = shell as any;
    component._pendingMcpSetupHash = '#/mcp/setup?token=one-time';
    vi.spyOn(component, '_fetchActiveWorkspace').mockResolvedValue(undefined);
    vi.spyOn(component, '_fetchAgents').mockResolvedValue(undefined);
    vi.spyOn(component, '_fetchApprovals').mockResolvedValue(undefined);
    vi.spyOn(component, '_startSse').mockImplementation(() => undefined);

    component._onApiKeySet(new CustomEvent('api-key-set', { detail: { key: ' owner-key ' } }));

    expect(component._apiKey).toBe('owner-key');
    expect(component._appContext.apiKey).toBe('owner-key');
    expect(component._pendingMcpSetupHash).toBeNull();
    expect(windowMock.location.hash).toBe('#/mcp/setup?token=one-time');
  });

  it('updates session state, clears old replay data, and scopes shell navigation', () => {
    const { shell, windowMock } = createShell();
    const component = shell as any;
    const sseManager = { resetDedup: vi.fn(), connect: vi.fn(), disconnect: vi.fn() };
    component._sseManager = sseManager;
    component._sessionId = 'old-session';
    component._platformId = 'old-platform';
    component._traceEvents = [traceEvent()];
    component._currentRunId = 'old-run';
    component._isReplayMode = true;
    component._replayTraceEvents = [traceEvent({ eventId: 'replay-event' })];
    const fetchRuns = vi.spyOn(component, '_fetchSessionRuns').mockResolvedValue(undefined);
    const startSse = vi.spyOn(component, '_startSse').mockImplementation(() => undefined);

    component._onSessionReady(new CustomEvent('session-ready', {
      detail: { sessionId: ' new-session ', platformId: ' new-platform ', workspaceId: ' workspace-2 ' },
    }));
    expect(component._sessionId).toBe('new-session');
    expect(component._platformId).toBe('new-platform');
    expect(component._appContext.workspaceId).toBe('workspace-2');
    expect(sessionStorage.getItem('amsha-chat-session')).toBe('new-session');
    expect(component._traceEvents).toEqual([]);
    expect(component._isReplayMode).toBe(false);
    expect(component._replayTraceEvents).toEqual([]);
    expect(fetchRuns).toHaveBeenCalledWith('new-session');
    expect(startSse).toHaveBeenCalledOnce();
    expect(sseManager.resetDedup).toHaveBeenCalledOnce();

    component._onSessionReady(new CustomEvent('session-ready', { detail: { sessionId: '   ' } }));
    expect(component._sessionId).toBe('new-session');
    component._boundWsStatusChange(new CustomEvent('ws-status-change', { detail: { status: 'open' } }));
    expect(component._chatWsStatus).toBe('open');
    component._boundWorkspaceSet(new CustomEvent('workspace-set', { detail: { workspaceId: ' workspace-3 ' } }));
    expect(component._workspaceId).toBe('workspace-3');

    component._apiKey = '';
    windowMock.location.hash = '#/mcp/setup?token=abc';
    component._boundHashChange();
    expect(component._pendingMcpSetupHash).toBe('#/mcp/setup?token=abc');
    expect(windowMock.location.hash).toBe('#/onboarding');
    expect(component._route).toBe('#/mcp');

    component._apiKey = 'owner-key';
    component._route = '#/onboarding';
    windowMock.location.hash = '#/onboarding';
    component._boundHashChange();
    expect(windowMock.location.hash).toBe('#/chat');
    expect(component._route).toBe('#/chat');

    component._route = '#/chat';
    component._chatWsStatus = 'closed';
    component._fetchAgents = vi.fn();
    component._fetchApprovals = vi.fn();
    component._boundHashChange();
    expect(component._chatWsStatus).toBe('connecting');
    expect(component._fetchAgents).toHaveBeenCalledOnce();
    expect(component._fetchApprovals).toHaveBeenCalledOnce();
    expect(sseManager.connect).toHaveBeenCalledOnce();
  });

  it('loads shell data, handles unauthorized API responses, and forwards HITL events', async () => {
    const { shell, windowMock } = createShell();
    const component = shell as any;
    const approvals = { items: [{ taskId: 'task-1' }] };
    component._client = {
      getAgents: vi.fn().mockResolvedValue([{ agentName: 'Researcher', status: 'READY' }]),
      getHitl: vi.fn().mockResolvedValue(approvals),
    };
    await component._fetchAgents();
    await component._fetchApprovals();
    expect(component._agents[0].agentName).toBe('Researcher');
    expect(component._pendingApprovals).toEqual(approvals.items);

    const unauthorized = new ArcApiError(401, 'unauthorized');
    expect(component._isUnauthorized(unauthorized)).toBe(true);
    expect(component._isUnauthorized(new Error('offline'))).toBe(false);
    const handleUnauthorized = vi.spyOn(component, '_handleUnauthorized').mockImplementation(() => undefined);
    component._client.getAgents.mockRejectedValueOnce(unauthorized);
    await component._fetchAgents();
    expect(handleUnauthorized).toHaveBeenCalledOnce();

    const fetchApprovals = vi.spyOn(component, '_fetchApprovals').mockResolvedValue(undefined);
    component._onApprovalAction(new CustomEvent('approval-action', { detail: { taskId: 'task-1', action: 'approved' } }));
    expect(fetchApprovals).toHaveBeenCalledOnce();
    const hitlProgress = vi.spyOn(component, '_publishHitlProgress').mockImplementation(() => undefined);
    component._handleHitlEvent({ type: 'AWAITING_APPROVAL', sessionId: 'session-1' });
    expect(hitlProgress).toHaveBeenCalledWith({ type: 'AWAITING_APPROVAL', sessionId: 'session-1' });

    component._sseManager = { disconnect: vi.fn() };
    component._sessionId = '';
    component._startSse();
    expect(component._sseManager.disconnect).toHaveBeenCalledOnce();
    expect(windowMock.location.hash).not.toBe('#/');
  });
});
