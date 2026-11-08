package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StartupAttemptReconcilerMultiInstanceTest {

    private static final String ACTION_FP = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);

    private ToolAttemptStorePort attemptStore;
    private IdentityStorePort identityStore;
    private SandboxExecutionPort sandboxPort;
    private CheckpointStorePort checkpointStore;
    private ToolObservationSanitizer observationSanitizer;

    @BeforeEach
    void setUp() {
        attemptStore = mock(ToolAttemptStorePort.class);
        identityStore = mock(IdentityStorePort.class);
        sandboxPort = mock(SandboxExecutionPort.class);
        checkpointStore = mock(CheckpointStorePort.class);
        observationSanitizer = mock(ToolObservationSanitizer.class);
    }

    @Test
    void admittedAttemptIsNotCancelledWithoutSingleInstanceOwnership() {
        String workspaceId = "ws-1";
        ToolAttemptDto admitted = createAttempt(workspaceId, ToolAttemptStatus.ADMITTED, 0L);
        stubWorkspaces(List.of(admitted));

        assertThrows(IllegalStateException.class, () -> reconciler(false).reconcileAllWorkspaces());
        verifyNoInteractions(sandboxPort);
        verify(attemptStore, Mockito.never()).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void launchedAttemptsHaltWithoutSingleInstanceOwnership() {
        String workspaceId = "ws-1";
        ToolAttemptDto launchRequested = createAttempt(workspaceId, ToolAttemptStatus.LAUNCH_REQUESTED, 1L);
        stubWorkspaces(List.of(launchRequested));

        assertThrows(IllegalStateException.class, () -> reconciler(false).reconcileAllWorkspaces());
        verifyNoInteractions(sandboxPort);
        verify(attemptStore, Mockito.never()).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void runningAttemptHaltsWithoutSingleInstanceOwnership() {
        String workspaceId = "ws-1";
        ToolAttemptDto running = createAttempt(workspaceId, ToolAttemptStatus.RUNNING, 2L);
        stubWorkspaces(List.of(running));

        assertThrows(IllegalStateException.class, () -> reconciler(false).reconcileAllWorkspaces());
        verifyNoInteractions(sandboxPort);
        verify(attemptStore, Mockito.never()).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    private void stubWorkspaces(List<ToolAttemptDto> attempts) {
        when(attemptStore.listWorkspaceIds()).thenReturn(Set.of("ws-1"));
        when(identityStore.listWorkspaces()).thenReturn(List.of());
        when(attemptStore.findActiveAttempts("ws-1")).thenReturn(attempts);
    }

    private StartupAttemptReconciler reconciler(boolean singleInstance) {
        return new StartupAttemptReconciler(
                attemptStore, identityStore, sandboxPort, checkpointStore, observationSanitizer, singleInstance);
    }

    private ToolAttemptDto createAttempt(String workspaceId, ToolAttemptStatus status, long fencingToken) {
        Instant now = Instant.now();
        return new ToolAttemptDto(
                workspaceId,
                "session-1",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1,
                "shell_command",
                ACTION_FP,
                1,
                null,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                status,
                fencingToken,
                "SANDBOX",
                "podman-container:" + UUID.randomUUID(),
                "container:" + UUID.randomUUID(),
                null,
                null,
                now,
                status == ToolAttemptStatus.ADMITTED ? null : now,
                status == ToolAttemptStatus.RUNNING ? now : null,
                null);
    }
}
