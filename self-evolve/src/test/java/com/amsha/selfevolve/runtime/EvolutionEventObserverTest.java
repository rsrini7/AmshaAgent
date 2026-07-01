package com.amsha.selfevolve.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.events.CompactionEvent;
import com.amsha.core.events.ToolExecutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvolutionEventObserverTest {

    @Mock
    DefaultSelfEvolveService service;

    @InjectMocks
    EvolutionEventObserver observer;

    @Test
    void onToolExecution_disabled_doesNothing() {
        when(service.isEnabled()).thenReturn(false);
        ToolExecutionEvent event = ToolExecutionEvent.call("sandbox_exec", "s1", "u1", "coder", null, 1);
        observer.onToolExecution(event);
        verify(service, never()).emit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void onToolExecution_enabled_emitsEvent() {
        when(service.isEnabled()).thenReturn(true);
        ToolExecutionEvent event = ToolExecutionEvent.call("sandbox_exec", "s1", "u1", "coder", null, 1);
        observer.onToolExecution(event);
        verify(service, times(1))
                .emit(
                        eq("s1"),
                        eq("u1"),
                        eq(EvolutionEventType.TOOL_CALL),
                        eq("coder"),
                        any(),
                        eq("sandbox_exec"),
                        eq(1));
    }

    @Test
    void onCompaction_disabled_doesNothing() {
        when(service.isEnabled()).thenReturn(false);
        CompactionEvent event = new CompactionEvent("s1", "u1", 5, 2, "summary");
        observer.onCompaction(event);
        verify(service, never()).emit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void onCompaction_enabled_emitsEvent() {
        when(service.isEnabled()).thenReturn(true);
        CompactionEvent event = new CompactionEvent("s1", "u1", 5, 2, "summary");
        observer.onCompaction(event);
        verify(service, times(1))
                .emit(eq("s1"), eq("u1"), eq(EvolutionEventType.COMPACTION), eq(null), any(), eq(null), eq(null));
    }

    @Test
    void onStateChanged_disabled_doesNothing() {
        when(service.isEnabled()).thenReturn(false);
        AgentStateChangedEvent event = new AgentStateChangedEvent("s1", "coordinator", "idle", "goal");
        observer.onStateChanged(event);
        verify(service, never()).flushSession(any());
    }

    @Test
    void onStateChanged_enabled_flushesOnIdle() {
        when(service.isEnabled()).thenReturn(true);
        AgentStateChangedEvent event = new AgentStateChangedEvent("s1", "coordinator", "idle", "goal");
        observer.onStateChanged(event);
        verify(service, times(1)).flushSession("s1");
    }

    @Test
    void onStateChanged_enabled_flushesOnError() {
        when(service.isEnabled()).thenReturn(true);
        AgentStateChangedEvent event = new AgentStateChangedEvent("s1", "coordinator", "error", "goal");
        observer.onStateChanged(event);
        verify(service, times(1)).flushSession("s1");
    }

    @Test
    void onStateChanged_enabled_doesNotFlushOnWaitingApproval() {
        when(service.isEnabled()).thenReturn(true);
        AgentStateChangedEvent event = new AgentStateChangedEvent("s1", "coordinator", "waiting-approval", "goal");
        observer.onStateChanged(event);
        verify(service, never()).flushSession(any());
    }
}
