import { describe, expect, it } from 'vitest';
import { EventDeduplicator } from '../api/event-dedup';
import { computeAnomalyCounts, extractAnomalies } from './arc-overflow-ledger';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

function mockTrace(eventId: string, overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    eventId,
    runId: 'run-reconnect-1',
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

describe('Reconnect De-duplication on Trace Streams', () => {
  it('prevents duplicate anomaly counting when SSE replays trace events upon reconnect', () => {
    const dedup = new EventDeduplicator(100);
    const receivedEvents: AgentTraceEvent[] = [];

    function onIncomingEvent(ev: AgentTraceEvent) {
      if (!dedup.accept(ev.eventId)) {
        return; // Dropped by deduplicator
      }
      receivedEvents.push(ev);
    }

    const event1 = mockTrace('ev-101', { stage: 'retry', status: 'RETRY', detail: 'Attempt 1' });
    const event2 = mockTrace('ev-102', { stage: 'tool_error', status: 'ERROR', detail: 'Connection reset' });
    const event3 = mockTrace('ev-103', { stage: 'loop_stuck', station: 'station_06_reasoning', status: 'ALERT' });

    // Initial stream delivery
    onIncomingEvent(event1);
    onIncomingEvent(event2);
    onIncomingEvent(event3);

    expect(receivedEvents).toHaveLength(3);
    const initialCounts = computeAnomalyCounts(receivedEvents);
    expect(initialCounts.retryCount).toBe(1);
    expect(initialCounts.errorCount).toBe(1);
    expect(initialCounts.stuckCount).toBe(1);
    expect(initialCounts.totalAnomalies).toBe(3);

    // Simulated network disconnect and SSE reconnect: server replays event1 and event2
    onIncomingEvent(event1); // Duplicate
    onIncomingEvent(event2); // Duplicate

    // Replay should NOT increase event count or anomaly counts
    expect(receivedEvents).toHaveLength(3);
    const postReconnectCounts = computeAnomalyCounts(receivedEvents);
    expect(postReconnectCounts.retryCount).toBe(1);
    expect(postReconnectCounts.errorCount).toBe(1);
    expect(postReconnectCounts.stuckCount).toBe(1);
    expect(postReconnectCounts.totalAnomalies).toBe(3);

    // Overflow ledger extractAnomalies has no duplicates
    const ledgerAnomalies = extractAnomalies(receivedEvents);
    expect(ledgerAnomalies).toHaveLength(3);

    // Subsequent fresh event
    const event4 = mockTrace('ev-104', { stage: 'tool_end', status: 'COMPLETED' });
    onIncomingEvent(event4);
    expect(receivedEvents).toHaveLength(4);
  });

  it('handles missing events gracefully when reconnect skips intermediate events', () => {
    // A partial trace where only start and end arrive
    const partialEvents: AgentTraceEvent[] = [
      mockTrace('ev-201', { stage: 'turn_start', station: 'station_01_inbound', status: 'START' }),
      mockTrace('ev-204', { stage: 'turn_end', station: 'station_10_outbound', status: 'COMPLETED' }),
    ];

    const counts = computeAnomalyCounts(partialEvents);
    expect(counts.totalAnomalies).toBe(0);

    const anomalies = extractAnomalies(partialEvents);
    expect(anomalies).toHaveLength(0);
  });
});
