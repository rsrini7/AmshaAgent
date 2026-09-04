package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;

import com.amsha.persistence.api.ExecutionTraceStorePort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TraceRetentionReaperTest {

    @Test
    void pruneExpiredTracesWhenStoreUnsatisfiedReturnsZero() {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        when(storeInstance.isUnsatisfied()).thenReturn(true);

        TraceRetentionReaper reaper = new TraceRetentionReaper(storeInstance, 7, 50);
        assertEquals(0L, reaper.pruneExpiredTraces());
    }

    @Test
    void pruneExpiredTracesCallsPruneBeforeWithCutoffAndPruneExcessRuns() {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        ExecutionTraceStorePort storePort = mock(ExecutionTraceStorePort.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        when(storeInstance.get()).thenReturn(storePort);
        when(storePort.pruneBefore(any(Instant.class))).thenReturn(15L);
        when(storePort.pruneExcessRuns(30)).thenReturn(10L);

        TraceRetentionReaper reaper = new TraceRetentionReaper(storeInstance, 14, 30);
        long pruned = reaper.pruneExpiredTraces();
        assertEquals(25L, pruned);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(storePort).pruneBefore(captor.capture());
        verify(storePort).pruneExcessRuns(30);
        Instant cutoff = captor.getValue();
        // Cutoff should be approximately 14 days ago (allow within 10 seconds tolerance)
        long diffSeconds = Instant.now().getEpochSecond() - cutoff.getEpochSecond();
        assertEquals(14 * 86400L, diffSeconds, 10L);
    }

    @Test
    void pruneExpiredTracesCatchesExceptionAndReturnsZero() {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        ExecutionTraceStorePort storePort = mock(ExecutionTraceStorePort.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        when(storeInstance.get()).thenReturn(storePort);
        when(storePort.pruneBefore(any(Instant.class))).thenThrow(new RuntimeException("Database error"));
        when(storePort.pruneExcessRuns(50)).thenThrow(new RuntimeException("Count error"));

        TraceRetentionReaper reaper = new TraceRetentionReaper(storeInstance, 7, 50);
        long pruned = reaper.pruneExpiredTraces();
        assertEquals(0L, pruned);
    }

    @Test
    void scheduledPruneDelegatesToPruneExpiredTraces() {
        @SuppressWarnings("unchecked")
        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        ExecutionTraceStorePort storePort = mock(ExecutionTraceStorePort.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        when(storeInstance.get()).thenReturn(storePort);
        when(storePort.pruneBefore(any(Instant.class))).thenReturn(5L);
        when(storePort.pruneExcessRuns(50)).thenReturn(3L);

        TraceRetentionReaper reaper = new TraceRetentionReaper(storeInstance, 7, 50);
        reaper.scheduledPrune();
        verify(storePort).pruneBefore(any(Instant.class));
        verify(storePort).pruneExcessRuns(50);
    }
}
