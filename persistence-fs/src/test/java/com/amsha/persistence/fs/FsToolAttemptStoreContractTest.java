package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.ToolActionClaimDto;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract suite for {@link FsToolAttemptStoreAdapter} per Proposal §12.8.
 * Mirrors {@link com.amsha.persistence.sqlite.SqliteToolAttemptStoreContractTest}.
 */
@SuppressWarnings({"java:S1192", "java:S5443"})
class FsToolAttemptStoreContractTest {

    private static final String ACTION_FINGERPRINT = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);
    private static final String TARGET_FINGERPRINT = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX + "b".repeat(64);

    private Path tempDir;
    private FsToolAttemptHelper helper;
    private FsToolAttemptStoreAdapter store;
    private String workspaceId;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-tool-attempt-contract-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
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

        transition(
                attempt1Id,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        transition(
                attempt1Id,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h1", "ok"));

        ToolAttemptAdmissionRequest retrySuccessReq =
                createRequest(opId, attempt1Id, ToolAttemptIdempotencyMode.IDEMPOTENT);
        assertEquals(AdmissionStatus.CONFLICT, store.admit(retrySuccessReq).status());

        String op2Id = UUID.randomUUID().toString();
        ToolAttemptAdmissionRequest atMostOnceReq = createRequest(op2Id, null, ToolAttemptIdempotencyMode.AT_MOST_ONCE);
        ToolAttemptAdmissionResult atMostOnceRes = store.admit(atMostOnceReq);
        String amoAttempt1Id = atMostOnceRes.attempt().attemptId();

        transition(
                amoAttempt1Id,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                ToolAttemptSettlementFields.empty(Instant.now()));

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

        transition(
                res1.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        transition(
                res1.attempt().attemptId(),
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h", "obs"));

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

        ToolAttemptTransitionResult t1 = transition(
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        assertEquals(TransitionStatus.APPLIED, t1.status());
        assertEquals(1L, t1.updatedAttempt().fencingToken());
        assertEquals(ToolAttemptStatus.LAUNCH_REQUESTED, t1.updatedAttempt().status());

        ToolAttemptTransitionResult staleToken = transition(
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                0L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"));
        assertEquals(TransitionStatus.STALE, staleToken.status());

        ToolAttemptTransitionResult staleStatus = transition(
                attemptId,
                ToolAttemptStatus.ADMITTED,
                1L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        assertEquals(TransitionStatus.STALE, staleStatus.status());

        ToolAttemptTransitionResult t2 = transition(
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"));
        assertEquals(TransitionStatus.APPLIED, t2.status());
        assertEquals(2L, t2.updatedAttempt().fencingToken());
    }

    @Test
    void transitionTerminalImmutability() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        String attemptId = res.attempt().attemptId();

        transition(
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        transition(
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h", "obs"));

        ToolAttemptTransitionResult postTerminal = transition(
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                2L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "h", "pid"));
        assertEquals(TransitionStatus.CONFLICT, postTerminal.status());
    }

    @Test
    void deleteClaimsBySessionPreservesOtherSessions() {
        String deletedSession = "s1-" + UUID.randomUUID();
        String retainedSession = "s2-" + UUID.randomUUID();
        store.admit(createRequest(
                workspaceId,
                deletedSession,
                UUID.randomUUID().toString(),
                null,
                ToolAttemptIdempotencyMode.AT_MOST_ONCE));
        store.admit(createRequest(
                workspaceId,
                retainedSession,
                UUID.randomUUID().toString(),
                null,
                ToolAttemptIdempotencyMode.AT_MOST_ONCE));

        assertEquals(1L, store.deleteClaimsBySession(workspaceId, deletedSession));
        assertTrue(store.findClaim(workspaceId, deletedSession, "testTool", 1, ACTION_FINGERPRINT)
                .isEmpty());
        assertTrue(store.findClaim(workspaceId, retainedSession, "testTool", 1, ACTION_FINGERPRINT)
                .isPresent());
    }

    @Test
    void transitionClaimProgressionAndCancellationPurge() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.AT_MOST_ONCE));
        String attemptId = res.attempt().attemptId();
        assertTrue(store.findClaim(workspaceId, sessionId, "testTool", 1, ACTION_FINGERPRINT)
                .isPresent());

        transition(
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                ToolAttemptSettlementFields.empty(Instant.now()));
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
    void fsCrashRecoveryDanglingClaimPurge() {
        String absentAttemptId = UUID.randomUUID().toString();
        ToolActionClaimDto danglingClaim = claim("toolX", UUID.randomUUID().toString(), absentAttemptId);

        helper.withWorkspaceLock(workspaceId, () -> {
            helper.saveClaimsEnvelope(new FsWorkspaceClaimsEnvelope(1, workspaceId, List.of(danglingClaim)));
            return null;
        });

        // Admission or repair under lock discovers attempt is missing and safely releases the dangling claim
        helper.withWorkspaceLock(workspaceId, () -> {
            helper.repairOrRecoverClaims(workspaceId);
            return null;
        });

        assertTrue(store.findClaim(workspaceId, sessionId, "toolX", 1, ACTION_FINGERPRINT)
                .isEmpty());
    }

    @Test
    void fsCrashRecoveryMissingClaimCompletion() {
        ToolAttemptAdmissionResult res =
                store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.AT_MOST_ONCE));
        String attemptId = res.attempt().attemptId();

        transition(
                attemptId,
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now()));
        transition(
                attemptId,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "h", "obs"));

        // Revert claims envelope to RESERVED to simulate crash after attempt write but before claim update
        helper.withWorkspaceLock(workspaceId, () -> {
            ToolActionClaimDto reserved = claim("testTool", res.attempt().operationId(), attemptId);
            helper.saveClaimsEnvelope(new FsWorkspaceClaimsEnvelope(1, workspaceId, List.of(reserved)));
            return null;
        });

        // Repair verifies terminal attempt and advances claim to CONSUMED
        helper.withWorkspaceLock(workspaceId, () -> {
            helper.repairOrRecoverClaims(workspaceId);
            return null;
        });

        ToolActionClaimDto recovered = store.findClaim(workspaceId, sessionId, "testTool", 1, ACTION_FINGERPRINT)
                .orElseThrow();
        assertEquals(ActionClaimStatus.CONSUMED, recovered.status());
    }

    @Test
    void workspaceIdInjectiveEncodingSeparatesSpecialCharacters() {
        String ws1 = "alpha/beta";
        String ws2 = "alpha_beta";
        ToolAttemptAdmissionRequest req1 = createRequest(
                ws1, sessionId, UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT);
        ToolAttemptAdmissionRequest req2 = createRequest(
                ws2, sessionId, UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT);
        store.admit(req1);
        store.admit(req2);

        Set<String> workspaces = helper.listWorkspaceIds();
        assertTrue(workspaces.contains("alpha/beta"));
        assertTrue(workspaces.contains("alpha_beta"));
        assertNotEquals(helper.workspacePath("alpha/beta"), helper.workspacePath("alpha_beta"));
        assertNotEquals(helper.attemptsDir("alpha/beta"), helper.attemptsDir("alpha_beta"));
    }

    @Test
    void findActiveAttemptsReturnsOnlyActiveInDeterministicOrderWithWorkspaceIsolation() {
        var a1 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        var a2 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        transition(
                a2.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now(), "podman-container:c2"));

        var a3 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        transition(
                a3.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now(), "podman-container:c3"));
        transition(
                a3.attempt().attemptId(),
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(Instant.now(), "podman-container:c3", "container:c3"));

        var a4 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        transition(
                a4.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(Instant.now(), "podman-container:c4"));
        transition(
                a4.attempt().attemptId(),
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                ToolAttemptStatus.SETTLED_SUCCESS,
                ToolAttemptSettlementFields.forSuccess(Instant.now(), "podman-container:c4", "done"));

        var a5 = store.admit(createRequest(UUID.randomUUID().toString(), null, ToolAttemptIdempotencyMode.IDEMPOTENT));
        transition(
                a5.attempt().attemptId(),
                ToolAttemptStatus.ADMITTED,
                0L,
                ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                ToolAttemptSettlementFields.empty(Instant.now()));

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

    private ToolAttemptTransitionResult transition(
            String attemptId,
            ToolAttemptStatus from,
            long token,
            ToolAttemptStatus to,
            ToolAttemptSettlementFields fields) {
        return store.transition(workspaceId, attemptId, from, token, to, fields, null, List.of());
    }

    private ToolActionClaimDto claim(String tool, String opId, String attemptId) {
        return new ToolActionClaimDto(
                workspaceId,
                sessionId,
                tool,
                1,
                ACTION_FINGERPRINT,
                ActionClaimStatus.RESERVED,
                opId,
                attemptId,
                Instant.now(),
                null);
    }

    private ToolAttemptAdmissionRequest createRequest(
            String requestedWorkspaceId,
            String requestedSessionId,
            String opId,
            String retryOfAttemptId,
            ToolAttemptIdempotencyMode mode) {
        return new ToolAttemptAdmissionRequest(
                requestedWorkspaceId,
                requestedSessionId,
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

    private ToolAttemptAdmissionRequest createRequest(
            String opId, String retryOfAttemptId, ToolAttemptIdempotencyMode mode) {
        return createRequest(workspaceId, sessionId, opId, retryOfAttemptId, mode);
    }
}
