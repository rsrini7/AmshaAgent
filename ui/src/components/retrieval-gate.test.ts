import { describe, expect, it } from 'vitest';
import { ArcRetrievalGate, resolveGateState, computeRetrievalCounts } from './arc-retrieval-gate';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-gate-1',
    sessionId: 'sess-1',
    turnNumber: 1,
    sequence: 0,
    stage: 'memory_decision',
    station: 'station_04_memory_decision',
    status: 'PASS',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-01T12:00:00.000Z',
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    ...overrides,
  };
}

describe('Visual Memory / Retrieval Gate', () => {
  it('renders NOT_INSTRUMENTED when no memory_decision event occurred', () => {
    const state = resolveGateState(null);
    expect(state.decision).toBe('NOT_INSTRUMENTED');
    expect(state.statusText).toBe('NOT INSTRUMENTED');
    expect(state.detail).toContain('No memory decision observed');
    expect(state.observed).toBe(false);

    const gate = new ArcRetrievalGate();
    gate.events = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'route', station: 'station_03_routing', status: 'COMPLETED' }),
    ];

    const rendered = gate.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('NOT INSTRUMENTED');
    expect(str).toContain('No memory decision observed in this execution turn.');
  });

  it('renders OPEN gate when retrieval is permitted / triggered', () => {
    const ev = mockTrace({
      stage: 'memory_decision',
      station: 'station_04_memory_decision',
      status: 'RETRIEVE',
      detail: 'Semantic vector search executed (similarity > 0.82)',
      metrics: { durationMs: 42, attempt: 1 },
    });

    const state = resolveGateState(ev);
    expect(state.decision).toBe('OPEN');
    expect(state.statusText).toBe('RETRIEVE');
    expect(state.detail).toBe('Semantic vector search executed (similarity > 0.82)');
    expect(state.metrics).toEqual({ durationMs: 42, attempt: 1 });

    const gate = new ArcRetrievalGate();
    gate.event = ev;

    const rendered = gate.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('GATE: RETRIEVE');
    expect(str).toContain('durationMs');
    expect(str).toContain('42');
  });

  it('renders CLOSED gate when retrieval is skipped / bypassed', () => {
    const ev = mockTrace({
      stage: 'memory_decision',
      station: 'station_04_memory_decision',
      status: 'SKIP',
      detail: 'Context history sufficient; retrieval bypassed.',
    });

    const state = resolveGateState(ev);
    expect(state.decision).toBe('CLOSED');
    expect(state.statusText).toBe('SKIP');
    expect(state.detail).toContain('retrieval bypassed');

    const gate = new ArcRetrievalGate();
    gate.event = ev;

    const rendered = gate.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('GATE: SKIP');
    expect(str).toContain('decision-');
    expect(str).toContain('CLOSED');
  });

  it('renders BLOCKED gate when retrieval is denied by policy', () => {
    const ev = mockTrace({
      stage: 'memory_decision',
      station: 'station_04_memory_decision',
      status: 'DENIED',
      detail: 'Cross-tenant namespace isolation policy blocked recall search.',
    });

    const state = resolveGateState(ev);
    expect(state.decision).toBe('BLOCKED');
    expect(state.statusText).toBe('DENIED');

    const gate = new ArcRetrievalGate();
    gate.event = ev;

    const rendered = gate.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('GATE: DENIED');
    expect(str).toContain('decision-');
    expect(str).toContain('BLOCKED');
  });

  it('renders ERROR gate on memory lookup failure', () => {
    const ev = mockTrace({
      stage: 'memory_decision',
      station: 'station_04_memory_decision',
      status: 'ERROR',
      detail: 'Store connection timeout',
    });

    const state = resolveGateState(ev);
    expect(state.decision).toBe('ERROR');
    expect(state.statusText).toBe('ERROR');
  });

  it('computes accurate retrieval counters across multiple events', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ status: 'RETRIEVE' }),
      mockTrace({ status: 'SKIP' }),
      mockTrace({ status: 'SKIP' }),
      mockTrace({ status: 'DENIED' }),
      mockTrace({ status: 'RETRIEVE' }),
    ];

    const counts = computeRetrievalCounts(events);
    expect(counts.retrieveCount).toBe(2);
    expect(counts.skipCount).toBe(2);
    expect(counts.deniedCount).toBe(1);
    expect(counts.errorCount).toBe(0);
    expect(counts.totalDecisions).toBe(5);
  });

  it('renders retrieval counter badges in the gate component', () => {
    const gate = new ArcRetrievalGate();
    gate.events = [
      mockTrace({ status: 'RETRIEVE' }),
      mockTrace({ status: 'SKIP' }),
      mockTrace({ status: 'DENIED' }),
    ];

    const rendered = gate.render();
    const str = JSON.stringify(rendered);

    expect(str).toContain('RETRIEVE: ');
    expect(str).toContain('SKIP: ');
    expect(str).toContain('DENIED: ');
  });
});
