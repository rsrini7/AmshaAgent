package com.amsha.core.observability;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.state.StatefulAgentStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeActivityObserverTest {

    @Mock
    StatefulAgentStore agentStore;

    @InjectMocks
    ResumeActivityObserver observer;

    @Test
    void toolCallStepMapsToToolPhase() {
        ReasoningStep step = new ReasoningStep.ToolCallStep(
                "think", "read_file", Map.of(), "obs", 1, 1, true, null, null, null, false, false);
        observer.onStepComplete("sess-1", "coder", step);
        verify(agentStore).refreshResuming(eq("sess-1"), eq("TOOL:read_file"));
    }

    @Test
    void observationStepMapsToObserving() {
        ReasoningStep step = new ReasoningStep.ObservationStep("think", "obs", 1, 1, true, null, null, null, false);
        observer.onStepComplete("sess-1", "coder", step);
        verify(agentStore).refreshResuming(eq("sess-1"), eq("OBSERVING"));
    }

    @Test
    void nullSessionIsNoOp() {
        ReasoningStep step = new ReasoningStep.ObservationStep("think", "obs", 1, 1, true, null, null, null, false);
        observer.onStepComplete(null, "coder", step);
        verify(agentStore, never()).refreshResuming(anyString(), anyString());
    }

    @Test
    void delegateSessionRefreshesParentMark() {
        // The observer receives the delegate's session id; the guard must also keep the
        // top-level session mark fresh so the busy message / status stay accurate.
        ReasoningStep step = mock(ReasoningStep.ToolCallStep.class);
        Mockito.when(step.action()).thenReturn("search");
        observer.onStepComplete("sess-1:delegate:abc", "coder", step);
        verify(agentStore).refreshResuming(eq("sess-1:delegate:abc"), eq("TOOL:search"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stepStartSchedulesPeriodicHeartbeatAndCompleteCancels() {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        Mockito.when(mockScheduler.scheduleWithFixedDelay(
                        runnableCaptor.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS)))
                .thenReturn(mockFuture);

        ResumeActivityObserver heartbeatObserver = new ResumeActivityObserver(agentStore, mockScheduler);

        // onStepStart triggers immediate refresh and schedules periodic task
        heartbeatObserver.onStepStart("sess-hb", "coder");
        verify(agentStore).refreshResuming(eq("sess-hb"), Mockito.isNull());

        // Heartbeat runnable executes and refreshes mark
        runnableCaptor.getValue().run();
        verify(agentStore, Mockito.times(2)).refreshResuming(eq("sess-hb"), Mockito.isNull());

        // onStepComplete cancels the heartbeat future
        ReasoningStep step = new ReasoningStep.ObservationStep("think", "obs", 1, 1, true, null, null, null, false);
        heartbeatObserver.onStepComplete("sess-hb", "coder", step);
        verify(mockFuture).cancel(false);
        verify(agentStore).refreshResuming(eq("sess-hb"), eq("OBSERVING"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loopCompleteCancelsHeartbeat() {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);

        Mockito.when(mockScheduler.scheduleWithFixedDelay(Mockito.any(), eq(15L), eq(15L), eq(TimeUnit.SECONDS)))
                .thenReturn(mockFuture);

        ResumeActivityObserver heartbeatObserver = new ResumeActivityObserver(agentStore, mockScheduler);

        heartbeatObserver.onStepStart("sess-loop", "coder");
        heartbeatObserver.onLoopComplete("sess-loop", "user-1", "coder", "hash", null);
        verify(mockFuture).cancel(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shutdownCancelsAllAndShutsDownScheduler() {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);

        Mockito.when(mockScheduler.scheduleWithFixedDelay(Mockito.any(), eq(15L), eq(15L), eq(TimeUnit.SECONDS)))
                .thenReturn(mockFuture);

        ResumeActivityObserver heartbeatObserver = new ResumeActivityObserver(agentStore, mockScheduler);

        heartbeatObserver.onStepStart("sess-shut", "coder");
        heartbeatObserver.shutdown();

        verify(mockFuture).cancel(false);
        verify(mockScheduler).shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchExecutionPreservesHeartbeatUntilBatchComplete() {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);

        Mockito.when(mockScheduler.scheduleWithFixedDelay(Mockito.any(), eq(15L), eq(15L), eq(TimeUnit.SECONDS)))
                .thenReturn(mockFuture);

        ResumeActivityObserver heartbeatObserver = new ResumeActivityObserver(agentStore, mockScheduler);

        heartbeatObserver.onStepStart("sess-batch", "coder");
        heartbeatObserver.onBatchStart("sess-batch", "coder", 3);

        // First parallel tool completes or times out — heartbeat must NOT be cancelled
        ReasoningStep tool1Step =
                new ReasoningStep.ObservationStep("think", "obs1", 1, 1, false, null, null, null, false);
        heartbeatObserver.onStepComplete("sess-batch", "coder", tool1Step);
        verify(mockFuture, Mockito.never()).cancel(Mockito.anyBoolean());

        // Batch completes — now the heartbeat should be cancelled
        heartbeatObserver.onBatchComplete("sess-batch", "coder");
        verify(mockFuture).cancel(false);
    }
}
