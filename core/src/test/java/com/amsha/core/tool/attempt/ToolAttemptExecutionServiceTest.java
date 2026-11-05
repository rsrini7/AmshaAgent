package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.loop.ToolObservationSanitizer;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.ActionLockScope;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ToolAttemptExecutionServiceTest {
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String SESSION_ID = "session-1";
    private static final String OPERATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String ATTEMPT_ID = "00000000-0000-0000-0000-000000000002";
    private static final String ADMITTED_EVENT_ID = "00000000-0000-0000-0000-000000000003";
    private static final String ACTION_FINGERPRINT = "sha256:v1:" + "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-11-05T00:00:00Z");

    private final Instance<ToolAttemptStorePort> stores = mock();
    private final ToolAttemptStorePort store = mock();
    private final ActionFingerprintGenerator fingerprints = mock();
    private final ToolTargetResolver targetResolver = mock();
    private final ToolAttemptAuditOutboxRelay outboxRelay = mock();
    private final ToolObservationSanitizer sanitizer = mock();
    private final ToolAttemptExecutionService service = new ToolAttemptExecutionService(
            stores, fingerprints, targetResolver, outboxRelay, sanitizer, new ObjectMapper());
    private final ToolTargetResolution target =
            new ToolTargetResolution(ActionLockScope.SESSION_MUTATIONS, null, null, null);
    private final ToolAttemptAuditOutboxDto admissionEvent = ToolAttemptAuditOutboxDto.forAttempt(
            ADMITTED_EVENT_ID,
            WORKSPACE_ID,
            SESSION_ID,
            ATTEMPT_ID,
            ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
            "{}",
            CREATED_AT);

    @BeforeEach
    void setUp() {
        when(stores.isUnsatisfied()).thenReturn(false);
        when(stores.isAmbiguous()).thenReturn(false);
        when(stores.get()).thenReturn(store);
        when(fingerprints.generate(WORKSPACE_ID, SESSION_ID, "safe_tool", Map.of("value", "x")))
                .thenReturn(ACTION_FINGERPRINT);
        when(targetResolver.resolve(WORKSPACE_ID, SESSION_ID, "safe_tool", Map.of("value", "x")))
                .thenReturn(target);
        when(store.findApplicableLocks(WORKSPACE_ID, SESSION_ID, ACTION_FINGERPRINT, null))
                .thenReturn(List.of());
        when(store.findLatestAttemptByOperation(WORKSPACE_ID, OPERATION_ID)).thenReturn(Optional.empty());
        when(store.admit(any()))
                .thenReturn(ToolAttemptAdmissionResult.created(
                        attempt(ToolAttemptStatus.ADMITTED, 1, null, null, null, null), admissionEvent));
        when(outboxRelay.deliverRequired(eq(WORKSPACE_ID), anyString())).thenReturn(ADMITTED_EVENT_ID);
    }

    @Test
    void admissionMustAcknowledgeBeforeLaunchCanBegin() throws Exception {
        ToolAttemptExecutionService.Admission admission = admit();

        assertTrue(admission.shouldExecute());
        verify(outboxRelay).deliverRequired(WORKSPACE_ID, ADMITTED_EVENT_ID);
    }

    @Test
    void launchAuditIsAcknowledgedBeforeRunningTransition() throws Exception {
        stubTransitions(
                attempt(ToolAttemptStatus.LAUNCH_REQUESTED, 2, CREATED_AT.plusSeconds(1), null, null, null),
                attempt(
                        ToolAttemptStatus.RUNNING,
                        3,
                        CREATED_AT.plusSeconds(1),
                        CREATED_AT.plusSeconds(2),
                        null,
                        null));
        ToolAttemptExecutionService.Admission admission = admit();

        service.launchAndStart(admission.handle());

        InOrder order = inOrder(store, outboxRelay);
        order.verify(store)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(1L),
                        eq(ToolAttemptStatus.LAUNCH_REQUESTED),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList());
        order.verify(outboxRelay).deliverRequired(eq(WORKSPACE_ID), anyString());
        order.verify(store)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.LAUNCH_REQUESTED),
                        eq(2L),
                        eq(ToolAttemptStatus.RUNNING),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList());
    }

    @Test
    void sandboxAttemptRemainsLaunchRequestedUntilExecutorStartIsConfirmed() throws Exception {
        stubTransitions(
                attempt(ToolAttemptStatus.LAUNCH_REQUESTED, 2, CREATED_AT.plusSeconds(1), null, null, null),
                attempt(
                        ToolAttemptStatus.RUNNING,
                        3,
                        CREATED_AT.plusSeconds(1),
                        CREATED_AT.plusSeconds(2),
                        null,
                        null));
        ToolAttemptExecutionService.AttemptHandle handle = admit().handle();

        service.requestLaunch(handle, "podman-container:zc-attempt");
        assertEquals(ToolAttemptStatus.LAUNCH_REQUESTED, handle.attempt().status());
        service.markExecutionStarting(handle);
        assertEquals(ToolAttemptStatus.LAUNCH_REQUESTED, handle.attempt().status());
        service.markRunning(handle, "container:container-id");

        assertEquals(ToolAttemptStatus.RUNNING, handle.attempt().status());
        InOrder order = inOrder(store, outboxRelay);
        order.verify(outboxRelay).deliverRequired(eq(WORKSPACE_ID), anyString());
        order.verify(store)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.LAUNCH_REQUESTED),
                        eq(2L),
                        eq(ToolAttemptStatus.RUNNING),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList());
    }

    @Test
    void failureAfterExternalStartIsIndeterminateInsteadOfPrelaunchCancellation() throws Exception {
        stubTransitions(
                attempt(ToolAttemptStatus.LAUNCH_REQUESTED, 2, CREATED_AT.plusSeconds(1), null, null, null),
                attempt(
                        ToolAttemptStatus.RUNNING,
                        3,
                        CREATED_AT.plusSeconds(1),
                        CREATED_AT.plusSeconds(2),
                        null,
                        null));
        when(store.transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.LAUNCH_REQUESTED),
                        eq(2L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(ToolAttemptSettlementFields.class),
                        any(DurableActionLockDto.class),
                        anyList()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt(
                        ToolAttemptStatus.INDETERMINATE,
                        3,
                        CREATED_AT.plusSeconds(1),
                        null,
                        CREATED_AT.plusSeconds(2),
                        "start evidence unavailable")));
        when(sanitizer.sanitize("safe_tool", "start evidence unavailable")).thenReturn("start evidence unavailable");
        ToolAttemptExecutionService.AttemptHandle handle = admit().handle();
        service.requestLaunch(handle, "podman-container:zc-attempt");
        service.markExecutionStarting(handle);

        service.settle(handle, "safe_tool", ToolResponse.infraFailure("start evidence unavailable"));

        assertEquals(ToolAttemptStatus.INDETERMINATE, handle.attempt().status());
        verify(store)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.LAUNCH_REQUESTED),
                        eq(2L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(ToolAttemptSettlementFields.class),
                        any(DurableActionLockDto.class),
                        anyList());
    }

    @Test
    void successfulOutputIsSanitizedBeforeDurableSettlement() throws Exception {
        ToolAttemptExecutionService.AttemptHandle handle = runningHandle();
        when(sanitizer.sanitize("safe_tool", "raw result")).thenReturn("safe result");
        when(store.transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(3L),
                        eq(ToolAttemptStatus.SETTLED_SUCCESS),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList()))
                .thenReturn(ToolAttemptTransitionResult.applied(attempt(
                        ToolAttemptStatus.SETTLED_SUCCESS,
                        4,
                        CREATED_AT.plusSeconds(1),
                        CREATED_AT.plusSeconds(2),
                        CREATED_AT.plusSeconds(3),
                        "safe result")));

        ToolResponse result = service.settle(handle, "safe_tool", ToolResponse.success("raw result"));

        assertEquals("safe result", result.output());
        verify(store)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(3L),
                        eq(ToolAttemptStatus.SETTLED_SUCCESS),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList());
    }

    @Test
    void uncertainExecutionSettlesIndeterminateWithAnActiveLock() throws Exception {
        ToolAttemptExecutionService.AttemptHandle handle = runningHandle();
        when(sanitizer.sanitize("safe_tool", "uncertain")).thenReturn("uncertain");
        when(store.transition(
                        eq(WORKSPACE_ID),
                        eq(ATTEMPT_ID),
                        eq(ToolAttemptStatus.RUNNING),
                        eq(3L),
                        eq(ToolAttemptStatus.INDETERMINATE),
                        any(ToolAttemptSettlementFields.class),
                        any(DurableActionLockDto.class),
                        anyList()))
                .thenAnswer(invocation -> {
                    DurableActionLockDto lock = invocation.getArgument(6);
                    assertEquals(DurableActionLockStatus.ACTIVE, lock.status());
                    assertEquals("INDETERMINATE_OUTCOME", lock.reason());
                    return ToolAttemptTransitionResult.applied(attempt(
                            ToolAttemptStatus.INDETERMINATE,
                            4,
                            CREATED_AT.plusSeconds(1),
                            CREATED_AT.plusSeconds(2),
                            CREATED_AT.plusSeconds(3),
                            "uncertain"));
                });

        ToolResponse result = service.settle(handle, "safe_tool", ToolResponse.infraFailure("uncertain"));

        assertNotNull(result);
        assertEquals("uncertain", result.output());
    }

    private ToolAttemptExecutionService.Admission admit() throws Exception {
        return WorkspaceContext.callWithActiveWorkspace(
                WORKSPACE_ID,
                () -> service.admit(
                        SESSION_ID,
                        OPERATION_ID,
                        "safe_tool",
                        Map.of("value", "x"),
                        IdempotencyMode.NON_IDEMPOTENT,
                        ToolAttemptExecutionService.EXECUTION_KIND_BUILT_IN));
    }

    private ToolAttemptExecutionService.AttemptHandle runningHandle() throws Exception {
        stubTransitions(
                attempt(ToolAttemptStatus.LAUNCH_REQUESTED, 2, CREATED_AT.plusSeconds(1), null, null, null),
                attempt(
                        ToolAttemptStatus.RUNNING,
                        3,
                        CREATED_AT.plusSeconds(1),
                        CREATED_AT.plusSeconds(2),
                        null,
                        null));
        ToolAttemptExecutionService.Admission admission = admit();
        service.launchAndStart(admission.handle());
        return admission.handle();
    }

    private void stubTransitions(ToolAttemptDto launchRequested, ToolAttemptDto running) {
        when(store.transition(
                        anyString(),
                        anyString(),
                        any(ToolAttemptStatus.class),
                        anyLong(),
                        any(ToolAttemptStatus.class),
                        any(ToolAttemptSettlementFields.class),
                        nullable(DurableActionLockDto.class),
                        anyList()))
                .thenAnswer(invocation -> switch (invocation.<ToolAttemptStatus>getArgument(4)) {
                    case LAUNCH_REQUESTED -> ToolAttemptTransitionResult.applied(launchRequested);
                    case RUNNING -> ToolAttemptTransitionResult.applied(running);
                    default -> throw new IllegalStateException("Unexpected transition in test.");
                });
    }

    private static ToolAttemptDto attempt(
            ToolAttemptStatus status,
            long fencingToken,
            Instant launchRequestedAt,
            Instant runningAt,
            Instant settledAt,
            String observation) {
        return new ToolAttemptDto(
                WORKSPACE_ID,
                SESSION_ID,
                OPERATION_ID,
                ATTEMPT_ID,
                1,
                "safe_tool",
                ACTION_FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                status,
                fencingToken,
                ToolAttemptExecutionService.EXECUTION_KIND_BUILT_IN,
                null,
                null,
                status == ToolAttemptStatus.SETTLED_ERROR ? 1 : null,
                observation,
                CREATED_AT,
                launchRequestedAt,
                runningAt,
                settledAt);
    }
}
