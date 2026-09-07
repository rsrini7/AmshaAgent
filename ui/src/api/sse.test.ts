import { afterEach, describe, expect, it, vi } from 'vitest';

import { SseManager } from './sse';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

/**
 * Builds an SSE body that stays open, mirroring a live connection. It is deliberately never
 * closed so the manager does not enter its reconnect path (which is browser-scheduled).
 */
function openEventStream(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

function traceEvent(eventId: string, stage: AgentTraceEvent['stage']): string {
  return JSON.stringify({
    eventId,
    eventType: TRACE_EVENT_TYPE,
    version: 1,
    runId: 'run-1',
    sessionId: 'session-1',
    turnNumber: 1,
    sequence: 0,
    stage,
    station: null,
    status: null,
    riskLevel: null,
    detail: null,
    metrics: null,
    timestamp: '2026-09-01T10:15:30Z',
  });
}

/** Waits until {@link predicate} holds, letting the manager's async read loop drain. */
async function waitFor(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

describe('SseManager eventId de-duplication', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('dispatches a replayed eventId exactly once', async () => {
    const received: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        openEventStream([
          `data: ${traceEvent('event-1', 'guard_check')}\n\n`,
          `data: ${traceEvent('event-1', 'guard_check')}\n\n`,
          `data: ${traceEvent('event-2', 'tool_start')}\n\n`,
        ]),
      ),
    );

    const manager = new SseManager(
      '/api/v1/chat/stream',
      'owner-key',
      (event) => received.push(event),
      () => {},
    );
    await manager.connect();
    await waitFor(() => received.length === 2);
    // Give any wrongly-dispatched duplicate a chance to arrive.
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(received).toHaveLength(2);
    manager.disconnect();
  });

  it('never de-duplicates legacy events that carry no eventId', async () => {
    const received: Array<{ eventType?: string }> = [];
    const legacy = JSON.stringify({ eventType: 'AWAITING_APPROVAL', sessionId: 'session-1' });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(openEventStream([`data: ${legacy}\n\n`, `data: ${legacy}\n\n`])),
    );

    const manager = new SseManager(
      '/api/v1/chat/stream',
      'owner-key',
      (event) => received.push(event as { eventType?: string }),
      () => {},
    );
    await manager.connect();
    await waitFor(() => received.length === 2);

    // Heartbeats and HITL replays are intentionally repeated by the server.
    expect(received).toHaveLength(2);
    manager.disconnect();
  });

  it('ignores heartbeat comments and blank data lines', async () => {
    const received: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        openEventStream([
          ': heartbeat\n\n',
          'data: \n\n',
          `data: ${traceEvent('event-9', 'turn_start')}\n\n`,
        ]),
      ),
    );

    const manager = new SseManager(
      '/api/v1/chat/stream',
      'owner-key',
      (event) => received.push(event),
      () => {},
    );
    await manager.connect();
    await waitFor(() => received.length === 1);

    expect(received).toHaveLength(1);
    manager.disconnect();
  });

  it('permits re-processing of eventId after resetDedup()', async () => {
    const received: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          openEventStream([
            `data: ${traceEvent('event-repeat', 'guard_check')}\n\n`,
          ]),
        ),
      ),
    );

    const manager = new SseManager(
      '/api/v1/chat/stream',
      'owner-key',
      (event) => received.push(event),
      () => {},
    );
    await manager.connect();
    await waitFor(() => received.length === 1);
    manager.disconnect();

    // Reset dedup
    manager.resetDedup();

    // Connect again with same eventId
    await manager.connect();
    await waitFor(() => received.length === 2);
    expect(received).toHaveLength(2);
    manager.disconnect();
  });
});
