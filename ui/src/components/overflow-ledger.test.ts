import { describe, expect, it } from 'vitest';
import {
  ArcOverflowLedger,
  computeAnomalyCounts,
  extractAnomalies,
  isAnomalyEvent,
} from './arc-overflow-ledger';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId: 'ev-' + Math.random(),
    runId: 'run-anom-1',
    sessionId: 'sess-1',
    turnNumber: 1,
    sequence: 0,
    stage: 'tool_end',
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

describe('Combo Badges & Overflow Ledger', () => {
  it('identifies anomaly events correctly', () => {
    const cleanEv = mockTrace({ stage: 'tool_end', status: 'COMPLETED' });
    expect(isAnomalyEvent(cleanEv)).toBe(false);

    const errorEv = mockTrace({ stage: 'tool_error', status: 'ERROR' });
    expect(isAnomalyEvent(errorEv)).toBe(true);

    const retryEv = mockTrace({ stage: 'retry', status: 'RETRY' });
    expect(isAnomalyEvent(retryEv)).toBe(true);

    const stuckEv = mockTrace({ stage: 'loop_stuck', station: 'station_06_reasoning', status: 'ALERT' });
    expect(isAnomalyEvent(stuckEv)).toBe(true);
  });

  it('computes accurate anomaly and combo counts', () => {
    const events: AgentTraceEvent[] = [
      mockTrace({ stage: 'tool_end', status: 'COMPLETED' }),
      mockTrace({ stage: 'tool_error', status: 'ERROR', detail: 'Connection refused' }),
      mockTrace({ stage: 'retry', status: 'RETRY', detail: 'Retrying tool execution (1/3)' }),
      mockTrace({ stage: 'retry', status: 'RETRY', detail: 'Retrying tool execution (2/3)' }),
      mockTrace({ stage: 'loop_stuck', station: 'station_06_reasoning', status: 'ALERT', detail: 'Identical call repeated 3 times' }),
    ];

    const counts = computeAnomalyCounts(events);
    expect(counts.errorCount).toBe(1);
    expect(counts.retryCount).toBe(2);
    expect(counts.stuckCount).toBe(1);
    expect(counts.totalAnomalies).toBe(4);

    const anomalies = extractAnomalies(events);
    expect(anomalies).toHaveLength(4);
  });

  it('renders clean run badge when there are 0 anomalies', () => {
    const ledger = new ArcOverflowLedger();
    ledger.events = [
      mockTrace({ stage: 'tool_end', status: 'COMPLETED' }),
      mockTrace({ stage: 'turn_end', station: 'station_10_outbound', status: 'COMPLETED' }),
    ];

    const rendered = ledger.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('Clean Run');
    expect(str).not.toContain('RETRY');
    expect(str).not.toContain('LOOP STUCK');
  });

  it('renders combo badges and table when anomalies exist', () => {
    const ledger = new ArcOverflowLedger();
    ledger.toggleOpen();
    ledger.events = [
      mockTrace({
        stage: 'retry',
        station: 'station_07_tool_execution',
        status: 'RETRY',
        detail: 'Backoff 1000ms',
        timestamp: '2026-09-01T12:05:30.000Z',
      }),
      mockTrace({
        stage: 'retry',
        station: 'station_07_tool_execution',
        status: 'RETRY',
        detail: 'Backoff 2000ms',
        timestamp: '2026-09-01T12:05:32.000Z',
      }),
      mockTrace({
        stage: 'tool_error',
        station: 'station_07_tool_execution',
        status: 'ERROR',
        detail: 'HTTP 500 upstream server error',
        timestamp: '2026-09-01T12:05:34.000Z',
      }),
      mockTrace({
        stage: 'loop_stuck',
        station: 'station_06_reasoning',
        status: 'ALERT',
        detail: 'Threshold 3 reached: identical arguments',
        timestamp: '2026-09-01T12:05:35.000Z',
      }),
    ];

    const rendered = ledger.render();
    const str = JSON.stringify(rendered);

    // Combo badges
    expect(str).toContain('x RETRY');
    expect(str).toContain('2');
    expect(str).toContain('x TOOL ERROR');
    expect(str).toContain('1');
    expect(str).toContain('x LOOP STUCK');

    // Table rows
    expect(str).toContain('HTTP 500 upstream server error');
    expect(str).toContain('Threshold 3 reached: identical arguments');
  });

  it('toggles open state on demand', () => {
    const ledger = new ArcOverflowLedger();
    expect(ledger['_isOpen']).toBe(false);
    ledger.toggleOpen();
    expect(ledger['_isOpen']).toBe(true);
    ledger.toggleOpen();
    expect(ledger['_isOpen']).toBe(false);
  });
});
