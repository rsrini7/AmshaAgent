package com.amsha.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import com.amsha.persistence.api.ToolAttemptStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolAttemptRetentionSchedulerTest {

    @Mock
    Instance<ToolAttemptStorePort> storePortInstance;

    @Mock
    ToolAttemptStorePort storePort;

    private ToolAttemptRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ToolAttemptRetentionScheduler();
        setField(scheduler, "storePortInstance", storePortInstance);
        setField(scheduler, "retentionDuration", Duration.ofDays(30));
        setField(scheduler, "retentionEnabled", true);
    }

    @Test
    void runDailyPruneWhenEnabledPrunesAllWorkspaces() {
        when(storePortInstance.isUnsatisfied()).thenReturn(false);
        when(storePortInstance.get()).thenReturn(storePort);
        when(storePort.listWorkspaceIds()).thenReturn(Set.of("ws-1", "ws-2"));
        when(storePort.pruneTerminalBefore(eq("ws-1"), any(Instant.class))).thenReturn(5L);
        when(storePort.pruneTerminalBefore(eq("ws-2"), any(Instant.class))).thenReturn(3L);

        scheduler.runDailyPrune();

        verify(storePort).pruneTerminalBefore(eq("ws-1"), any(Instant.class));
        verify(storePort).pruneTerminalBefore(eq("ws-2"), any(Instant.class));
    }

    @Test
    void runDailyPruneSkippedWhenDisabled() {
        setField(scheduler, "retentionEnabled", false);

        scheduler.runDailyPrune();

        verifyNoInteractions(storePortInstance);
        verifyNoInteractions(storePort);
    }

    @Test
    void runDailyPruneSkippedWhenStorePortUnsatisfied() {
        when(storePortInstance.isUnsatisfied()).thenReturn(true);

        scheduler.runDailyPrune();

        verify(storePortInstance, never()).get();
        verifyNoInteractions(storePort);
    }

    @Test
    void pruneHandlesWorkspaceExceptionGracefullyAndContinues() {
        when(storePortInstance.isUnsatisfied()).thenReturn(false);
        when(storePortInstance.get()).thenReturn(storePort);
        when(storePort.listWorkspaceIds()).thenReturn(Set.of("ws-fail", "ws-ok"));
        when(storePort.pruneTerminalBefore(eq("ws-fail"), any(Instant.class)))
                .thenThrow(new RuntimeException("DB error"));
        when(storePort.pruneTerminalBefore(eq("ws-ok"), any(Instant.class))).thenReturn(7L);

        Instant fixedNow = Instant.parse("2026-11-04T12:00:00Z");
        long pruned = scheduler.pruneNow(fixedNow);

        assertEquals(7L, pruned);
        verify(storePort).pruneTerminalBefore(eq("ws-fail"), eq(fixedNow.minus(Duration.ofDays(30))));
        verify(storePort).pruneTerminalBefore(eq("ws-ok"), eq(fixedNow.minus(Duration.ofDays(30))));
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
