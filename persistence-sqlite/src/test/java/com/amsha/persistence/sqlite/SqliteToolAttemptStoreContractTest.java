package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAdmissionResult.AdmissionStatus;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.amsha.persistence.api.ToolAttemptTransitionResult.TransitionStatus;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Contract suite for {@link SqliteToolAttemptStoreAdapter} per Proposal §12.8.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@SuppressWarnings("java:S1192")
class SqliteToolAttemptStoreContractTest {

    private static final String ACTION_FINGERPRINT = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);
    private static final String TARGET_FINGERPRINT = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX + "b".repeat(64);

    @Inject
    SqliteToolAttemptStoreAdapter store;

    private String workspaceId;
    private String sessionId;

    @BeforeEach
    void setUp() {
        workspaceId = "ws-" + UUID.randomUUID();
        sessionId = "ses-" + UUID.randomUUID();
    }

    @Test
    void admitInitialAndDuplicateIdentical() {
        String opId = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest req = createRequest(opId, null, ToolAttemptIdempotencyMode.IDEMPOTENT);

        ToolAttemptAdmissionResult res1 = store.admit(req);
        assertEquals(AdmissionStatus.CREATED, res1.status());
        assertNotNull(res1.attempt());
        assertEquals(ToolAttemptStatus.ADMITTED, res1.attempt().status());
        assertEquals(1, res1.attempt().attemptNumber());
        assertEquals(0L, res1.attempt().fencingToken());
        assertNotNull(res1.admissionOutboxEvent());
        assertEquals(
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                res1.admissionOutboxEvent().eventKind());

        ToolAttemptAdmissionResult res2 = store.admit(req);
        assertEquals(AdmissionStatus.IDENTICAL_EXISTING, res2.status());
        assertEquals(res1.attempt().attemptId(), res2.attempt().attemptId());
        assertNotNull(res2.admissionOutboxEvent());
    }

    @Test
    void admitRetryEligibilityAndIneligibleRejection() {
        String opId = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest initialReq = createRequest(opId, null, ToolAttemptIdempotencyMode.IDEMPOTENT);
        ToolAttemptAdmissionResult initialRes = store.admit(initialReq);
        String attempt1Id = initialRes.attempt().attemptId();

        store.transition(
                workspaceId,
                attempt1Id,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()),
                null,
                List.of());
        store.transition(
                workspaceId,
                attempt1Id,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h1", "ok"),
                null,
                List.of());

        ToolAttemptAdmissionRequest retrySuccessReq =
                createRequest(opId, attempt1Id, ToolAttemptIdempotencyMode.IDEMPOTENT);
        assertEquals(AdmissionStatus.CONFLICT, store.admit(retrySuccessReq).status());

        String op2Id = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest atMostOnceReq = createRequest(op2Id, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult atMostOnceRes = store.admit(atMostOnceReq);
        String amoAttempt1Id = atMostOnceRes.attempt().attemptId();

        store.transition(
                workspaceId,
                amoAttempt1Id,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                ToolAttemptSettlementFields.empty(Instant.now()),
                null,
                List.of());

        ToolAttemptAdmissionRequest amoRetryReq =
                createRequest(op2Id, amoAttempt1Id, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult amoRetryRes = store.admit(amoRetryReq);
        assertEquals(AdmissionStatus.CREATED, amoRetryRes.status());
        assertEquals(2, amoRetryRes.attempt().attemptNumber());

        ToolAttemptAdmissionRequest mismatchedModeRetryReq =
                createRequest(op2Id, amoAttempt1Id, ToolAttemptIdempotencyMode.IDEMPOTENT);
        assertEquals(
                AdmissionStatus.CONFLICT, store.admit(mismatchedModeRetryReq).status());
    }

    @Test
    void admitAtMostOnceClaimConflictAndExistingReplay() {
        String op1Id = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest req1 = createRequest(op1Id, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult res1 = store.admit(req1);
        assertEquals(AdmissionStatus.CREATED, res1.status());

        String op2Id = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest req2 = createRequest(op2Id, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult res2 = store.admit(req2);
        assertEquals(AdmissionStatus.CONFLICT, res2.status());

        store.transition(
                workspaceId,
                res1.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()),
                null,
                List.of());
        store.transition(
                workspaceId,
                res1.attempt().attemptId(),
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h", "obs"),
                null,
                List.of());

        String op3Id = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest req3 = createRequest(op3Id, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult res3 = store.admit(req3);
        assertEquals(AdmissionStatus.AT_MOST_ONCE_EXISTING, res3.status());
        assertEquals(res1.attempt().attemptId(), res3.attempt().attemptId());
    }

    @Test
    void transitionLifecycleTokenIncrementAndFencing() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attemptId = res.attempt().attemptId();

        ToolAttemptTransitionResult t1 = store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()),
                null,
                List.of());
        assertEquals(TransitionStatus.APPLIED, t1.status());
        assertEquals(1L, t1.updatedAttempt().fencingToken());
        assertEquals(ToolAttemptStatus.LAUNCH_REQUESTED, t1.updatedAttempt().status());

        ToolAttemptTransitionResult staleToken = store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                0L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"),
                null,
                List.of());
        assertEquals(TransitionStatus.STALE, staleToken.status());

        ToolAttemptTransitionResult staleStatus = store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                1L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()),
                null,
                List.of());
        assertEquals(TransitionStatus.STALE, staleStatus.status());

        ToolAttemptTransitionResult t2 = store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"),
                null,
                List.of());
        assertEquals(TransitionStatus.APPLIED, t2.status());
        assertEquals(2L, t2.updatedAttempt().fencingToken());
    }

    @Test
    void transitionTerminalImmutability() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attemptId = res.attempt().attemptId();

        store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()),
                null,
                List.of());
        store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h", "obs"),
                null,
                List.of());

        ToolAttemptTransitionResult postTerminal = store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                2L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"),
                null,
                List.of());
        assertEquals(TransitionStatus.CONFLICT, postTerminal.status());
    }

    @Test
    void transitionClaimProgressionAndCancellationPurge() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.AT_MOST_ONCE));
        String attemptId = res.attempt().attemptId();
        assertTrue(store.findClaim(workspaceId, sessionId, "testTool", 1, ACTION_FINGERPRINT)
                .isPresent());

        store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                ToolAttemptSettlementFields.empty(Instant.now()),
                null,
                List.of());
        assertTrue(store.findClaim(workspaceId, sessionId, "testTool", 1, ACTION_FINGERPRINT)
                .isEmpty());
    }

    @Test
    void listWorkspaceIdsReturnsKnownWorkspaces() {
        store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        Set<String> workspaces = store.listWorkspaceIds();
        assertTrue(workspaces.contains(workspaceId));
    }

    @Test
    void findActiveAttemptsReturnsOnlyActiveInDeterministicOrderWithWorkspaceIsolation() {
        String otherWorkspace = "ws-other-" + UUID.randomUUID();
        store.admit(new ToolAttemptAdmissionRequest(
                otherWorkspace,
                sessionId,
                UUID.randomUUID().toString(),
                null,
                "testTool",
                ACTION_FINGERPRINT,
                1,
                TARGET_FINGERPRINT,
                ToolAttemptIdempotencyMode.IDEMPOTENT,
                "DIRECT",
                "{}",
                Instant.now()));

        var a1 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));

        var a2 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        store.transition(
                workspaceId,
                a2.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now(), "podman-container:c2"),
                null,
                List.of());

        var a3 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        store.transition(
                workspaceId,
                a3.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now(), "podman-container:c3"),
                null,
                List.of());
        store.transition(
                workspaceId,
                a3.attempt().attemptId(),
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "podman-container:c3", "container:c3"),
                null,
                List.of());

        var a4 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        store.transition(
                workspaceId,
                a4.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now(), "podman-container:c4"),
                null,
                List.of());
        store.transition(
                workspaceId,
                a4.attempt().attemptId(),
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "podman-container:c4", "done"),
                null,
                List.of());

        var a5 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        store.transition(
                workspaceId,
                a5.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                ToolAttemptSettlementFields.empty(Instant.now()),
                null,
                List.of());

        List<ToolAttemptDto> active = store.findActiveAttempts(workspaceId);
        assertEquals(3, active.size());
        assertEquals(a1.attempt().attemptId(), active.get(0).attemptId());
        assertEquals(a2.attempt().attemptId(), active.get(1).attemptId());
        assertEquals(a3.attempt().attemptId(), active.get(2).attemptId());
        assertEquals(ToolAttemptStatus.ADMITTED, active.get(0).status());
        assertEquals(ToolAttemptStatus.LAUNCH_REQUESTED, active.get(1).status());
        assertEquals(ToolAttemptStatus.RUNNING, active.get(2).status());

        assertThrows(IllegalArgumentException.class, () -> store.findActiveAttempts(null));
        assertThrows(IllegalArgumentException.class, () -> store.findActiveAttempts("   "));
    }

    private ToolAttemptAdmissionRequest createRequest(
            String opId, String retryOfAttemptId, ToolAttemptIdempotencyMode mode) {
        return new ToolAttemptAdmissionRequest(
                workspaceId,
                sessionId,
                opId,
                retryOfAttemptId,
                "testTool",
                ACTION_FINGERPRINT,
                1,
                TARGET_FINGERPRINT,
                mode,
                "DIRECT",
                "{}",
                Instant.now());
    }
}
