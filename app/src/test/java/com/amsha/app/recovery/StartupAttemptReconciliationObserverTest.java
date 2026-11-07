package com.amsha.app.recovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import com.amsha.app.db.ToolAttemptSchemaGuard;
import com.amsha.core.agent.loop.StartupAttemptReconciler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.StartupEvent;

class StartupAttemptReconciliationObserverTest {

    private ToolAttemptSchemaGuard schemaGuard;
    private StartupAttemptReconciler reconciler;
    private StartupAttemptReconciliationObserver observer;

    @BeforeEach
    void setUp() {
        schemaGuard = mock(ToolAttemptSchemaGuard.class);
        reconciler = mock(StartupAttemptReconciler.class);
        observer = new StartupAttemptReconciliationObserver(schemaGuard, reconciler);
    }

    @Test
    void whenSchemaGuardVerifiedReconciliationIsTriggered() {
        when(schemaGuard.isVerified()).thenReturn(true);

        observer.onStartup(new StartupEvent());

        verify(reconciler).reconcileAllWorkspaces();
    }

    @Test
    void whenSchemaGuardNotVerifiedThrowsIllegalStateException() {
        when(schemaGuard.isVerified()).thenReturn(false);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> observer.onStartup(new StartupEvent()));
        assertTrue(ex.getMessage().contains("ToolAttemptSchemaGuard did not verify schema contract"));
        verifyNoInteractions(reconciler);
    }

    @Test
    void whenSchemaGuardNullThrowsIllegalStateException() {
        StartupAttemptReconciliationObserver nullGuardObserver =
                new StartupAttemptReconciliationObserver(null, reconciler);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> nullGuardObserver.onStartup(new StartupEvent()));
        assertTrue(ex.getMessage().contains("ToolAttemptSchemaGuard did not verify schema contract"));
        verifyNoInteractions(reconciler);
    }

    @Test
    void whenReconcilerNullThrowsIllegalStateException() {
        when(schemaGuard.isVerified()).thenReturn(true);
        StartupAttemptReconciliationObserver nullReconcilerObserver =
                new StartupAttemptReconciliationObserver(schemaGuard, null);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> nullReconcilerObserver.onStartup(new StartupEvent()));
        assertTrue(ex.getMessage().contains("StartupAttemptReconciler is not resolvable"));
    }

    @Test
    void whenReconcilerThrowsExceptionStartupFailsClosed() {
        when(schemaGuard.isVerified()).thenReturn(true);
        doThrow(new RuntimeException("Reconciliation failed unexpectedly"))
                .when(reconciler)
                .reconcileAllWorkspaces();

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> observer.onStartup(new StartupEvent()));
        assertTrue(ex.getMessage().contains("Startup attempt reconciliation failed"));
    }

    @Test
    void startupObserverHasPriority30AfterSchemaGuardPriority20() throws Exception {
        Method obsMethod =
                StartupAttemptReconciliationObserver.class.getDeclaredMethod("onStartup", StartupEvent.class);
        Parameter obsParam = obsMethod.getParameters()[0];
        assertTrue(obsParam.isAnnotationPresent(Observes.class));
        assertTrue(obsParam.isAnnotationPresent(Priority.class));
        Priority obsPriority = obsParam.getAnnotation(Priority.class);
        assertEquals(30, obsPriority.value(), "Observer must run at @Priority(30)");

        Method guardMethod = ToolAttemptSchemaGuard.class.getDeclaredMethod("onStart", StartupEvent.class);
        Priority guardPriority = guardMethod.getAnnotation(Priority.class);
        assertNotNull(guardPriority, "ToolAttemptSchemaGuard.onStart must have @Priority");
        assertEquals(20, guardPriority.value());
        assertTrue(
                guardPriority.value() < obsPriority.value(),
                "SchemaGuard priority (20) must fire before ReconciliationObserver (30)");
    }
}
