import { describe, expect, it } from 'vitest';
import { computeConveyorStations } from './arc-harness-conveyor';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-guard-1',
    sessionId: 'sess-1',
    turnNumber: 1,
    sequence: 0,
    stage: 'guard_check',
    station: 'station_05_tool_guard',
    status: 'ALLOW',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-01T12:00:00.000Z',
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    ...overrides,
  };
}

describe('Conveyor Guard Station (Station 05)', () => {
  it('drives COMPLETED state when guard_check status is ALLOW', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ status: 'ALLOW', riskLevel: 'LOW', detail: 'safe tool' }),
    ];
    const stations = computeConveyorStations(events);
    const guardStation = stations.find((s) => s.code === 'station_05_tool_guard')!;

    expect(guardStation.status).toBe('COMPLETED');
    expect(guardStation.detail).toBe('safe tool');
  });

  it('drives HITL_REQUIRED state when guard_check status is HITL_REQUIRED', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ status: 'HITL_REQUIRED', riskLevel: 'HIGH', detail: 'shell command' }),
    ];
    const stations = computeConveyorStations(events);
    const guardStation = stations.find((s) => s.code === 'station_05_tool_guard')!;

    expect(guardStation.status).toBe('HITL_REQUIRED');
    expect(guardStation.riskLevel).toBe('HIGH');
  });

  it('drives DENIED state when guard_check status is DENIED', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ status: 'DENIED', riskLevel: 'CRITICAL', detail: 'forbidden operation' }),
    ];
    const stations = computeConveyorStations(events);
    const guardStation = stations.find((s) => s.code === 'station_05_tool_guard')!;

    expect(guardStation.status).toBe('DENIED');
    expect(guardStation.riskLevel).toBe('CRITICAL');
  });

  it('drives DENIED state on hitl_timeout', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'hitl_timeout', status: 'DENIED_RESTART', detail: 'timed out' }),
    ];
    const stations = computeConveyorStations(events);
    const guardStation = stations.find((s) => s.code === 'station_05_tool_guard')!;

    expect(guardStation.status).toBe('DENIED');
    expect(guardStation.detail).toBe('timed out');
  });
});
