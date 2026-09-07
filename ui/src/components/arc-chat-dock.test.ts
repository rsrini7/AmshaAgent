import { describe, expect, it, vi } from 'vitest';
import { ArcChatInterface } from './arc-chat-interface';
import { ArcHitlPanel } from './arc-hitl-panel';
import { ArcClient } from '../api/client';
import { computeTurnStats } from './arc-run-flow-card';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function makeTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-test-1',
    runId: 'run-dock-1',
    sessionId: 'session-dock-1',
    turnNumber: 1,
    sequence: 0,
    stage: 'turn_start',
    station: 'station_01_inbound',
    status: 'START',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-01T12:00:00.000Z',
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    ...overrides,
  };
}

describe('Chat Dock & HITL Panel integration', () => {
  it('converts chat message to PendingApprovalDto format for arc-hitl-panel', () => {
    const chat = new ArcChatInterface();
    const msg = {
      id: 'm1',
      role: 'assistant' as const,
      text: 'Need approval to run tool',
      timestamp: Date.now(),
      isApprovalPrompt: true,
      approvalData: {
        tool: 'execute_shell',
        params: { command: 'ls -la' },
      },
      targetSessionId: 'sess-target-99',
    };

    const approvals = (chat as any)._getApprovalsForMessage(msg);
    expect(approvals).toHaveLength(1);
    expect(approvals[0].taskId).toBe('sess-target-99');
    expect(approvals[0].toolName).toBe('execute_shell');
    expect(approvals[0].params).toEqual({ command: 'ls -la' });
    expect(approvals[0].agentName).toBe('Amsha Agent');
  });

  it('handles approval action events from arc-hitl-panel correctly', () => {
    const chat = new ArcChatInterface();
    const sendApproveSpy = vi.spyOn(chat as any, '_sendApprove').mockImplementation(() => {});
    const sendDenySpy = vi.spyOn(chat as any, '_sendDeny').mockImplementation(() => {});

    const msg = {
      id: 'm1',
      role: 'assistant' as const,
      text: 'Action approval',
      timestamp: Date.now(),
      isApprovalPrompt: true,
      targetSessionId: 'sess-1',
    };

    // Simulate approval event
    const approveEv = new CustomEvent('approval-action', {
      detail: { taskId: 'sess-1', action: 'approved' },
    });
    (chat as any)._onHitlAction(approveEv, msg);
    expect(sendApproveSpy).toHaveBeenCalledWith(msg);

    // Simulate denial event
    const denyEv = new CustomEvent('approval-action', {
      detail: { taskId: 'sess-1', action: 'rejected' },
    });
    (chat as any)._onHitlAction(denyEv, msg);
    expect(sendDenySpy).toHaveBeenCalledWith(msg);
  });

  it('displays turn-stats with real values from trace events', () => {
    const events: AgentTraceEvent[] = [
      makeTrace({ stage: 'turn_start', timestamp: '2026-09-01T12:00:00.000Z' }),
      makeTrace({ stage: 'guard_check', status: 'ALLOW', riskLevel: 'LOW' }),
      makeTrace({
        stage: 'llm_reasoning',
        metrics: { promptTokens: 300, completionTokens: 120, durationMs: 450 },
      }),
      makeTrace({ stage: 'turn_end', timestamp: '2026-09-01T12:00:01.000Z' }),
    ];

    const stats = computeTurnStats(events);
    expect(stats.guardDecision).toBe('ALLOW');
    expect(stats.latencyMs).toBe(450);
    expect(stats.promptTokens).toBe(300);
    expect(stats.completionTokens).toBe(120);
    expect(stats.totalTokens).toBe(420);
  });

  it('converts JSON string approvalData.params to object in _getApprovalsForMessage', () => {
    const chat = new ArcChatInterface();
    const msg = {
      id: 'm2',
      role: 'assistant' as const,
      text: 'Need approval',
      timestamp: Date.now(),
      isApprovalPrompt: true,
      approvalData: {
        tool: 'sandbox_exec',
        params: '{"code": "package main", "language": "go"}' as any,
      },
      targetSessionId: 'sess-target-100',
    };

    const approvals = (chat as any)._getApprovalsForMessage(msg);
    expect(approvals[0].params).toEqual({ code: 'package main', language: 'go' });
  });

  it('formats JSON string parameters cleanly without character-by-character splitting in ArcHitlPanel', () => {
    const panel = new ArcHitlPanel();
    const formatted = (panel as any)._formatParams(
      '{"code": "package main\\nfmt.Println()", "language": "go"}'
    );
    expect(formatted).toContain('code: package main\nfmt.Println()');
    expect(formatted).toContain('language: go');
    expect(formatted).not.toContain('0: {');
    expect(formatted).not.toContain('1: "');
  });

  it('clears the approval spinner and accepts a timed-out request once the approval disappears', async () => {
    const panel = new ArcHitlPanel();
    (panel as any)._appContext = { apiKey: '' };
    const approval = {
      taskId: 'task-timeout',
      toolName: 'sandbox_exec',
      params: { code: 'echo done' },
      requestedAt: new Date().toISOString(),
      agentName: 'Agent',
    };
    panel.approvals = [approval];
    const events: CustomEvent[] = [];
    panel.addEventListener('approval-action', (event) => events.push(event as CustomEvent));

    vi.spyOn(ArcClient.prototype, 'approveHitl').mockRejectedValue(
      new DOMException('The approval request timed out', 'TimeoutError'),
    );
    vi.spyOn(ArcClient.prototype, 'getHitl').mockResolvedValue({
      items: [],
      page: 0,
      size: 20,
      totalCount: 0,
      totalPages: 0,
    });

    await (panel as any)._approve('task-timeout');

    expect((panel as any)._processing.has('task-timeout')).toBe(false);
    expect((panel as any)._statusUnknown.has('task-timeout')).toBe(false);
    expect(events[0]?.detail).toMatchObject({ taskId: 'task-timeout', action: 'approved' });
  });

  it('clears the spinner and exposes status-unknown when approval is still pending', async () => {
    const panel = new ArcHitlPanel();
    (panel as any)._appContext = { apiKey: '' };
    const approval = {
      taskId: 'task-pending',
      toolName: 'sandbox_exec',
      params: { code: 'echo done' },
      requestedAt: new Date().toISOString(),
      agentName: 'Agent',
    };
    panel.approvals = [approval];

    vi.spyOn(ArcClient.prototype, 'approveHitl').mockRejectedValue(
      new DOMException('The approval request timed out', 'TimeoutError'),
    );
    vi.spyOn(ArcClient.prototype, 'getHitl').mockResolvedValue({
      items: [approval],
      page: 0,
      size: 20,
      totalCount: 1,
      totalPages: 1,
    });

    await (panel as any)._approve('task-pending');

    expect((panel as any)._processing.has('task-pending')).toBe(false);
    expect((panel as any)._statusUnknown.has('task-pending')).toBe(true);
    expect((panel as any)._error['task-pending']).toContain('still pending');
  });

  it('toggles media dropdown menu and handles menu item actions', () => {
    const chat = new ArcChatInterface();
    expect((chat as any)._showMediaMenu).toBe(false);

    const mockClickEvent = { stopPropagation: () => {} } as any;

    // Toggle open
    (chat as any)._toggleMediaMenu(mockClickEvent);
    expect((chat as any)._showMediaMenu).toBe(true);

    // Escape closes menu
    (chat as any)._handleGlobalKeyDown({ key: 'Escape' } as any);
    expect((chat as any)._showMediaMenu).toBe(false);

    // Menu attach triggers picker and closes menu
    const pickerSpy = vi.spyOn(chat as any, '_triggerFilePicker').mockImplementation(() => {});
    (chat as any)._showMediaMenu = true;
    (chat as any)._onMenuAttach();
    expect((chat as any)._showMediaMenu).toBe(false);
    expect(pickerSpy).toHaveBeenCalled();

    // Menu record toggles recording and closes menu
    const recordSpy = vi.spyOn(chat as any, '_toggleRecording').mockImplementation(async () => {});
    (chat as any)._showMediaMenu = true;
    (chat as any)._onMenuRecord();
    expect((chat as any)._showMediaMenu).toBe(false);
    expect(recordSpy).toHaveBeenCalled();

    // Menu TTS toggles auto-play
    expect((chat as any)._autoPlaySpeech).toBe(false);
    (chat as any)._onMenuToggleTts();
    expect((chat as any)._autoPlaySpeech).toBe(true);
    (chat as any)._onMenuToggleTts();
    expect((chat as any)._autoPlaySpeech).toBe(false);

    // Clicking toggle button while recording stops recording directly
    (chat as any)._isRecording = true;
    (chat as any)._showMediaMenu = false;
    const stopRecordingSpy = vi.spyOn(chat as any, '_stopRecording').mockImplementation(() => {});
    (chat as any)._toggleMediaMenu(mockClickEvent);
    expect(stopRecordingSpy).toHaveBeenCalled();
    expect((chat as any)._showMediaMenu).toBe(false);
  });

  it('resets connecting lock, dispatches connecting status, and triggers connection attempt on reconnect()', async () => {
    const listeners = new Map<string, Function[]>();
    const mockWindow = {
      addEventListener: (type: string, fn: any) => {
        const list = listeners.get(type) || [];
        list.push(fn);
        listeners.set(type, list);
      },
      removeEventListener: (type: string, fn: any) => {
        const list = listeners.get(type) || [];
        listeners.set(type, list.filter((l) => l !== fn));
      },
      dispatchEvent: (e: any) => {
        const list = listeners.get(e.type) || [];
        list.forEach((l) => l(e));
        return true;
      },
    };
    vi.stubGlobal('window', mockWindow);

    const chat = new ArcChatInterface();
    // Simulate stuck connecting state and failed previous connection
    (chat as any)._connecting = true;
    (chat as any)._wsStatus = 'closed';
    (chat as any)._reconnectAttempts = 5;

    const statuses: string[] = [];
    const handler = (e: any) => statuses.push(e.detail.status);
    mockWindow.addEventListener('ws-status-change', handler);

    const connectSpy = vi.spyOn(chat as any, '_connect').mockImplementation(async () => {});

    await chat.reconnect();

    expect((chat as any)._reconnectAttempts).toBe(0);
    expect((chat as any)._isReconnecting).toBe(true);
    expect(statuses).toContain('connecting');
    expect(connectSpy).toHaveBeenCalled();

    mockWindow.removeEventListener('ws-status-change', handler);
    vi.unstubAllGlobals();
  });

  it('coalesces repeated reconnect clicks into one connection attempt', async () => {
    const mockWindow = { dispatchEvent: vi.fn() };
    vi.stubGlobal('window', mockWindow);

    const chat = new ArcChatInterface();
    const connectSpy = vi.spyOn(chat as any, '_connect').mockResolvedValue(undefined);

    const first = chat.reconnect();
    const second = chat.reconnect();

    expect(second).toBe(first);
    await first;
    expect(connectSpy).toHaveBeenCalledTimes(1);

    vi.unstubAllGlobals();
  });

  it('does not replace a socket that is already connecting', async () => {
    const mockWindow = { dispatchEvent: vi.fn() };
    vi.stubGlobal('window', mockWindow);

    const chat = new ArcChatInterface();
    (chat as any)._ws = {};
    (chat as any)._wsStatus = 'connecting';
    (chat as any)._connecting = true;
    const connectSpy = vi.spyOn(chat as any, '_connect').mockResolvedValue(undefined);

    await chat.reconnect();

    expect(connectSpy).not.toHaveBeenCalled();
    expect((chat as any)._ws).toEqual({});

    vi.unstubAllGlobals();
  });

  it('keeps a stalled turn visible when the websocket reconnects', async () => {
    const mockWindow = { dispatchEvent: vi.fn() };
    vi.stubGlobal('window', mockWindow);

    const chat = new ArcChatInterface();
    (chat as any)._turnStalled = true;
    (chat as any)._progressPhase = 'Connection lost';
    (chat as any)._progressDetail = 'Reconnect to continue checking this turn.';
    vi.spyOn(chat as any, '_loadHistory').mockResolvedValue(undefined);

    (chat as any)._handleFrame({ type: 'connected', sessionId: 'sess-1' });

    expect((chat as any)._turnStalled).toBe(true);
    expect((chat as any)._progressPhase).toBe('Connection lost');
    expect((chat as any)._progressDetail).toContain('Reconnect');

    vi.unstubAllGlobals();
  });

  it('persists the latest authenticated session when the server sends provisional and authenticated frames', () => {
    const storage = new Map<string, string>();
    const mockWindow = { dispatchEvent: vi.fn() };
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    });
    vi.stubGlobal('window', mockWindow);

    const chat = new ArcChatInterface();
    vi.spyOn(chat as any, '_loadHistory').mockResolvedValue([]);
    (chat as any)._handleFrame({
      type: 'connected',
      sessionId: 'provisional-session',
      platformId: 'platform-1',
    });
    (chat as any)._handleFrame({
      type: 'connected',
      sessionId: 'authenticated-session',
      platformId: 'platform-1',
    });

    expect(storage.get('amsha-chat-session')).toBe('authenticated-session');
    expect(mockWindow.dispatchEvent).toHaveBeenLastCalledWith(
      expect.objectContaining({
        type: 'session-ready',
        detail: { sessionId: 'authenticated-session', platformId: 'platform-1' },
      }),
    );

    vi.unstubAllGlobals();
  });

  it('turns a background cron approval frame into an actionable approval message', () => {
    const chat = new ArcChatInterface();

    (chat as any)._handleFrame({
      type: 'approval_required',
      role: 'assistant',
      text: 'Approval required for the scheduled task',
      sessionId: 'cron-cron-1-exec-1',
      toolName: 'sandbox_exec',
    });

    expect((chat as any)._messages).toHaveLength(1);
    expect((chat as any)._messages[0]).toMatchObject({
      isApprovalPrompt: true,
      approvalViaChannel: true,
      targetSessionId: 'cron-cron-1-exec-1',
      approvalData: { tool: 'sandbox_exec' },
    });
  });

  it('uses the originating websocket for a background cron approval', () => {
    const chat = new ArcChatInterface();
    const send = vi.fn();
    (chat as any)._wsStatus = 'open';
    (chat as any)._ws = { send };
    (chat as any)._awaitingApproval = true;
    const armWatchdogSpy = vi.spyOn(chat as any, '_armTurnWatchdog').mockImplementation(() => {});

    (chat as any)._sendApprove({
      targetSessionId: 'cron-cron-1-exec-1',
      approvalViaChannel: true,
    });

    expect(send).toHaveBeenCalledWith({
      type: 'message',
      text: '/approve cron-cron-1-exec-1',
    });
    expect((chat as any)._awaitingApproval).toBe(false);
    expect((chat as any)._isTyping).toBe(true);
    expect((chat as any)._progressPhase).toBe('Executing tool');
    expect((chat as any)._activeBackgroundCronSessionId).toBe('cron-cron-1-exec-1');
    expect(armWatchdogSpy).toHaveBeenCalled();
  });

  it('checks backend status without sending another chat message', async () => {
    const chat = new ArcChatInterface();
    (chat as any)._appContext = { apiKey: '', sessionId: 'session-1' };
    (chat as any)._turnStalled = true;
    const statusSpy = vi
      .spyOn(ArcClient.prototype as any, 'getSessionStatus')
      .mockResolvedValue({ status: 'AWAITING_APPROVAL' });
    vi.spyOn(chat as any, '_loadHistory').mockResolvedValue(undefined);

    await (chat as any)._checkStalledTurn();

    expect(statusSpy).toHaveBeenCalledWith('session-1', 'coordinator');
    expect((chat as any)._progressPhase).toBe('Waiting for approval');
    expect((chat as any)._progressDetail).toContain('AWAITING_APPROVAL');
  });

  it('clears a stalled turn when the backend is terminal without history', async () => {
    const chat = new ArcChatInterface();
    (chat as any)._appContext = { apiKey: '', sessionId: 'session-1' };
    (chat as any)._isTyping = false;
    (chat as any)._turnStalled = true;
    (chat as any)._awaitingApproval = true;
    vi.spyOn(ArcClient.prototype as any, 'getSessionStatus').mockResolvedValue({
      status: 'COMPLETED',
    });
    vi.spyOn(chat as any, '_loadHistory').mockResolvedValue([]);

    await (chat as any)._checkStalledTurn();

    expect((chat as any)._isTyping).toBe(false);
    expect((chat as any)._awaitingApproval).toBe(false);
    expect((chat as any)._turnStalled).toBe(false);
    expect((chat as any)._progressPhase).toBe('Status checked');
    expect((chat as any)._progressDetail).toContain('COMPLETED');
  });

  it('resumes the chat turn after an approval event from the separate HITL view', () => {
    const chat = new ArcChatInterface();
    (chat as any)._awaitingApproval = true;
    (chat as any)._isTyping = false;
    vi.spyOn(chat as any, '_armTurnWatchdog').mockImplementation(() => {});

    (chat as any)._onProgress({
      detail: { phase: 'Resuming work', detail: 'Picking up where the agent left off.' },
    });

    expect((chat as any)._awaitingApproval).toBe(false);
    expect((chat as any)._isTyping).toBe(true);
  });

  it('accepts progress for the tracked background cron session only', () => {
    const chat = new ArcChatInterface();
    (chat as any)._appContext = { apiKey: '', sessionId: 'session-1' };
    (chat as any)._activeBackgroundCronSessionId = 'cron-cron-1-exec-1';
    vi.spyOn(chat as any, '_armTurnWatchdog').mockImplementation(() => {});

    (chat as any)._onProgress({
      detail: {
        sessionId: 'cron-cron-1-exec-1',
        phase: 'Executing tool',
        detail: 'Running the approved scheduled task.',
      },
    });

    expect((chat as any)._progressPhase).toBe('Executing tool');
    expect((chat as any)._isTyping).toBe(true);

    (chat as any)._onProgress({
      detail: {
        sessionId: 'cron-other-exec-1',
        phase: 'Wrong session',
        detail: 'Must be ignored.',
      },
    });

    expect((chat as any)._progressPhase).toBe('Executing tool');
    expect((chat as any)._progressDetail).toBe('Running the approved scheduled task.');
  });

  it('clears the scheduled turn after a background cron completion frame', () => {
    const chat = new ArcChatInterface();
    const clearWatchdogSpy = vi.spyOn(chat as any, '_clearTurnWatchdog');

    (chat as any)._activeBackgroundCronSessionId = 'cron-cron-1-exec-1';
    (chat as any)._isTyping = true;
    (chat as any)._awaitingApproval = false;
    (chat as any)._turnStalled = false;
    (chat as any)._progressPhase = 'Executing tool';
    (chat as any)._progressDetail = 'Tool approved, running execution...';

    (chat as any)._handleFrame({
      type: 'message',
      role: 'assistant',
      sessionId: 'cron-cron-1-exec-1',
      text: 'The scheduled task completed successfully.',
    });

    expect((chat as any)._isTyping).toBe(false);
    expect((chat as any)._awaitingApproval).toBe(false);
    expect((chat as any)._turnStalled).toBe(false);
    expect((chat as any)._progressPhase).toBe('');
    expect((chat as any)._progressDetail).toBe('');
    expect((chat as any)._activeBackgroundCronSessionId).toBeNull();
    expect(clearWatchdogSpy).toHaveBeenCalled();
  });

  it('does not clear a newer interactive turn for an untracked cron completion', () => {
    const chat = new ArcChatInterface();

    (chat as any)._isTyping = true;
    (chat as any)._progressPhase = 'Thinking';
    (chat as any)._progressDetail = 'Working on the newer request...';

    (chat as any)._handleFrame({
      type: 'message',
      role: 'assistant',
      sessionId: 'cron-old-exec-1',
      text: 'The old scheduled task completed.',
    });

    expect((chat as any)._isTyping).toBe(true);
    expect((chat as any)._progressPhase).toBe('Thinking');
    expect((chat as any)._progressDetail).toBe('Working on the newer request...');
    expect((chat as any)._activeBackgroundCronSessionId).toBeNull();
    expect((chat as any)._messages[0].text).toBe('The old scheduled task completed.');
  });
});
