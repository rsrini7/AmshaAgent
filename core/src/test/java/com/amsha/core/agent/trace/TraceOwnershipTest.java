package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.amsha.core.agent.sse.AgentSseService;

import org.junit.jupiter.api.Test;

/**
 * Issue 03 AC1 — ownership / session scoping: a trace event is delivered only to the session that
 * produced it, so cross-session leakage is impossible.
 *
 * <p>Scoping is structural rather than a runtime check: {@link AgentSseService} keeps one
 * {@code BroadcastProcessor} per {@code sessionId}, and {@link TracePublisher#publish} routes using
 * the event's own {@code sessionId}. These tests pin that invariant so a future refactor to a
 * shared/global broadcast cannot silently leak one tenant's execution trace to another.
 */
class TraceOwnershipTest {

    private final AgentSseService sse = new AgentSseService();
    private final TracePublisher publisher = new TracePublisher(sse, new TraceRedactor());

    private List<AgentTraceEvent> subscribeTraceOnly(String sessionId) {
        List<AgentTraceEvent> traceEvents = new CopyOnWriteArrayList<>();
        sse.subscribe(sessionId).subscribe().with(event -> {
            if (event instanceof AgentTraceEvent traceEvent) {
                traceEvents.add(traceEvent);
            }
        });
        return traceEvents;
    }

    private static AgentTraceEvent traceFor(String sessionId, TraceStage stage) {
        return AgentTraceEvent.of(
                        TracePublisher.runId(sessionId), sessionId, TracePublisher.DEFAULT_TURN_NUMBER, 0, stage)
                .withStation(StationCode.STATION_05_TOOL_GUARD)
                .withStatus("START");
    }

    @Test
    void sessionADoesNotReceiveSessionBTraceEvents() {
        List<AgentTraceEvent> receivedByA = subscribeTraceOnly("session-A");

        publisher.publish(traceFor("session-B", TraceStage.TOOL_START));
        publisher.publish(traceFor("session-B", TraceStage.TOOL_END));

        assertTrue(receivedByA.isEmpty(), "session A must never receive session B's trace events");
    }

    @Test
    void sessionReceivesOnlyItsOwnTraceEvents() {
        List<AgentTraceEvent> receivedByA = subscribeTraceOnly("session-A");

        publisher.publish(traceFor("session-B", TraceStage.TOOL_START));
        publisher.publish(traceFor("session-A", TraceStage.TOOL_START));

        assertEquals(1, receivedByA.size(), "session A must receive exactly its own event");
        assertEquals("session-A", receivedByA.get(0).sessionId());
    }

    @Test
    void concurrentSessionsRemainIsolated() {
        List<AgentTraceEvent> receivedByA = subscribeTraceOnly("session-A");
        List<AgentTraceEvent> receivedByB = subscribeTraceOnly("session-B");

        publisher.publish(traceFor("session-A", TraceStage.GUARD_CHECK));
        publisher.publish(traceFor("session-B", TraceStage.GUARD_CHECK));
        publisher.publish(traceFor("session-C", TraceStage.GUARD_CHECK));

        assertEquals(1, receivedByA.size());
        assertEquals(1, receivedByB.size());
        assertEquals("session-A", receivedByA.get(0).sessionId());
        assertEquals("session-B", receivedByB.get(0).sessionId());
    }
}
