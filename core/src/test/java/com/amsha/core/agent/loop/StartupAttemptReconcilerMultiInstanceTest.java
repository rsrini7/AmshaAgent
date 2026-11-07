package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
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
import com.amsha.persistence.api.ToolAttemptTransitionResult;

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
    void launchedAttemptsSettleIndeterminateWithLocksWithoutInspectingPeers() {
        String workspaceId = "ws-1";
        ToolAttemptDto launchRequested = createAttempt(workspaceId, ToolAttemptStatus.LAUNCH_REQUESTED, 1L);
        ToolAttemptDto running = createAttempt(workspaceId, ToolAttemptStatus.RUNNING, 2L);
        List<ToolAttemptDto> attempts = List.of(launchRequested, running);
        stubWorkspaces(attempts);
        when(attemptStore.transition(anyString(), anyString(), any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String attemptId = invocation.getArgument(1);
                    ToolAttemptDto attempt = attempts.stream()
                            .filter(candidate -> candidate.attemptId().equals(attemptId))
                            .findFirst()
                            .orElseThrow();
                    return ToolAttemptTransitionResult.applied(attempt);
                });

        reconciler(false).reconcileAllWorkspaces();

        verifyNoInteractions(sandboxPort);
        verify(attemptStore)
                .transition(
                        eq(workspaceId),
                        eq(launchRequested.attemptId()),
                        eq(ToolAttemptStatus.LAUNCH_REQUESTED),
                        eq(1L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(),
                        notNull(),
                        any());
        verify(attemptStore)
                .transition(
                        eq(workspaceId),
                        eq(running.attemptId()),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(2L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(),
                        notNull(),
                        any());
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
