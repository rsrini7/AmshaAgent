package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import com.amsha.core.agent.sse.AgentSseService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TracePublisherTest {

    @BeforeEach
    @AfterEach
    void resetState() {
        TracePublisher.resetStateForTesting();
    }

    private static AgentTraceEvent sampleEvent(String detail) {
        return AgentTraceEvent.of(
                        "sess1:turn-1", "sess1", TracePublisher.DEFAULT_TURN_NUMBER, 0, TraceStage.GUARD_CHECK)
                .withStatus("ALLOW")
                .withDetail(detail);
    }

    @Test
    void publishNullIsNoOp() {
        TracePublisher publisher = new TracePublisher(mock(AgentSseService.class), new TraceRedactor());
        assertDoesNotThrow(() -> publisher.publish(null));
    }

    @Test
    void publishWithNoSubscriberDropsSilently() {
        // Real AgentSseService with no active subscriber: publish must not throw.
        AgentSseService sse = new AgentSseService();
        TracePublisher publisher = new TracePublisher(sse, new TraceRedactor());
        assertDoesNotThrow(() -> publisher.publish(sampleEvent("no subscriber here")));
    }

    @Test
    void publishRedactsAndSequencesEvent() {
        AgentSseService sse = mock(AgentSseService.class);
        TracePublisher publisher = new TracePublisher(sse, new TraceRedactor());

        publisher.publish(sampleEvent("api_key=sk-secret-value"));

        ArgumentCaptor<AgentSseService.SseEvent> captor = ArgumentCaptor.forClass(AgentSseService.SseEvent.class);
        verify(sse).publish(anyString(), captor.capture());

        AgentTraceEvent published = (AgentTraceEvent) captor.getValue();
        assertNotNull(published);
        // Sequence is assigned by the publisher (starts at 0 for a fresh run id).
        assertEquals(0, published.sequence());
        // Detail must be redacted: the secret value must not survive publication.
        assertFalse(published.detail().contains("sk-secret-value"), "secret leaked in trace detail");
        assertTrue(published.detail().contains(TraceRedactor.REDACTED));
    }

    @Test
    void multiTurnSessionAdvancesTurnNumberAndRunId() {
        String sessionId = "sess-turn-test-" + System.nanoTime();
        try {
            // First turn
            int turn1 = TracePublisher.startTurn(sessionId);
            assertEquals(1, turn1);
            assertEquals(1, TracePublisher.currentTurnNumber(sessionId));
            assertEquals(sessionId + ":turn-1", TracePublisher.runId(sessionId));

            AgentTraceEvent ev1 = TracePublisher.newEvent(sessionId, TraceStage.TURN_START);
            assertEquals(1, ev1.turnNumber());
            assertEquals(sessionId + ":turn-1", ev1.runId());

            // Second turn
            int turn2 = TracePublisher.startTurn(sessionId);
            assertEquals(2, turn2);
            assertEquals(2, TracePublisher.currentTurnNumber(sessionId));
            assertEquals(sessionId + ":turn-2", TracePublisher.runId(sessionId));

            AgentTraceEvent ev2 = TracePublisher.newEvent(sessionId, TraceStage.TURN_START);
            assertEquals(2, ev2.turnNumber());
            assertEquals(sessionId + ":turn-2", ev2.runId());
        } finally {
            TracePublisher.releaseSession(sessionId);
        }
    }

    @Test
    void releaseSessionResetsTurnCounterToDefault() {
        String sessionId = "sess-release-test-" + System.nanoTime();
        TracePublisher.startTurn(sessionId);
        TracePublisher.startTurn(sessionId);
        assertEquals(2, TracePublisher.currentTurnNumber(sessionId));

        TracePublisher.releaseSession(sessionId);
        assertEquals(TracePublisher.DEFAULT_TURN_NUMBER, TracePublisher.currentTurnNumber(sessionId));
    }

    @Test
    void newEventAttachesAuthoritativeParadigm() {
        String sessionId = "sess-paradigm-test-" + System.nanoTime();
        try {
            TracePublisher.startTurn(sessionId);
            AgentTraceEvent evReAct = TracePublisher.newEvent(sessionId, TraceStage.TURN_START, "REACT");
            assertEquals("REACT", evReAct.paradigm());

            AgentTraceEvent evMulti = TracePublisher.newEvent(sessionId, TraceStage.ROUTE, "MULTI_AGENT");
            assertEquals("MULTI_AGENT", evMulti.paradigm());

            AgentTraceEvent evNull = TracePublisher.newEvent(sessionId, TraceStage.TOOL_START);
            assertNull(evNull.paradigm());
        } finally {
            TracePublisher.releaseSession(sessionId);
        }
    }

    @Test
    void publishEnqueuesRedactedEventToPersistenceEngine() {
        AgentSseService sse = mock(AgentSseService.class);
        @SuppressWarnings("unchecked")
        Instance<TracePersistenceEngine> persistenceInstance = mock(Instance.class);
        TracePersistenceEngine engine = mock(TracePersistenceEngine.class);
        when(persistenceInstance.isResolvable()).thenReturn(true);
        when(persistenceInstance.get()).thenReturn(engine);

        TracePublisher publisher = new TracePublisher(sse, new TraceRedactor(), persistenceInstance);
        publisher.publish(sampleEvent("api_key=sk-secret-value"));

        ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(engine).enqueue(captor.capture());

        AgentTraceEvent enqueued = captor.getValue();
        assertNotNull(enqueued);
        assertFalse(enqueued.detail().contains("sk-secret-value"));
        assertTrue(enqueued.detail().contains(TraceRedactor.REDACTED));
    }

    @Test
    void resetStateForTestingClearsTurnState() {
        String sessionId = "sess-reset-test-" + System.nanoTime();
        TracePublisher.startTurn(sessionId);
        TracePublisher.startTurn(sessionId);
        assertEquals(2, TracePublisher.currentTurnNumber(sessionId));

        TracePublisher.resetStateForTesting();
        assertEquals(TracePublisher.DEFAULT_TURN_NUMBER, TracePublisher.currentTurnNumber(sessionId));
    }
}
