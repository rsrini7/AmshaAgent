import { describe, expect, it } from 'vitest';
import {
  computeConveyorStations,
  CONVEYOR_STATION_DEFINITIONS,
} from './arc-harness-conveyor';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-conv-1',
    sessionId: 'sess-1',
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

describe('Harness Conveyor 10-Station Pipeline', () => {
  it('marks unobserved/uninstrumented stations as NOT_INSTRUMENTED', () => {
    // Only station 1 (Inbound), 3 (Routing), and 10 (Outbound) emitted
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'route', station: 'station_03_routing', status: 'COMPLETED', detail: 'RESEARCHER' }),
      mockTrace({ stage: 'turn_end', station: 'station_10_outbound', status: 'END' }),
    ];

    const stations = computeConveyorStations(events);
    expect(stations).toHaveLength(10);

    // Station 1: Inbound
    expect(stations[0].status).toBe('ACTIVE');

    // Station 2: Preflight (not instrumented in core loop)
    expect(stations[1].status).toBe('NOT_INSTRUMENTED');
    expect(stations[1].name).toBe('Preflight');

    // Station 3: Routing
    expect(stations[2].status).toBe('COMPLETED');
    expect(stations[2].detail).toBe('RESEARCHER');

    // Station 4: Memory Decision (not instrumented per-turn)
    expect(stations[3].status).toBe('NOT_INSTRUMENTED');
    expect(stations[3].name).toBe('Memory Decision');

    // Station 5: Tool Guard
    expect(stations[4].status).toBe('NOT_INSTRUMENTED');

    // Station 10: Outbound
    expect(stations[9].status).toBe('COMPLETED');
  });

  it('reflects all stations when full sequence is provided', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'preflight', station: 'station_02_preflight', status: 'PASS' }),
      mockTrace({ stage: 'route', station: 'station_03_routing', status: 'DEFAULT' }),
      mockTrace({ stage: 'memory_decision', station: 'station_04_memory_decision', status: 'PASS' }),
      mockTrace({ stage: 'guard_check', station: 'station_05_tool_guard', status: 'ALLOW' }),
      mockTrace({ stage: 'llm_reasoning', station: 'station_06_reasoning', status: 'COMPLETED' }),
      mockTrace({ stage: 'tool_end', station: 'station_07_tool_execution', status: 'SUCCESS' }),
      mockTrace({ stage: 'quality_check', station: 'station_08_quality', status: 'PASS' }),
      mockTrace({ stage: 'consolidation', station: 'station_09_consolidation', status: 'COMPLETED' }),
      mockTrace({ stage: 'turn_end', station: 'station_10_outbound', status: 'END' }),
    ];

    const stations = computeConveyorStations(events);
    expect(stations).toHaveLength(10);

    for (let i = 0; i < 10; i++) {
      expect(stations[i].status).not.toBe('NOT_INSTRUMENTED');
    }
  });

  it('detects loop_stuck on Station 06 (Reasoning)', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'loop_stuck', station: 'station_06_reasoning', status: 'ALERT', detail: 'Threshold exceeded' }),
    ];

    const stations = computeConveyorStations(events);
    const reasoning = stations.find((s) => s.code === 'station_06_reasoning')!;
    expect(reasoning.status).toBe('LOOP_STUCK');
    expect(reasoning.detail).toBe('Threshold exceeded');

    const preflight = stations.find((s) => s.code === 'station_02_preflight')!;
    expect(preflight.status).toBe('NOT_INSTRUMENTED');
  });

  it('detects tool_error on Station 07 (Tool Execution)', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'tool_error', station: 'station_07_tool_execution', status: 'ERROR', detail: 'Timeout error' }),
    ];

    const stations = computeConveyorStations(events);
    const toolExec = stations.find((s) => s.code === 'station_07_tool_execution')!;
    expect(toolExec.status).toBe('ERROR');
    expect(toolExec.detail).toBe('Timeout error');
  });
});
