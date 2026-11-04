package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.amsha.persistence.api.ActionLockScope;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAdmissionResult.AdmissionStatus;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptResolutionDto;
import com.amsha.persistence.api.ToolAttemptResolutionStatus;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Resolution, outbox, and retention contract tests for {@link SqliteToolAttemptStoreAdapter} per Proposal §12.8.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@SuppressWarnings("java:S1192")
class SqliteToolAttemptResolutionContractTest {

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
    void applyResolutionRejectsRetryAuthorization() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, true);
        ToolAttemptAuditOutboxDto resolvedOutbox = resolvedOutbox(fix.resId());

        boolean applied = store.applyResolution(workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox);
        assertFalse(applied);
        assertEquals(
                DurableActionLockStatus.ACTIVE,
                store.findActiveLock(workspaceId, fix.lockId()).orElseThrow().status());
    }

    @Test
    void applyResolutionClearsLockForNonRetry() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION, true);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());

        boolean applied = store.applyResolution(workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox);
        assertTrue(applied);
        assertTrue(store.findActiveLock(workspaceId, fix.lockId()).isEmpty());

        boolean repeat = store.applyResolution(workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox);
        assertFalse(repeat);
    }

    @Test
    void applyResolutionRejectsUnacknowledgedAuthorization() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION, false);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());

        boolean applied = store.applyResolution(workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox);
        assertFalse(applied);
        assertEquals(
                DurableActionLockStatus.ACTIVE,
                store.findActiveLock(workspaceId, fix.lockId()).orElseThrow().status());
    }

    @Test
    void applyResolutionAndAdmitRetryAtomic() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, true);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());
        ToolAttemptAdmissionRequest retryReq =
                createRequest(fix.opId(), fix.originAttemptId(), ToolAttemptIdempotencyMode.NON_IDEMPOTENT);

        ToolAttemptAdmissionResult retryRes = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, retryReq);
        assertEquals(AdmissionStatus.CREATED, retryRes.status());
        assertEquals(2, retryRes.attempt().attemptNumber());
        assertTrue(store.findActiveLock(workspaceId, fix.lockId()).isEmpty());
    }

    @Test
    void applyResolutionAndAdmitRetryRejectsUnacknowledgedAuthorization() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, false);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());
        ToolAttemptAdmissionRequest retryReq =
                createRequest(fix.opId(), fix.originAttemptId(), ToolAttemptIdempotencyMode.NON_IDEMPOTENT);

        ToolAttemptAdmissionResult retryRes = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, retryReq);
        assertEquals(AdmissionStatus.CONFLICT, retryRes.status());
        assertEquals(
                DurableActionLockStatus.ACTIVE,
                store.findActiveLock(workspaceId, fix.lockId()).orElseThrow().status());
    }

    @Test
    void applyResolutionAndAdmitRetryRejectsMismatchedFingerprintsOrOrigin() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, true);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());

        ToolAttemptAdmissionRequest mismatchOriginReq =
                createRequest(fix.opId(), UUID.randomUUID().toString(), ToolAttemptIdempotencyMode.NON_IDEMPOTENT);
        ToolAttemptAdmissionResult resMismatchOrigin = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, mismatchOriginReq);
        assertEquals(AdmissionStatus.CONFLICT, resMismatchOrigin.status());

        String otherFingerprint = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "c".repeat(64);
        ToolAttemptAdmissionRequest mismatchFpReq = new ToolAttemptAdmissionRequest(
                workspaceId,
                sessionId,
                fix.opId(),
                fix.originAttemptId(),
                "testTool",
                otherFingerprint,
                1,
                null,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                "DIRECT",
                "{}",
                Instant.now());
        ToolAttemptAdmissionResult resMismatchFp = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, mismatchFpReq);
        assertEquals(AdmissionStatus.CONFLICT, resMismatchFp.status());

        ToolAttemptAdmissionRequest mismatchTargetReq = new ToolAttemptAdmissionRequest(
                workspaceId,
                sessionId,
                fix.opId(),
                fix.originAttemptId(),
                "testTool",
                ACTION_FINGERPRINT,
                1,
                TARGET_FINGERPRINT,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                "DIRECT",
                "{}",
                Instant.now());
        ToolAttemptAdmissionResult resMismatchTarget = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, mismatchTargetReq);
        assertEquals(AdmissionStatus.CONFLICT, resMismatchTarget.status());
    }

    @Test
    void applyResolutionAndAdmitRetryReplayReturnsIdenticalExistingWithOutbox() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, true);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());
        ToolAttemptAdmissionRequest retryReq =
                createRequest(fix.opId(), fix.originAttemptId(), ToolAttemptIdempotencyMode.NON_IDEMPOTENT);

        ToolAttemptAdmissionResult res1 = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, retryReq);
        assertEquals(AdmissionStatus.CREATED, res1.status());
        assertNotNull(res1.admissionOutboxEvent());

        ToolAttemptAdmissionResult res2 = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, retryReq);
        assertEquals(AdmissionStatus.IDENTICAL_EXISTING, res2.status());
        assertEquals(res1.attempt().attemptId(), res2.attempt().attemptId());
        assertNotNull(res2.admissionOutboxEvent());
        assertEquals(
                res1.admissionOutboxEvent().eventId(),
                res2.admissionOutboxEvent().eventId());

        ResolutionFixture other = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, true);
        ToolAttemptAdmissionRequest otherRequest =
                createRequest(other.opId(), other.originAttemptId(), ToolAttemptIdempotencyMode.NON_IDEMPOTENT);
        ToolAttemptAdmissionResult otherRetry = store.applyResolutionAndAdmitRetry(
                workspaceId, other.resId(), other.lockId(), Instant.now(), resolvedOutbox(other.resId()), otherRequest);
        assertEquals(AdmissionStatus.CREATED, otherRetry.status());

        ToolAttemptAdmissionRequest crossOperationRequest =
                createRequest(other.opId(), fix.originAttemptId(), ToolAttemptIdempotencyMode.NON_IDEMPOTENT);
        ToolAttemptAdmissionResult crossOperationReplay = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, crossOperationRequest);
        assertEquals(AdmissionStatus.CONFLICT, crossOperationReplay.status());
    }

    @Test
    void applyResolutionAndAdmitRetryRejectsModeMismatch() {
        ResolutionFixture fix = setupResolution(ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY, true);
        ToolAttemptAuditOutboxDto resolvedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                fix.resId(),
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());
        ToolAttemptAdmissionRequest retryReq =
                createRequest(fix.opId(), fix.originAttemptId(), ToolAttemptIdempotencyMode.IDEMPOTENT);

        ToolAttemptAdmissionResult res = store.applyResolutionAndAdmitRetry(
                workspaceId, fix.resId(), fix.lockId(), Instant.now(), resolvedOutbox, retryReq);
        assertEquals(AdmissionStatus.CONFLICT, res.status());
    }

    @Test
    void prepareResolutionRejectsMismatchedOutbox() {
        String resId = UUID.randomUUID().toString();
        ToolAttemptResolutionDto resDto = new ToolAttemptResolutionDto(
                resId,
                UUID.randomUUID().toString(),
                workspaceId,
                "admin",
                ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                ToolAttemptResolutionStatus.PENDING,
                UUID.randomUUID().toString(),
                ACTION_FINGERPRINT,
                null,
                "ref",
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                null);
        ToolAttemptAuditOutboxDto mismatchedOutbox = ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                resId,
                ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED,
                "{}",
                Instant.now());

        assertThrows(IllegalArgumentException.class, () -> store.prepareResolution(resDto, mismatchedOutbox));
    }

    @Test
    void deleteClaimsBySessionPreservesOtherSessions() {
        String ses1 = "s1-" + UUID.randomUUID();
        String ses2 = "s2-" + UUID.randomUUID();
        ToolAttemptAdmissionRequest r1 = new ToolAttemptAdmissionRequest(
                workspaceId,
                ses1,
                UUID.randomUUID().toString(),
                null,
                "toolA",
                ACTION_FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.AT_MOST_ONCE,
                "DIRECT",
                "{}",
                Instant.now());
        ToolAttemptAdmissionRequest r2 = new ToolAttemptAdmissionRequest(
                workspaceId,
                ses2,
                UUID.randomUUID().toString(),
                null,
                "toolA",
                ACTION_FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.AT_MOST_ONCE,
                "DIRECT",
                "{}",
                Instant.now());

        store.admit(r1);
        store.admit(r2);

        assertEquals(1L, store.deleteClaimsBySession(workspaceId, ses1));
        assertTrue(store.findClaim(workspaceId, ses1, "toolA", 1, ACTION_FINGERPRINT)
                .isEmpty());
        assertTrue(store.findClaim(workspaceId, ses2, "toolA", 1, ACTION_FINGERPRINT)
                .isPresent());
    }

    @Test
    void outboxLeaseAcknowledgeAndReschedule() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String eventId = res.admissionOutboxEvent().eventId();

        List<ToolAttemptAuditOutboxDto> leased =
                store.leaseOutboxEvents(workspaceId, "lease-1", Instant.now().plus(5, ChronoUnit.MINUTES), 10);
        assertTrue(leased.stream().anyMatch(e -> e.eventId().equals(eventId)));

        boolean resched = store.rescheduleOutboxEvent(
                workspaceId, eventId, "lease-1", Instant.now().minusSeconds(1), 1);
        assertTrue(resched);

        List<ToolAttemptAuditOutboxDto> reLeased =
                store.leaseOutboxEvents(workspaceId, "lease-2", Instant.now().plus(5, ChronoUnit.MINUTES), 10);
        assertTrue(reLeased.stream().anyMatch(e -> e.eventId().equals(eventId)));

        boolean ack = store.acknowledgeOutboxEvent(
                workspaceId, eventId, "lease-2", UUID.randomUUID().toString(), Instant.now());
        assertTrue(ack);
        assertTrue(store.findOutboxEvent(workspaceId, eventId).orElseThrow().deliveredAt() != null);
    }

    @Test
    void pruneTerminalBeforeRespectsCutoffAndActiveState() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attemptId = res.attempt().attemptId();
        Instant past = Instant.now().minus(40, ChronoUnit.DAYS);

        store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(past),
                null,
                List.of());
        store.transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(past, "h", "obs"),
                null,
                List.of());

        long pruned = store.pruneTerminalBefore(workspaceId, Instant.now().minus(30, ChronoUnit.DAYS));
        assertTrue(pruned >= 1L);
        assertTrue(store.findAttempt(workspaceId, attemptId).isEmpty());
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
                null,
                mode,
                "DIRECT",
                "{}",
                Instant.now());
    }

    private ToolAttemptAuditOutboxDto resolvedOutbox(String resolutionId) {
        return ToolAttemptAuditOutboxDto.forResolution(
                UUID.randomUUID().toString(),
                workspaceId,
                sessionId,
                resolutionId,
                ToolAttemptAuditEventKind.ACTION_RESOLVED,
                "{}",
                Instant.now());
    }

    private record ResolutionFixture(String resId, String originAttemptId, String lockId, String opId) {}

    private ResolutionFixture setupResolution(String resolutionKind, boolean ackAuthOutbox) {
        String lockId = UUID.randomUUID().toString();
        String opId = UUID.randomUUID().toString();
        ToolAttemptAdmissionResult initialRes =
                store.admit(createRequest(opId, null, ToolAttemptIdempotencyMode.NON_IDEMPOTENT));
        String originAttemptId = initialRes.attempt().attemptId();
        store.transition(
                workspaceId,
                originAttemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()),
                null,
                List.of());

        DurableActionLockDto lock = new DurableActionLockDto(
                lockId,
                workspaceId,
                sessionId,
                opId,
                originAttemptId,
                ACTION_FINGERPRINT,
                null,
                ActionLockScope.EXACT_REQUEST,
                "failed",
                DurableActionLockStatus.ACTIVE,
                Instant.now(),
                null,
                null);
        store.transition(
                workspaceId,
                originAttemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_ERROR,
                ToolAttemptSettlementFields.forError(Instant.now(), "h", 1, "err"),
                lock,
                List.of());

        String resId = UUID.randomUUID().toString();
        String authOutboxId = UUID.randomUUID().toString();
        ToolAttemptResolutionDto resDto = new ToolAttemptResolutionDto(
                resId,
                lockId,
                workspaceId,
                "admin",
                resolutionKind,
                ToolAttemptResolutionStatus.PENDING,
                originAttemptId,
                ACTION_FINGERPRINT,
                null,
                "ref",
                authOutboxId,
                null,
                Instant.now(),
                null);
        ToolAttemptAuditOutboxDto authOutbox = ToolAttemptAuditOutboxDto.forResolution(
                authOutboxId,
                workspaceId,
                sessionId,
                resId,
                ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED,
                "{}",
                Instant.now());
        store.prepareResolution(resDto, authOutbox);

        if (ackAuthOutbox) {
            store.leaseOutboxEvents(workspaceId, "auth-lease", Instant.now().plus(5, ChronoUnit.MINUTES), 10);
            store.acknowledgeOutboxEvent(
                    workspaceId, authOutboxId, "auth-lease", UUID.randomUUID().toString(), Instant.now());
        }
        return new ResolutionFixture(resId, originAttemptId, lockId, opId);
    }
}
