import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArcShell } from './arc-shell';
import { ArcFlowCanvas } from './arc-flow-canvas';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

class MockStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.get(key) ?? null;
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  setItem(key: string, val: string) {
    this.store.set(key, val);
  }
}

function makeTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-1',
    sessionId: 'sess-replay-test',
    turnNumber: 1,
    sequence: 0,
    stage: 'turn_start',
    station: 'station_01_inbound',
    status: 'COMPLETED',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-04T12:00:00.000Z',
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    ...overrides,
  };
}

describe('Visual Cockpit Trace Replay and Scrubbing Controls', () => {
  beforeEach(() => {
    vi.stubGlobal('sessionStorage', new MockStorage());
    vi.stubGlobal('window', {
      location: { hash: '#/chat' },
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      setTimeout: vi.fn(),
      clearTimeout: vi.fn(),
      dispatchEvent: vi.fn(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('isolates replay buffer from live SSE events and restores live buffer on exit', () => {
    const shell = new ArcShell();
    (shell as any)._sessionId = 'sess-replay-test';

    // 1. Ingest live event
    const liveEvent = makeTrace({ runId: 'run-live', sequence: 0, stage: 'turn_start' });
    (shell as any)._handleSseEvent(liveEvent);

    expect((shell as any)._currentRunId).toBe('run-live');
    expect((shell as any)._activeRunEvents).toHaveLength(1);

    // 2. Activate replay mode for an archived run
    const replayEvent1 = makeTrace({ runId: 'run-archive', sequence: 0, stage: 'turn_start' });
    const replayEvent2 = makeTrace({ runId: 'run-archive', sequence: 1, stage: 'llm_reasoning' });
    (shell as any)._isReplayMode = true;
    (shell as any)._replayRunId = 'run-archive';
    (shell as any)._replayTraceEvents = [replayEvent1, replayEvent2];

    expect((shell as any)._activeRunEvents).toHaveLength(2);
    expect((shell as any)._activeRunEvents[0].runId).toBe('run-archive');

    // 3. Incoming live event arrives while replay is active
    const lateLiveEvent = makeTrace({ runId: 'run-live', sequence: 1, stage: 'tool_start' });
    (shell as any)._handleSseEvent(lateLiveEvent);

    // Proves: Active replay view is not mutated or corrupted by incoming live event
    expect((shell as any)._activeRunEvents).toHaveLength(2);
    expect((shell as any)._activeRunEvents.every((e: AgentTraceEvent) => e.runId === 'run-archive')).toBe(true);

    // 4. Exit replay mode — buffered live events must be restored, not lost
    (shell as any)._exitReplay();
    expect((shell as any)._isReplayMode).toBe(false);
    expect((shell as any)._replayBufferedEvents).toHaveLength(0);
    // Restored: the pre-replay live event PLUS the live event that arrived during replay.
    expect((shell as any)._activeRunEvents).toHaveLength(2);
    expect((shell as any)._activeRunEvents.every((e: AgentTraceEvent) => e.runId === 'run-live')).toBe(true);
  });

  it('stepBackward at index 0 and stepForward at last index are no-ops', () => {
    const canvas = new ArcFlowCanvas();
    canvas.isReplayMode = true;
    canvas.events = [
      makeTrace({ runId: 'run-b', sequence: 0, stage: 'turn_start' }),
      makeTrace({ runId: 'run-b', sequence: 1, stage: 'guard_check' }),
      makeTrace({ runId: 'run-b', sequence: 2, stage: 'turn_end' }),
    ];

    // Scrub to the very first index, then step backward (no-op, stays at 0).
    (canvas as any)._scrubIndex = 0;
    (canvas as any)._stepBackward();
    expect((canvas as any)._scrubIndex).toBe(0);
    expect(canvas.visibleEvents).toHaveLength(1);

    // Scrub to the last index, then step forward (no-op, stays at last).
    (canvas as any)._scrubIndex = canvas.events.length - 1;
    (canvas as any)._stepForward();
    expect((canvas as any)._scrubIndex).toBe(canvas.events.length - 1);
    expect(canvas.visibleEvents).toHaveLength(3);
  });

  it('startReplay populates replay buffer and sets replay mode', async () => {
    const shell = new ArcShell();
    (shell as any)._sessionId = 'sess-replay-test';

    const dtos = [
      {
        eventId: 'ev-1',
        runId: 'run-archive',
        sessionId: 'sess-replay-test',
        turnNumber: 1,
        sequence: 0,
        stage: 'turn_start',
        station: 'station_01_inbound',
        status: null,
        riskLevel: null,
        detail: null,
        promptTokens: null,
        completionTokens: null,
        durationMs: null,
        iteration: null,
        attempt: null,
        timestamp: '2026-09-04T12:00:00.000Z',
        paradigm: null,
        version: 1,
        createdAt: '2026-09-04T12:00:00.000Z',
      },
    ];
    const evidence = {
      runId: 'run-archive',
      outcomeStatus: 'END',
      outcomeAvailability: 'OBSERVED',
      latencyMs: 17,
      latencyAvailability: 'MEASURED',
      observedEventCount: 1,
      eventsTruncated: false,
      guardDecisions: [],
      approvalDecisions: [],
      decisionEvidenceTruncated: false,
      promptTokens: null,
      completionTokens: null,
      usageAvailability: 'UNAVAILABLE',
      costStatus: 'UNAVAILABLE',
      estimatedCost: null,
      costCurrency: null,
      costReason: 'Run-scoped token usage is unavailable.',
    };
    const client = {
      getRunTrace: vi.fn().mockResolvedValue(dtos),
      getRunEvidence: vi.fn().mockResolvedValue(evidence),
    };
    (shell as any)._client = client;

    await (shell as any)._startReplay('run-archive');

    expect((shell as any)._isReplayMode).toBe(true);
    expect((shell as any)._replayRunId).toBe('run-archive');
    expect((shell as any)._replayTraceEvents).toHaveLength(1);
    expect((shell as any)._replayTraceEvents[0].eventId).toBe('ev-1');
    expect(client.getRunEvidence).toHaveBeenCalledWith('run-archive');
    expect((shell as any)._replayEvidence).toEqual(evidence);
  });

  it('scrubs and steps through execution trace events chronologically', () => {
    const canvas = new ArcFlowCanvas();
    canvas.isReplayMode = true;
    canvas.runId = 'run-scrub-test';
    canvas.events = [
      makeTrace({ runId: 'run-scrub-test', sequence: 0, stage: 'turn_start' }),
      makeTrace({ runId: 'run-scrub-test', sequence: 1, stage: 'guard_check' }),
      makeTrace({ runId: 'run-scrub-test', sequence: 2, stage: 'llm_reasoning' }),
      makeTrace({ runId: 'run-scrub-test', sequence: 3, stage: 'tool_start' }),
      makeTrace({ runId: 'run-scrub-test', sequence: 4, stage: 'turn_end' }),
    ];

    // Default: all events visible
    expect(canvas.visibleEvents).toHaveLength(5);

    // Scrub to index 2
    (canvas as any)._scrubIndex = 2;
    expect(canvas.visibleEvents).toHaveLength(3);
    expect(canvas.visibleEvents[canvas.visibleEvents.length - 1].stage).toBe('llm_reasoning');

    // Step forward to index 3
    (canvas as any)._stepForward();
    expect(canvas.visibleEvents).toHaveLength(4);
    expect(canvas.visibleEvents[canvas.visibleEvents.length - 1].stage).toBe('tool_start');

    // Step backward to index 2
    (canvas as any)._stepBackward();
    expect(canvas.visibleEvents).toHaveLength(3);
    expect(canvas.visibleEvents[canvas.visibleEvents.length - 1].stage).toBe('llm_reasoning');

    // Step backward to index 1
    (canvas as any)._stepBackward();
    expect(canvas.visibleEvents).toHaveLength(2);
    expect(canvas.visibleEvents[canvas.visibleEvents.length - 1].stage).toBe('guard_check');
  });

  it('detects sequence gaps and renders NOT_OBSERVED indicator', () => {
    const canvas = new ArcFlowCanvas();
    canvas.isReplayMode = true;
    canvas.runId = 'run-gap-test';

    // Consecutive sequence: no gaps
    canvas.events = [
      makeTrace({ sequence: 0, stage: 'turn_start' }),
      makeTrace({ sequence: 1, stage: 'guard_check' }),
      makeTrace({ sequence: 2, stage: 'turn_end' }),
    ];
    expect((canvas as any)._detectSequenceGaps(canvas.events)).toBe(0);
    let rendered = JSON.stringify(canvas.render());
    expect(rendered).not.toContain('NOT_OBSERVED');

    // Gaps in sequence (sequence jumps from 1 to 4)
    canvas.events = [
      makeTrace({ sequence: 0, stage: 'turn_start' }),
      makeTrace({ sequence: 1, stage: 'guard_check' }),
      makeTrace({ sequence: 4, stage: 'turn_end' }),
    ];
    expect((canvas as any)._detectSequenceGaps(canvas.events)).toBe(1);
    rendered = JSON.stringify(canvas.render());
    expect(rendered).toContain('NOT_OBSERVED');
    expect(rendered).toContain('gaps)');
  });

  it('covers paradigm view selection, replay playback, scrub controls, and run actions', () => {
    const canvas = new ArcFlowCanvas();
    const events = [
      makeTrace({ sequence: 0, stage: 'turn_start' }),
      makeTrace({ sequence: 1, stage: 'guard_check' }),
      makeTrace({ sequence: 2, stage: 'turn_end' }),
    ];
    canvas.events = events;
    canvas.paradigm = 'REACT';
    canvas.setViewMode('react');
    expect(canvas.viewMode).toBe('react');
    canvas.setViewMode('multi_agent');
    expect(canvas.viewMode).toBe('conveyor');
    canvas.paradigm = 'GROUP_CONSULTATION';
    canvas.setViewMode('multi_agent');
    expect(canvas.viewMode).toBe('multi_agent');
    canvas.setViewMode('conveyor');
    expect(canvas.viewMode).toBe('conveyor');

    (window as any).setTimeout = vi.fn(() => 44);
    (window as any).clearTimeout = vi.fn();
    (canvas as any)._play();
    expect((canvas as any)._isPlaying).toBe(true);
    expect((canvas as any)._scrubIndex).toBe(0);
    (canvas as any)._togglePlay();
    expect((canvas as any)._isPlaying).toBe(false);
    (canvas as any)._togglePlay();
    expect((canvas as any)._isPlaying).toBe(true);
    (canvas as any)._cycleSpeed();
    expect((canvas as any)._playbackSpeed).toBe(2);
    (canvas as any)._cycleSpeed();
    expect((canvas as any)._playbackSpeed).toBe(5);
    (canvas as any)._cycleSpeed();
    expect((canvas as any)._playbackSpeed).toBe(1);

    (canvas as any)._onScrubInput({ target: { value: '99' } });
    expect((canvas as any)._scrubIndex).toBe(2);
    (canvas as any)._onScrubInput({ target: { value: 'invalid' } });
    expect((canvas as any)._scrubIndex).toBe(2);
    canvas.isReplayMode = true;
    (canvas as any)._scrubIndex = -1;
    canvas.updated(new Map([['isReplayMode', false]]) as any);
    expect((canvas as any)._scrubIndex).toBe(2);

    const selectedRuns: string[] = [];
    canvas.addEventListener('start-replay', (event) => selectedRuns.push((event as CustomEvent).detail.runId));
    const refreshEvents: string[] = [];
    canvas.addEventListener('refresh-runs', () => refreshEvents.push('refresh'));
    canvas.addEventListener('exit-replay', () => refreshEvents.push('exit'));
    (canvas as any)._runsOpen = true;
    (canvas as any)._onSelectRun('run-archive');
    expect(selectedRuns).toEqual(['run-archive']);
    expect((canvas as any)._runsOpen).toBe(false);
    (canvas as any)._onExitReplay();
    expect(refreshEvents).toEqual(['exit', 'refresh']);
    expect((canvas as any)._scrubIndex).toBe(-1);
    expect((canvas as any)._isPlaying).toBe(false);
  });
});
