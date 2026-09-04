package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

class TracePersistenceEngineTest {

    private static AgentTraceEvent createSampleEvent(long sequence) {
        return new AgentTraceEvent(
                "evt-" + sequence,
                "sess-1:turn-1",
                "sess-1",
                1,
                sequence,
                TraceStage.TOOL_START,
                StationCode.STATION_07_TOOL_EXECUTION,
                "OK",
                "LOW",
                "details",
                new AgentTraceEvent.TraceMetrics(100, 50, 120L, 1, 1),
                Instant.now(),
                "REACT");
    }

    @Test
    void toDtoAndToEventRoundTrip() {
        AgentTraceEvent event = createSampleEvent(42L);
        ExecutionTraceDto dto = TracePersistenceEngine.toDto(event);

        assertEquals(event.eventId(), dto.eventId());
        assertEquals(event.runId(), dto.runId());
        assertEquals(event.sessionId(), dto.sessionId());
        assertEquals(event.turnNumber(), dto.turnNumber());
        assertEquals(event.sequence(), dto.sequence());
        assertEquals("tool_start", dto.stage());
        assertEquals("station_07_tool_execution", dto.station());
        assertEquals("OK", dto.status());
        assertEquals("LOW", dto.riskLevel());
        assertEquals("details", dto.detail());
        assertNotNull(dto.promptTokens());
        assertEquals(100, dto.promptTokens());
        assertEquals(50, dto.completionTokens());
        assertEquals(120L, dto.durationMs());
        assertEquals("REACT", dto.paradigm());

        AgentTraceEvent restored = TracePersistenceEngine.toEvent(dto);
        assertEquals(event.eventId(), restored.eventId());
        assertEquals(event.runId(), restored.runId());
        assertEquals(event.sessionId(), restored.sessionId());
        assertEquals(event.turnNumber(), restored.turnNumber());
        assertEquals(event.sequence(), restored.sequence());
        assertEquals(event.stage(), restored.stage());
        assertEquals(event.station(), restored.station());
        assertEquals(event.status(), restored.status());
        assertEquals(event.riskLevel(), restored.riskLevel());
        assertEquals(event.detail(), restored.detail());
        assertNotNull(restored.metrics());
        assertEquals(100, restored.metrics().promptTokens());
        assertEquals(50, restored.metrics().completionTokens());
        assertEquals(120L, restored.metrics().durationMs());
        assertEquals(event.paradigm(), restored.paradigm());
    }

    @Test
    void enqueueNullIsNoOp() {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        TracePersistenceEngine engine = new TracePersistenceEngine(storeInstance, 100);

        assertDoesNotThrow(() -> engine.enqueue(null));
        assertEquals(0, engine.queueSize());
        assertEquals(0, engine.droppedCount());
    }

    @Test
    void queueCapacityDropsWhenFull() {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        // Small capacity 2, no drain thread started
        TracePersistenceEngine engine = new TracePersistenceEngine(storeInstance, 2);

        engine.enqueue(createSampleEvent(0));
        engine.enqueue(createSampleEvent(1));
        assertEquals(2, engine.queueSize());
        assertEquals(0, engine.droppedCount());

        // 3rd event should be dropped
        engine.enqueue(createSampleEvent(2));
        assertEquals(2, engine.queueSize());
        assertEquals(1, engine.droppedCount());
    }

    @Test
    void startupAndDrainBatchPersistsToStore() throws Exception {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        ExecutionTraceStorePort storePort = mock(ExecutionTraceStorePort.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        when(storeInstance.get()).thenReturn(storePort);

        TracePersistenceEngine engine = new TracePersistenceEngine(storeInstance, 100);
        engine.onStartup(new StartupEvent());

        try {
            engine.enqueue(createSampleEvent(0));
            engine.enqueue(createSampleEvent(1));

            // Wait for background drain loop
            long deadline = System.currentTimeMillis() + 2000;
            while (engine.queueSize() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(100);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ExecutionTraceDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(storePort, atLeastOnce()).saveBatch(captor.capture());

            List<ExecutionTraceDto> allSaved = new ArrayList<>();
            for (List<ExecutionTraceDto> batch : captor.getAllValues()) {
                allSaved.addAll(batch);
            }
            assertEquals(2, allSaved.size());
        } finally {
            engine.onShutdown(new ShutdownEvent());
        }
    }

    @Test
    void drainErrorCountsEventsAsDropped() throws Exception {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        // An Error (not an Exception) escapes persistBatch's own guards, so this exercises the
        // drain loop's terminal catch. Regression guard: a batch lost to a drain failure used
        // to be discarded without incrementing the dropped counter, silently under-reporting.
        when(storeInstance.get()).thenThrow(new Error("simulated fatal drain error"));

        TracePersistenceEngine engine = new TracePersistenceEngine(storeInstance, 100);
        engine.onStartup(new StartupEvent());

        try {
            engine.enqueue(createSampleEvent(0));
            engine.enqueue(createSampleEvent(1));

            long deadline = System.currentTimeMillis() + 5000;
            while (engine.droppedCount() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(
                    engine.droppedCount() >= 2,
                    "events lost to a drain error must be reflected in droppedCount(), was " + engine.droppedCount());
        } finally {
            engine.onShutdown(new ShutdownEvent());
        }
    }

    @Test
    void batchFailureRetriesIndividuallyAndCountsDropped() throws Exception {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        ExecutionTraceStorePort storePort = mock(ExecutionTraceStorePort.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        when(storeInstance.get()).thenReturn(storePort);

        Mockito.doThrow(new RuntimeException("DB Batch Failure"))
                .when(storePort)
                .saveBatch(any());
        Mockito.doThrow(new RuntimeException("DB Single Failure"))
                .when(storePort)
                .save(any());

        TracePersistenceEngine engine = new TracePersistenceEngine(storeInstance, 100);
        engine.onStartup(new StartupEvent());

        try {
            engine.enqueue(createSampleEvent(0));
            engine.enqueue(createSampleEvent(1));

            long deadline = System.currentTimeMillis() + 2000;
            while (engine.queueSize() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(100);

            // Both events should have been retried individually and counted as dropped
            assertEquals(2, engine.droppedCount());
        } finally {
            engine.onShutdown(new ShutdownEvent());
        }
    }
}
