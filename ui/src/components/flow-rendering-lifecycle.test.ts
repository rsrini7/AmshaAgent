import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { html } from 'lit';
import { ArcShell } from './arc-shell';
import { ArcChatInterface } from './arc-chat-interface';
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
    sessionId: 'sess-lifecycle',
    turnNumber: 1,
    sequence: 0,
    stage: 'turn_start',
    station: 'station_01_inbound',
    status: 'COMPLETED',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-02T12:00:00.000Z',
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    ...overrides,
  };
}

describe('Visual Cockpit Flow Rendering Lifecycle Regression', () => {
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

  it('renders exactly one live execution flow for the active run and resets previous run on continuation', () => {
    const shell = new ArcShell();
    (shell as any)._sessionId = 'sess-lifecycle';

    // 1. First execution run (run-1)
    const run1Events = [
      makeTrace({ runId: 'sess-lifecycle:1', turnNumber: 1, stage: 'turn_start' }),
      makeTrace({ runId: 'sess-lifecycle:1', turnNumber: 1, stage: 'guard_check' }),
      makeTrace({ runId: 'sess-lifecycle:1', turnNumber: 1, stage: 'llm_reasoning' }),
      makeTrace({ runId: 'sess-lifecycle:1', turnNumber: 1, stage: 'turn_end' }),
    ];
    for (const ev of run1Events) {
      (shell as any)._handleSseEvent(ev);
    }

    expect((shell as any)._currentRunId).toBe('sess-lifecycle:1');
    expect((shell as any)._activeRunEvents).toHaveLength(4);
    expect((shell as any)._activeRunEvents.every((e: AgentTraceEvent) => e.runId === 'sess-lifecycle:1')).toBe(true);

    // Live canvas reflects run-1
    const canvas = new ArcFlowCanvas();
    canvas.runId = (shell as any)._currentRunId;
    canvas.events = (shell as any)._activeRunEvents;
    let canvasRender = JSON.stringify(canvas.render());
    expect(canvasRender).toContain('sess-lifec'); // slice(0, 10) in run badge
    expect(canvas.events).toHaveLength(4);

    // 2. Continuation arrives with a new runId (run-2)
    const run2Events = [
      makeTrace({ runId: 'sess-lifecycle:2', turnNumber: 2, stage: 'turn_start' }),
      makeTrace({ runId: 'sess-lifecycle:2', turnNumber: 2, stage: 'llm_reasoning' }),
    ];
    for (const ev of run2Events) {
      (shell as any)._handleSseEvent(ev);
    }

    // Proves: On a new runId, previous live flow is cleared before displaying new run
    expect((shell as any)._currentRunId).toBe('sess-lifecycle:2');
    const activeEvents = (shell as any)._activeRunEvents;
    expect(activeEvents).toHaveLength(2);
    expect(activeEvents.every((e: AgentTraceEvent) => e.runId === 'sess-lifecycle:2')).toBe(true);
    expect(activeEvents.some((e: AgentTraceEvent) => e.runId === 'sess-lifecycle:1')).toBe(false);

    // Live canvas strictly displays only the active run (run-2)
    canvas.runId = (shell as any)._currentRunId;
    canvas.events = (shell as any)._activeRunEvents;
    canvasRender = JSON.stringify(canvas.render());
    expect(canvas.events).toHaveLength(2);
    expect(canvas.events.some((e: AgentTraceEvent) => e.runId === 'sess-lifecycle:1')).toBe(false);
  });

  it('does not render shared traceEvents in assistant messages and prevents duplicate flow cards', () => {
    const chat = new ArcChatInterface();
    vi.spyOn(chat as any, '_renderMarkdown').mockImplementation((_id: any, text: any) => html`<div>${text}</div>`);

    // Two assistant messages representing two turns in a conversation
    (chat as any)._messages = [
      {
        id: 'msg-1',
        role: 'assistant',
        text: 'Initial response from turn 1',
        timestamp: 1000,
      },
      {
        id: 'msg-2',
        role: 'assistant',
        text: 'Continuation response from turn 2',
        timestamp: 2000,
      },
    ];

    // Simulate global traceEvents populated with active run-2
    chat.traceEvents = [
      makeTrace({ runId: 'sess-lifecycle:2', turnNumber: 2, stage: 'turn_start' }),
      makeTrace({ runId: 'sess-lifecycle:2', turnNumber: 2, stage: 'llm_reasoning' }),
    ];

    const rendered = chat.render();
    const renderedStr = JSON.stringify(rendered);

    // Proves: Neither assistant message renders arc-run-flow-card from the shared traceEvents array
    expect(renderedStr).not.toContain('arc-run-flow-card');
  });

  it('scopes inline run summaries strictly to their own message runId and turnNumber', () => {
    const chat = new ArcChatInterface();
    vi.spyOn(chat as any, '_renderMarkdown').mockImplementation((_id: any, text: any) => html`<div>${text}</div>`);

    const turn1Events = [
      makeTrace({ runId: 'run-alpha', turnNumber: 1, stage: 'turn_start' }),
      makeTrace({ runId: 'run-alpha', turnNumber: 1, stage: 'turn_end' }),
    ];
    const turn2Events = [
      makeTrace({ runId: 'run-beta', turnNumber: 2, stage: 'turn_start' }),
    ];

    // Message 1 carries its own scoped summary for run-alpha
    // Message 2 has no inline summary
    (chat as any)._messages = [
      {
        id: 'msg-alpha',
        role: 'assistant',
        text: 'First response',
        timestamp: 1000,
        runId: 'run-alpha',
        runEvents: turn1Events,
      },
      {
        id: 'msg-beta',
        role: 'assistant',
        text: 'Second response',
        timestamp: 2000,
      },
    ];

    // Global active trace is run-beta
    chat.traceEvents = turn2Events;

    const rendered = chat.render();
    const renderedStr = JSON.stringify(rendered);

    // Message 1 displays its own scoped runId (run-alpha)
    expect(renderedStr).toContain('run-alpha');
    // Message 1 does NOT display the new run (run-beta)
    // And message 2 does not render any duplicate flow cards
    expect(renderedStr).not.toContain('run-beta');
  });

  it('automatically attaches active runEvents and runId in real production message creation path (_handleFrame)', () => {
    const chat = new ArcChatInterface();
    vi.spyOn(chat as any, '_renderMarkdown').mockImplementation((_id: any, text: any) => html`<div>${text}</div>`);

    const turn1TraceEvents = [
      makeTrace({ runId: 'sess-prod:1', turnNumber: 1, stage: 'turn_start' }),
      makeTrace({ runId: 'sess-prod:1', turnNumber: 1, stage: 'llm_reasoning' }),
    ];

    // 1. Live trace events arrive for Turn 1
    chat.traceEvents = turn1TraceEvents;

    // 2. Real message frame arrives over WebSocket
    (chat as any)._handleFrame({
      type: 'message',
      role: 'assistant',
      text: 'Production response for turn 1',
      timestamp: 1000,
    });

    const messages = (chat as any)._messages;
    expect(messages).toHaveLength(1);
    expect(messages[0].runId).toBe('sess-prod:1');
    expect(messages[0].runEvents).toHaveLength(2);
    expect(messages[0].runEvents[0].runId).toBe('sess-prod:1');

    // Rendering produces flow card with run-1
    let rendered = JSON.stringify(chat.render());
    expect(rendered).toContain('sess-prod:1');

    // 3. Late-arriving turn_end trace event for Turn 1 arrives
    const turn1CompleteEvents = [
      ...turn1TraceEvents,
      makeTrace({ runId: 'sess-prod:1', turnNumber: 1, stage: 'turn_end' }),
    ];
    chat.traceEvents = turn1CompleteEvents;
    (chat as any)._syncLatestAssistantRunEvents();

    expect((chat as any)._messages[0].runEvents).toHaveLength(3);

    // 4. Continuation begins: Turn 2 trace events arrive
    const turn2TraceEvents = [
      makeTrace({ runId: 'sess-prod:2', turnNumber: 2, stage: 'turn_start' }),
      makeTrace({ runId: 'sess-prod:2', turnNumber: 2, stage: 'llm_reasoning' }),
      makeTrace({ runId: 'sess-prod:2', turnNumber: 2, stage: 'turn_end' }),
    ];
    chat.traceEvents = turn2TraceEvents;
    (chat as any)._syncLatestAssistantRunEvents();

    // Turn 1 message must NOT be altered or overwritten by Turn 2 trace events!
    expect((chat as any)._messages[0].runId).toBe('sess-prod:1');
    expect((chat as any)._messages[0].runEvents).toHaveLength(3);
    expect((chat as any)._messages[0].runEvents.every((e: AgentTraceEvent) => e.runId === 'sess-prod:1')).toBe(true);

    // 5. Turn 2 message frame arrives over WebSocket
    (chat as any)._handleFrame({
      type: 'message',
      role: 'assistant',
      text: 'Production response for turn 2',
      timestamp: 2000,
    });

    expect((chat as any)._messages).toHaveLength(2);
    expect((chat as any)._messages[1].runId).toBe('sess-prod:2');
    expect((chat as any)._messages[1].runEvents).toHaveLength(3);
    expect((chat as any)._messages[1].runEvents.every((e: AgentTraceEvent) => e.runId === 'sess-prod:2')).toBe(true);

    // When rendered: Both messages render their respective run summaries without bleeding
    rendered = JSON.stringify(chat.render());
    expect(rendered).toContain('sess-prod:1');
    expect(rendered).toContain('sess-prod:2');
  });
});
