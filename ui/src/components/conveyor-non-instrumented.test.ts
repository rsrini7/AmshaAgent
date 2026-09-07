import { describe, expect, it } from 'vitest';
import { ArcHarnessConveyor, computeConveyorStations } from './arc-harness-conveyor';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-conv-1',
    sessionId: 'sess-1',
    turnNumber: 1,
    sequence: 0,
    stage: 'tool_start',
    station: 'station_07_tool_execution',
    status: 'COMPLETED',
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-01T12:00:00.000Z',
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    ...overrides,
  };
}

describe('Conveyor Non-Instrumented States & Anomaly Combo Badges', () => {
  it('treats unobserved stations as NOT_INSTRUMENTED without X-Ray inspection', () => {
    const conveyor = new ArcHarnessConveyor();
    conveyor.events = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'route', station: 'station_03_routing', status: 'COMPLETED' }),
    ];

    const rendered = conveyor.render();
    const str = JSON.stringify(rendered);

    // Station 04 Memory Decision is NOT_INSTRUMENTED
    expect(str).toContain('status-');
    expect(str).toContain('NOT_INSTRUMENTED');
    expect(str).toContain('No events observed');

    // Observed Station 01 has Inspect X-Ray button
    expect(str).toContain('Inspect X-Ray');
    expect(str).toContain('Inbound');
  });

  it('renders combo badges on Station 07 when retries or errors occur', () => {
    const conveyor = new ArcHarnessConveyor();
    conveyor.events = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'tool_error', station: 'station_07_tool_execution', status: 'ERROR', detail: 'Socket closed' }),
      mockTrace({ stage: 'retry', station: 'station_07_tool_execution', status: 'RETRY', detail: 'Backing off' }),
      mockTrace({ stage: 'retry', station: 'station_07_tool_execution', status: 'RETRY', detail: 'Backing off 2' }),
      mockTrace({ stage: 'tool_end', station: 'station_07_tool_execution', status: 'COMPLETED' }),
    ];

    const rendered = conveyor.render();
    const str = JSON.stringify(rendered);

    expect(str).toContain('x RETRY');
    expect(str).toContain('2');
    expect(str).toContain('x ERROR');
    expect(str).toContain('1');
  });

  it('renders stuck combo badge on Station 06 when loop_stuck occurs', () => {
    const conveyor = new ArcHarnessConveyor();
    conveyor.events = [
      mockTrace({ stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace({ stage: 'loop_stuck', station: 'station_06_reasoning', status: 'ALERT', detail: 'Duplicate pattern' }),
    ];

    const rendered = conveyor.render();
    const str = JSON.stringify(rendered);

    expect(str).toContain('LOOP STUCK');
  });
});
