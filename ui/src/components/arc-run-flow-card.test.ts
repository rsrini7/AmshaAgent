import { describe, expect, it } from 'vitest';
import {
  ArcRunFlowCard,
  computeFlowStages,
  computeTurnStats,
  CANONICAL_FLOW_STAGES,
} from './arc-run-flow-card';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-test-1',
    sessionId: 'session-1',
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

describe('ArcRunFlowCard logic & invariants', () => {
  it('marks unobserved stages as NOT_OBSERVED and never fabricates steps', () => {
    // Only 3 stages observed: turn_start, route, turn_end
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'turn_start', status: 'START' }),
      mockTrace({ stage: 'route', status: 'DEFAULT' }),
      mockTrace({ stage: 'turn_end', status: 'END' }),
    ];

    const stages = computeFlowStages(events);
    expect(stages).toHaveLength(CANONICAL_FLOW_STAGES.length);

    // Observed
    const inbound = stages.find((s) => s.stage === 'turn_start')!;
    expect(inbound.observed).toBe(true);
    expect(inbound.status).toBe('START');

    const routing = stages.find((s) => s.stage === 'route')!;
    expect(routing.observed).toBe(true);
    expect(routing.status).toBe('DEFAULT');

    const outbound = stages.find((s) => s.stage === 'turn_end')!;
    expect(outbound.observed).toBe(true);
    expect(outbound.status).toBe('END');

    // Unobserved - must NOT be fabricated
    const preflight = stages.find((s) => s.stage === 'preflight')!;
    expect(preflight.observed).toBe(false);
    expect(preflight.status).toBe('NOT_OBSERVED');

    const memDecision = stages.find((s) => s.stage === 'memory_decision')!;
    expect(memDecision.observed).toBe(false);
    expect(memDecision.status).toBe('NOT_OBSERVED');

    const guard = stages.find((s) => s.stage === 'guard_check')!;
    expect(guard.observed).toBe(false);
    expect(guard.status).toBe('NOT_OBSERVED');
  });

  it('computes turn-stats from real event metrics and decisions', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({
        stage: 'turn_start',
        timestamp: '2026-09-01T12:00:00.000Z',
        runId: 'run-xyz',
        turnNumber: 2,
      }),
      mockTrace({
        stage: 'guard_check',
        status: 'HITL_REQUIRED',
        riskLevel: 'HIGH',
      }),
      mockTrace({
        stage: 'llm_reasoning',
        metrics: { promptTokens: 120, completionTokens: 45, durationMs: 350 },
      }),
      mockTrace({
        stage: 'tool_start',
        metrics: { durationMs: 150 },
      }),
      mockTrace({
        stage: 'turn_end',
        timestamp: '2026-09-01T12:00:01.000Z',
      }),
    ];

    const stats = computeTurnStats(events);
    expect(stats.runId).toBe('run-xyz');
    expect(stats.turnNumber).toBe(2);
    expect(stats.guardDecision).toBe('HITL_REQUIRED');
    expect(stats.promptTokens).toBe(120);
    expect(stats.completionTokens).toBe(45);
    expect(stats.totalTokens).toBe(165);
    expect(stats.latencyMs).toBe(500); // 350 + 150 from metrics durationMs
  });

  it('falls back to timestamp diff if metrics duration is absent', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({
        stage: 'turn_start',
        timestamp: '2026-09-01T12:00:00.000Z',
      }),
      mockTrace({
        stage: 'turn_end',
        timestamp: '2026-09-01T12:00:02.500Z',
      }),
    ];

    const stats = computeTurnStats(events);
    expect(stats.latencyMs).toBe(2500);
    expect(stats.guardDecision).toBe('NOT_OBSERVED');
    expect(stats.totalTokens).toBe(0);
  });

  it('renders an empty flow card and one populated with observed events', () => {
    const card = new ArcRunFlowCard();
    expect(card.render()).toBeDefined();
    card.events = [mockTrace({ stage: 'turn_start', status: 'START' })];
    card.runId = 'run-test-1';
    expect(card.render()).toBeDefined();
  });
});
