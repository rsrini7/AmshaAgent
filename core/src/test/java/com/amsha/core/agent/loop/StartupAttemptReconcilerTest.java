package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxRecoveryEvidence;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.ActionLockScope;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StartupAttemptReconcilerTest {

    private static final String ACTION_FP = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);
    private static final String TARGET_FP = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX + "b".repeat(64);

    private ToolAttemptStorePort attemptStore;
    private IdentityStorePort identityStore;
    private SandboxExecutionPort sandboxPort;
    private CheckpointStorePort checkpointStore;
    private ToolObservationSanitizer observationSanitizer;
    private StartupCheckpointOperationMatcher operationMatcher;

    @BeforeEach
    void setUp() {
        attemptStore = mock(ToolAttemptStorePort.class);
        identityStore = mock(IdentityStorePort.class);
        sandboxPort = mock(SandboxExecutionPort.class);
        checkpointStore = mock(CheckpointStorePort.class);
        observationSanitizer = mock(ToolObservationSanitizer.class);
        operationMatcher = mock(StartupCheckpointOperationMatcher.class);
        when(operationMatcher.matches(any(), anyString(), anyString())).thenReturn(true);
    }

    private StartupAttemptReconciler createReconciler(boolean singleInstance) {
        return new StartupAttemptReconciler(
                attemptStore,
                identityStore,
                sandboxPort,
                checkpointStore,
                observationSanitizer,
                operationMatcher,
                singleInstance);
    }

    private void mockSingleAttempt(String ws, ToolAttemptDto attempt) {
        when(attemptStore.listWorkspaceIds()).thenReturn(Set.of(ws));
        when(identityStore.listWorkspaces()).thenReturn(List.of());
        when(attemptStore.findActiveAttempts(ws)).thenReturn(List.of(attempt));
    }

    @Test
    void enumerateWorkspacesCombinesDeduplicatesAndPreservesOpaqueIds() {
        when(attemptStore.listWorkspaceIds()).thenReturn(Set.of("ws-1", "ws:opaque/special", "ws-common"));
        Instant now = Instant.now();
        when(identityStore.listWorkspaces())
                .thenReturn(List.of(
                        new IdentityWorkspaceDto("ws-common", "Common", "admin", now, now),
                        new IdentityWorkspaceDto("ws-2", "WS 2", "admin", now, now)));

        StartupAttemptReconciler reconciler = createReconciler(true);
        Set<String> workspaces = reconciler.enumerateWorkspaces();
        assertEquals(4, workspaces.size());
        assertTrue(workspaces.contains("ws-1") && workspaces.contains("ws:opaque/special"));
        assertTrue(workspaces.contains("ws-common") && workspaces.contains("ws-2"));
    }

    @Test
    void admittedAttemptWithAbandonedCheckpointTransitionsToCancelledBeforeLaunch() {
        String ws = "ws-1";
        ToolAttemptDto attempt = createAttempt(ws, "session-1", ToolAttemptStatus.ADMITTED, 0L, null, null, null);
        mockSingleAttempt(ws, attempt);
        when(checkpointStore.findLatestNonArchivedBySession(attempt.sessionId()))
                .thenReturn(Optional.empty());
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        verify(attemptStore)
                .transition(
                        eq(ws),
                        eq(attempt.attemptId()),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(0L),
                        eq(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH),
                        any(),
                        isNull(),
                        any());
    }

    @Test
    void admittedAttemptWithActiveCheckpointRemainsAdmitted() {
        String ws = "ws-1";
        ToolAttemptDto attempt = createAttempt(ws, "session-1", ToolAttemptStatus.ADMITTED, 0L, null, null, null);
        mockSingleAttempt(ws, attempt);
        when(checkpointStore.findLatestNonArchivedBySession(attempt.sessionId()))
                .thenReturn(Optional.of(createCheckpoint(attempt.sessionId(), "AWAITING_APPROVAL")));

        createReconciler(true).reconcileAllWorkspaces();

        verify(attemptStore, never()).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void admittedAttemptWithTerminalCheckpointMatchingToolIsCancelled() {
        String ws = "ws-1";
        ToolAttemptDto attempt = createAttempt(ws, "session-1", ToolAttemptStatus.ADMITTED, 0L, null, null, null);
        mockSingleAttempt(ws, attempt);
        when(checkpointStore.findLatestNonArchivedBySession(attempt.sessionId()))
                .thenReturn(Optional.of(createCheckpoint(attempt.sessionId(), "COMPLETED", "shell_command")));
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        verify(attemptStore)
                .transition(
                        eq(ws),
                        eq(attempt.attemptId()),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(0L),
                        eq(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH),
                        any(),
                        isNull(),
                        any());
    }

    @Test
    void inProcessRunningAttemptTransitionsToIndeterminateWithConservativeLockWhenNoTarget() {
        String ws = "ws-1";
        ToolAttemptDto attempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 2L, "IN_PROCESS", null, null);
        mockSingleAttempt(ws, attempt);

        ArgumentCaptor<DurableActionLockDto> lockCaptor = ArgumentCaptor.forClass(DurableActionLockDto.class);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), lockCaptor.capture(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        DurableActionLockDto lock = lockCaptor.getValue();
        assertEquals(ActionLockScope.SESSION_MUTATIONS, lock.scope());
        assertNull(lock.targetFingerprint());
        verifyNoInteractions(sandboxPort);
    }

    @Test
    void inProcessRunningAttemptWithValidTargetFingerprintSelectsTargetResourceLockScope() {
        String ws = "ws-1";
        ToolAttemptDto attempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 2L, "IN_PROCESS", null, TARGET_FP);
        mockSingleAttempt(ws, attempt);

        ArgumentCaptor<DurableActionLockDto> lockCaptor = ArgumentCaptor.forClass(DurableActionLockDto.class);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), lockCaptor.capture(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        DurableActionLockDto lock = lockCaptor.getValue();
        assertEquals(ActionLockScope.TARGET_RESOURCE, lock.scope());
        assertEquals(TARGET_FP, lock.targetFingerprint());
    }

    @Test
    void runningVerifiedExecutorTerminatedAndTransitionsToIndeterminate() {
        String ws = "ws-1";
        ToolAttemptDto attempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 2L, "SANDBOX", "podman-container:c1", null);
        mockSingleAttempt(ws, attempt);
        when(sandboxPort.inspectAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity()))
                .thenReturn(new SandboxRecoveryEvidence.RunningVerified("podman-container:c1", "container:c1"));
        when(sandboxPort.terminateRunningAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity()))
                .thenReturn(true);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        verify(sandboxPort)
                .terminateRunningAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity());
        verify(attemptStore)
                .transition(
                        eq(ws),
                        eq(attempt.attemptId()),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(2L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(),
                        notNull(),
                        any());
    }

    @Test
    void runningVerifiedExecutorTerminationUnconfirmedTransitionsToIndeterminateWithLock() {
        String ws = "ws-1";
        ToolAttemptDto attempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 2L, "SANDBOX", "podman-container:c1", null);
        mockSingleAttempt(ws, attempt);
        when(sandboxPort.inspectAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity()))
                .thenReturn(new SandboxRecoveryEvidence.RunningVerified("podman-container:c1", "container:c1"));
        when(sandboxPort.terminateRunningAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity()))
                .thenReturn(false);
        ArgumentCaptor<ToolAttemptSettlementFields> fieldsCaptor =
                ArgumentCaptor.forClass(ToolAttemptSettlementFields.class);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), fieldsCaptor.capture(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        verify(sandboxPort)
                .terminateRunningAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity());
        verify(attemptStore)
                .transition(
                        eq(ws),
                        eq(attempt.attemptId()),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(2L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(),
                        notNull(),
                        any());
        assertTrue(fieldsCaptor.getValue().sanitizedObservation().contains("stop unconfirmed"));
    }

    @Test
    void exitedVerifiedWithSuccessSanitizesOutputAndTransitionsToSettledSuccess() {
        String ws = "ws-1";
        ToolAttemptDto attempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 2L, "SANDBOX", "podman-container:c1", null);
        mockSingleAttempt(ws, attempt);
        when(sandboxPort.inspectAttempt(
                        ws, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity()))
                .thenReturn(new SandboxRecoveryEvidence.ExitedVerified(
                        "podman-container:c1", "container:c1", 0, "raw output"));
        when(observationSanitizer.sanitize("shell_command", "raw output")).thenReturn("sanitized output");

        ArgumentCaptor<ToolAttemptSettlementFields> fieldsCaptor =
                ArgumentCaptor.forClass(ToolAttemptSettlementFields.class);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), fieldsCaptor.capture(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        verify(observationSanitizer).sanitize("shell_command", "raw output");
        assertEquals("sanitized output", fieldsCaptor.getValue().sanitizedObservation());
        assertEquals(0, fieldsCaptor.getValue().exitCode());
    }

    @Test
    void cronSessionAttemptReconciledUnderItsRecordedWorkspace() {
        String ws = "ws-cron-authority";
        ToolAttemptDto attempt =
                createAttempt(ws, "cron:session:100", ToolAttemptStatus.RUNNING, 3L, "IN_PROCESS", null, null);
        mockSingleAttempt(ws, attempt);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt));

        createReconciler(true).reconcileAllWorkspaces();

        verify(attemptStore)
                .transition(
                        eq(ws),
                        eq(attempt.attemptId()),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(3L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(),
                        notNull(),
                        any());
    }

    @Test
    void workspaceContextIsPropagatedDuringWorkspaceReconciliation() {
        String ws = "ws-context-test";
        AtomicReference<String> boundWorkspace = new AtomicReference<>();
        when(attemptStore.listWorkspaceIds()).thenReturn(Set.of(ws));
        when(identityStore.listWorkspaces()).thenReturn(List.of());
        when(attemptStore.findActiveAttempts(ws)).thenAnswer(_ -> {
            boundWorkspace.set(WorkspaceContext.activeWorkspaceId().orElse(null));
            return List.of();
        });

        createReconciler(true).reconcileAllWorkspaces();

        assertEquals(ws, boundWorkspace.get());
    }

    @Test
    void restartReconciliationIsIdempotentOnSubsequentRuns() {
        String ws = "ws-idempotent";
        ToolAttemptDto runningAttempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 1L, "SANDBOX", "podman-container:c1", null);
        when(attemptStore.listWorkspaceIds()).thenReturn(Set.of(ws));
        when(identityStore.listWorkspaces()).thenReturn(List.of());
        when(attemptStore.findActiveAttempts(ws))
                .thenReturn(List.of(runningAttempt))
                .thenReturn(List.of());
        when(sandboxPort.inspectAttempt(
                        ws,
                        runningAttempt.attemptId(),
                        runningAttempt.executionHandle(),
                        runningAttempt.processStartIdentity()))
                .thenReturn(new SandboxRecoveryEvidence.ExitedVerified(
                        "podman-container:c1", "container:c1", 0, "success observation"));
        when(observationSanitizer.sanitize(any(), any())).thenReturn("success observation");
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.applied(runningAttempt));

        StartupAttemptReconciler reconciler = createReconciler(true);
        reconciler.reconcileAllWorkspaces();
        verify(attemptStore, times(1)).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());

        reconciler.reconcileAllWorkspaces();
        verify(attemptStore, times(1)).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void failClosedWhenAttemptStoreFailsWorkspaceEnumeration() {
        when(attemptStore.listWorkspaceIds()).thenThrow(new RuntimeException("DB offline"));
        StartupAttemptReconciler reconciler = createReconciler(true);
        assertThrows(IllegalStateException.class, reconciler::reconcileAllWorkspaces);
    }

    @Test
    void failClosedWhenAttemptTransitionIsRejected() {
        String ws = "ws-1";
        ToolAttemptDto attempt =
                createAttempt(ws, "session-1", ToolAttemptStatus.RUNNING, 1L, "IN_PROCESS", null, null);
        mockSingleAttempt(ws, attempt);
        when(attemptStore.transition(any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolAttemptTransitionResult.conflict("fencing collision"));

        StartupAttemptReconciler reconciler = createReconciler(true);
        assertThrows(IllegalStateException.class, reconciler::reconcileAllWorkspaces);
    }

    @Test
    void missingIdentityStoreFailsClosedDuringWorkspaceEnumeration() {
        StartupAttemptReconciler reconciler = new StartupAttemptReconciler(
                attemptStore, null, sandboxPort, checkpointStore, observationSanitizer, true);
        assertThrows(IllegalStateException.class, reconciler::enumerateWorkspaces);
    }

    @Test
    void missingSanitizerFailsClosedDuringStartupReconciliation() {
        when(attemptStore.listWorkspaceIds()).thenReturn(Set.of("ws-1"));
        when(identityStore.listWorkspaces()).thenReturn(List.of());
        StartupAttemptReconciler reconciler =
                new StartupAttemptReconciler(attemptStore, identityStore, sandboxPort, checkpointStore, null, true);
        assertThrows(IllegalStateException.class, reconciler::reconcileAllWorkspaces);
    }

    @Test
    void admittedAttemptWithUnknownCheckpointStatusFailsClosedWithoutCancelling() {
        String ws = "ws-1";
        ToolAttemptDto attempt = createAttempt(ws, "session-1", ToolAttemptStatus.ADMITTED, 0L, null, null, null);
        mockSingleAttempt(ws, attempt);
        when(checkpointStore.findLatestNonArchivedBySession(attempt.sessionId()))
                .thenReturn(Optional.of(createCheckpoint(attempt.sessionId(), "UNKNOWN_STATUS_XYZ")));

        StartupAttemptReconciler reconciler = createReconciler(true);
        IllegalStateException ex = assertThrows(IllegalStateException.class, reconciler::reconcileAllWorkspaces);
        assertTrue(ex.getCause() != null && ex.getCause().getMessage().contains("Cannot verify abandonment"));
        verify(attemptStore, never()).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    private ToolAttemptDto createAttempt(
            String workspaceId,
            String sessionId,
            ToolAttemptStatus status,
            long fencingToken,
            String executionKind,
            String executionHandle,
            String targetFp) {
        Instant now = Instant.now();
        return new ToolAttemptDto(
                workspaceId,
                sessionId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1,
                "shell_command",
                ACTION_FP,
                1,
                targetFp,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                status,
                fencingToken,
                executionKind == null ? "SANDBOX" : executionKind,
                executionHandle,
                executionHandle == null ? null : "container:c1",
                null,
                null,
                now,
                status == ToolAttemptStatus.ADMITTED ? null : now,
                status == ToolAttemptStatus.RUNNING ? now : null,
                null);
    }

    private CheckpointDto createCheckpoint(String sessionId, String status) {
        return createCheckpoint(sessionId, status, "shell_command");
    }

    private CheckpointDto createCheckpoint(String sessionId, String status, String pendingTool) {
        Instant now = Instant.now();
        return new CheckpointDto(
                1L,
                sessionId,
                "DEVELOPER",
                status,
                "SEMI_AUTONOMOUS",
                "{}",
                "cli",
                "INTERNAL",
                "goal",
                null,
                null,
                null,
                pendingTool,
                null,
                null,
                null,
                10,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now,
                1);
    }
}
