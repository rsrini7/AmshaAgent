import { describe, expect, it, vi } from 'vitest';
import { ArcChatInterface } from './arc-chat-interface';
import { ArcHitlPanel } from './arc-hitl-panel';
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
});
