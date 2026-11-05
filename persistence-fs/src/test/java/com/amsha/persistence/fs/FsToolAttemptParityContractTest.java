package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.ActionLockScope;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAdmissionResult.AdmissionStatus;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.amsha.persistence.api.ToolAttemptTransitionResult.TransitionStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-backend parity and retention verification suite for {@link FsToolAttemptStoreAdapter}
 * per Proposal §10 Phase A exit gate and Proposal §12.8 Store Contract matrix.
 */
@SuppressWarnings({"java:S1192", "java:S5443"})
class FsToolAttemptParityContractTest {

    private static final String ACTION_FINGERPRINT = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);
    private static final String TARGET_FINGERPRINT = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX + "b".repeat(64);

    private Path tempDir;
    private FsToolAttemptHelper helper;
    private FsToolAttemptStoreAdapter store;
    private String workspaceId;
    private String sessionId;

    private record TestFsProps(String dataDir, int lockTimeoutSeconds, String luceneIndexDir, int vectorDimension)
            implements FsStoreProperties {}

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-tool-attempt-parity-contract-");
        FilesystemFileStore fileStore =
                new FilesystemFileStore(new TestFsProps(tempDir.toString(), 5, "lucene-index", 768));
        fileStore.ensureDataDir();
        helper = new FsToolAttemptHelper(fileStore);
        store = new FsToolAttemptStoreAdapter(helper);
        workspaceId = "ws-" + UUID.randomUUID();
        sessionId = "ses-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var s = Files.walk(tempDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException expected) {
                        // Best effort cleanup of temp files
                    }
                });
            }
        }
    }

    @Test
    void workspaceIsolationAcrossAllEntities() {
        String wsA = workspaceId;
        String wsB = "ws-" + UUID.randomUUID();
        String opA = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest reqA =
                createRequest(wsA, sessionId, opA, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult resA = store.admit(reqA);
        assertEquals(AdmissionStatus.CREATED, resA.status());
        String attemptIdA = resA.attempt().attemptId();
        String outboxIdA = resA.admissionOutboxEvent().eventId();

        transition(
                wsA,
                attemptIdA,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        String lockIdA = UUID.randomUUID().toString();
        DurableActionLockDto lockA = new DurableActionLockDto(
                lockIdA,
                wsA,
                sessionId,
                opA,
                attemptIdA,
                ACTION_FINGERPRINT,
                null,
                ActionLockScope.EXACT_REQUEST,
                "err",
                DurableActionLockStatus.ACTIVE,
                Instant.now(),
                null,
                null);
        transition(
                wsA,
                attemptIdA,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_ERROR,
                ToolAttemptSettlementFields.forError(Instant.now(), "h", 1, "err"),
                lockA);

        assertTrue(store.findAttempt(wsB, attemptIdA).isEmpty());
        assertTrue(store.findClaim(wsB, sessionId, "testTool", 1, ACTION_FINGERPRINT)
                .isEmpty());
        assertTrue(store.findActiveLock(wsB, lockIdA).isEmpty());
        assertTrue(store.findOutboxEvent(wsB, outboxIdA).isEmpty());
        assertTrue(store.leaseOutboxEvents(wsB, "lease-b", Instant.now().plus(5, ChronoUnit.MINUTES), 10)
                .isEmpty());

        ToolAttemptTransitionResult crossWsTrans = transition(
                wsB,
                attemptIdA,
                ToolAttemptStatus.SETTLED_ERROR,
                2L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"));
        assertEquals(TransitionStatus.CONFLICT, crossWsTrans.status());

        assertEquals(0L, store.deleteClaimsBySession(wsB, sessionId));
        assertTrue(store.findClaim(wsA, sessionId, "testTool", 1, ACTION_FINGERPRINT)
                .isPresent());

        assertEquals(0L, store.pruneTerminalBefore(wsB, Instant.now().plusSeconds(60)));
        assertTrue(store.findAttempt(wsA, attemptIdA).isPresent());

        String opB = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest reqB =
                createRequest(wsB, sessionId, opB, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult resB = store.admit(reqB);
        assertEquals(AdmissionStatus.CREATED, resB.status());
        assertNotEquals(attemptIdA, resB.attempt().attemptId());
    }

    @Test
    void staleFencingTokenAndNonExistentAttemptTransition() {
        ToolAttemptTransitionResult nonExistent = transition(
                workspaceId,
                UUID.randomUUID().toString(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        assertEquals(TransitionStatus.CONFLICT, nonExistent.status());

        ToolAttemptAdmissionResult res = store.admit(createRequest(
                workspaceId, sessionId, UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attemptId = res.attempt().attemptId();

        ToolAttemptTransitionResult t1 = transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        assertEquals(TransitionStatus.APPLIED, t1.status());
        assertEquals(1L, t1.updatedAttempt().fencingToken());

        ToolAttemptTransitionResult staleToken = transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                0L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"));
        assertEquals(TransitionStatus.STALE, staleToken.status());

        ToolAttemptTransitionResult futureToken = transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                5L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"));
        assertEquals(TransitionStatus.STALE, futureToken.status());

        ToolAttemptTransitionResult staleStatus = transition(
                workspaceId,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                1L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        assertEquals(TransitionStatus.STALE, staleStatus.status());

        assertEquals(1L, store.findAttempt(workspaceId, attemptId).orElseThrow().fencingToken());
        assertEquals(
                ToolAttemptStatus.LAUNCH_REQUESTED,
                store.findAttempt(workspaceId, attemptId).orElseThrow().status());
    }

    @Test
    void atMostOnceClaimLifecycleTerminalProgression() {
        assertClaimProgressionForTerminalStatus(ToolAttemptStatus.SETTLED_SUCCESS, ActionClaimStatus.CONSUMED, true);
        assertClaimProgressionForTerminalStatus(ToolAttemptStatus.SETTLED_ERROR, ActionClaimStatus.CONSUMED, true);
        assertClaimProgressionForTerminalStatus(ToolAttemptStatus.TIMED_OUT, ActionClaimStatus.CONSUMED, true);
        assertClaimProgressionForTerminalStatus(ToolAttemptStatus.INDETERMINATE, ActionClaimStatus.RESERVED, false);
        assertClaimProgressionForTerminalStatus(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH, null, false);
    }

    @Test
    void retentionPruningBoundariesAndProtectedEntities() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant past = cutoff.minus(10, ChronoUnit.DAYS);
        Instant recent = cutoff.plus(10, ChronoUnit.DAYS);

        String opPrunable = UUID.randomUUID().toString();
        ToolAttemptAdmissionResult resPrunable = store.admit(
                createRequest(workspaceId, sessionId, opPrunable, null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attPrunableId = resPrunable.attempt().attemptId();
        advanceToSettled(workspaceId, attPrunableId, past, ToolAttemptStatus.SETTLED_SUCCESS, "obs");

        String opRecent = UUID.randomUUID().toString();
        ToolAttemptAdmissionResult resRecent = store.admit(
                createRequest(workspaceId, sessionId, opRecent, null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attRecentId = resRecent.attempt().attemptId();
        advanceToSettled(workspaceId, attRecentId, recent, ToolAttemptStatus.SETTLED_SUCCESS, "obs");

        String opActive = UUID.randomUUID().toString();
        ToolAttemptAdmissionResult resActive = store.admit(
                createRequest(workspaceId, sessionId, opActive, null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attActiveId = resActive.attempt().attemptId();
        transition(
                workspaceId,
                attActiveId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(past));
        transition(
                workspaceId,
                attActiveId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(past, "h", "pid"));

        String sesClaim = "ses-claim-" + UUID.randomUUID();
        String opClaim = UUID.randomUUID().toString();
        ToolAttemptAdmissionResult resClaim = store.admit(
                createRequest(workspaceId, sesClaim, opClaim, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE));
        String attClaimId = resClaim.attempt().attemptId();
        advanceToSettled(workspaceId, attClaimId, past, ToolAttemptStatus.SETTLED_SUCCESS, "obs");

        String opLock = UUID.randomUUID().toString();
        ToolAttemptAdmissionResult resLock = store.admit(
                createRequest(workspaceId, sessionId, opLock, null, ToolAttemptIdempotencyMode.NON_IDEMPOTENT));
        String attLockId = resLock.attempt().attemptId();
        transition(
                workspaceId,
                attLockId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(past));
        String lockId = UUID.randomUUID().toString();
        DurableActionLockDto activeLock = new DurableActionLockDto(
                lockId,
                workspaceId,
                sessionId,
                opLock,
                attLockId,
                ACTION_FINGERPRINT,
                null,
                ActionLockScope.EXACT_REQUEST,
                "active",
                DurableActionLockStatus.ACTIVE,
                past,
                null,
                null);
        transition(
                workspaceId,
                attLockId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_ERROR,
                ToolAttemptSettlementFields.forError(past, "h", 1, "err"),
                activeLock);

        long pruned = store.pruneTerminalBefore(workspaceId, cutoff);
        assertTrue(pruned >= 1L);

        assertTrue(store.findAttempt(workspaceId, attPrunableId).isEmpty(), "Past terminal attempt must be pruned");
        assertTrue(store.findAttempt(workspaceId, attRecentId).isPresent(), "Recent terminal attempt must be retained");
        assertTrue(store.findAttempt(workspaceId, attActiveId).isPresent(), "In-flight attempt must never be pruned");
        assertTrue(store.findAttempt(workspaceId, attClaimId).isPresent(), "Claim-protected attempt must be retained");
        assertTrue(
                store.findAttempt(workspaceId, attLockId).isPresent(),
                "Active lock-protected attempt must be retained");
        assertTrue(store.findActiveLock(workspaceId, lockId).isPresent(), "Active lock must be retained");

        assertEquals(1L, store.deleteClaimsBySession(workspaceId, sesClaim));
        store.pruneTerminalBefore(workspaceId, cutoff);
        assertTrue(
                store.findAttempt(workspaceId, attClaimId).isEmpty(),
                "Attempt must be pruned once session claims are deleted");
    }

    private void advanceToSettled(String ws, String attemptId, Instant time, ToolAttemptStatus status, String obs) {
        transition(
                ws,
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(time));
        transition(
                ws,
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                status,
                ToolAttemptSettlementFields.forSuccess(time, "h", obs));
    }

    private ToolAttemptTransitionResult transition(
            String ws,
            String attemptId,
            ToolAttemptStatus from,
            long token,
            ToolAttemptStatus to,
            ToolAttemptSettlementFields fields) {
        return transition(ws, attemptId, from, token, to, fields, null);
    }

    private ToolAttemptTransitionResult transition(
            String ws,
            String attemptId,
            ToolAttemptStatus from,
            long token,
            ToolAttemptStatus to,
            ToolAttemptSettlementFields fields,
            DurableActionLockDto lock) {
        return store.transition(ws, attemptId, from, token, to, fields, lock, List.of());
    }

    private void assertClaimProgressionForTerminalStatus(
            ToolAttemptStatus terminalStatus, ActionClaimStatus expectedClaimStatus, boolean expectExisting) {
        String ses = "ses-" + UUID.randomUUID();
        String op = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest req =
                createRequest(workspaceId, ses, op, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult res = store.admit(req);
        assertEquals(AdmissionStatus.CREATED, res.status());
        String attemptId = res.attempt().attemptId();

        if (terminalStatus == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH) {
            transition(
                    workspaceId,
                    attemptId,
                    ToolAttemptStatus.ADMITTED,
                    0L,
                    terminalStatus,
                    ToolAttemptSettlementFields.empty(Instant.now()));
        } else {
            transition(
                    workspaceId,
                    attemptId,
                    ToolAttemptStatus.ADMITTED,
                    0L,
                    ToolAttemptStatus.LAUNCH_REQUESTED,
                    ToolAttemptSettlementFields.forLaunch(Instant.now()));
            ToolAttemptSettlementFields fields = terminalStatus == ToolAttemptStatus.SETTLED_SUCCESS
                    ? ToolAttemptSettlementFields.forSuccess(Instant.now(), "h", "obs")
                    : ToolAttemptSettlementFields.forError(Instant.now(), "h", 1, "err");
            DurableActionLockDto lock = terminalStatus == ToolAttemptStatus.INDETERMINATE
                    ? new DurableActionLockDto(
                            UUID.randomUUID().toString(),
                            workspaceId,
                            ses,
                            op,
                            attemptId,
                            ACTION_FINGERPRINT,
                            null,
                            ActionLockScope.EXACT_REQUEST,
                            "indet",
                            DurableActionLockStatus.ACTIVE,
                            Instant.now(),
                            null,
                            null)
                    : null;
            transition(workspaceId, attemptId, ToolAttemptStatus.LAUNCH_REQUESTED, 1L, terminalStatus, fields, lock);
        }

        var claimOpt = store.findClaim(workspaceId, ses, "testTool", 1, ACTION_FINGERPRINT);
        if (expectedClaimStatus == null) {
            assertTrue(claimOpt.isEmpty(), "Claim should be deleted on " + terminalStatus);
            ToolAttemptAdmissionRequest retryReq =
                    createRequest(workspaceId, ses, op, attemptId, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
            assertEquals(AdmissionStatus.CREATED, store.admit(retryReq).status());
        } else {
            assertTrue(claimOpt.isPresent(), "Claim must be retained on " + terminalStatus);
            assertEquals(expectedClaimStatus, claimOpt.get().status());
            if (expectExisting) {
                String nextOp = UUID.randomUUID().toString();
                ToolAttemptAdmissionRequest dupReq =
                        createRequest(workspaceId, ses, nextOp, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
                ToolAttemptAdmissionResult dupRes = store.admit(dupReq);
                assertEquals(AdmissionStatus.AT_MOST_ONCE_EXISTING, dupRes.status());
                assertEquals(attemptId, dupRes.attempt().attemptId());
            } else if (expectedClaimStatus == ActionClaimStatus.RESERVED) {
                String nextOp = UUID.randomUUID().toString();
                ToolAttemptAdmissionRequest dupReq =
                        createRequest(workspaceId, ses, nextOp, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
                ToolAttemptAdmissionResult dupRes = store.admit(dupReq);
                assertEquals(AdmissionStatus.CONFLICT, dupRes.status());
            }
        }
    }

    private ToolAttemptAdmissionRequest createRequest(
            String wsId, String sesId, String opId, String retryOfAttemptId, ToolAttemptIdempotencyMode mode) {
        return new ToolAttemptAdmissionRequest(
                wsId,
                sesId,
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
