package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Parity contract suite verifying compare-and-set checkpoint result persistence and idempotency
 * for {@link SqliteCheckpointStoreAdapter} per Proposal §12.6.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@SuppressWarnings("java:S1192")
class SqliteCheckpointAppendContractTest {

    private static final String ROLE = "worker";
    private static final String ACTION_FINGERPRINT = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);
    private static final String TARGET_FINGERPRINT = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX + "b".repeat(64);

    @Inject
    SqliteCheckpointStoreAdapter checkpointStore;

    @Inject
    SqliteToolAttemptStoreAdapter attemptStore;

    private String workspaceId;
    private String sessionId;

    @BeforeEach
    void setUp() {
        workspaceId = "ws-" + UUID.randomUUID();
        sessionId = "ses-" + UUID.randomUUID();
    }

    @Test
    void appendResultIfAbsent_Success_IncrementsVersionAndAppendsStep() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String attemptId =
                admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto step = step("Step 1 observation");

        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of(attemptId));
        assertEquals(CheckpointAppendResult.APPENDED, result);

        CheckpointDto updated = checkpointStore.findById(cp.id()).orElseThrow();
        assertEquals(1, updated.version());

        List<ReasoningStepDto> steps = checkpointStore.loadSteps(cp.id());
        assertEquals(1, steps.size());
        assertEquals("Step 1 observation", steps.getFirst().observation());
    }

    @Test
    void appendResultIfAbsent_AlreadyPresent_IdempotentReplay() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String attemptId =
                admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto step = step("Step observation");

        CheckpointAppendResult first =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of(attemptId));
        assertEquals(CheckpointAppendResult.APPENDED, first);

        // Replay with identical step and attempt ID (even with stale expected version 0)
        CheckpointAppendResult replay =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of(attemptId));
        assertEquals(CheckpointAppendResult.ALREADY_PRESENT, replay);

        // Replay with current version 1
        CheckpointAppendResult replayCurrent =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, step, List.of(attemptId));
        assertEquals(CheckpointAppendResult.ALREADY_PRESENT, replayCurrent);

        // Verify version and steps unchanged
        CheckpointDto updated = checkpointStore.findById(cp.id()).orElseThrow();
        assertEquals(1, updated.version());
        assertEquals(1, checkpointStore.loadSteps(cp.id()).size());
    }

    @Test
    void appendResultIfAbsent_Conflict_DifferentStepOnReplay() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String attemptId =
                admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto stepA = step("Step observation A");
        ReasoningStepDto stepB = step("Step observation B mutated");

        CheckpointAppendResult first =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, stepA, List.of(attemptId));
        assertEquals(CheckpointAppendResult.APPENDED, first);

        // Replay with conflicting step on same attempt
        CheckpointAppendResult conflict =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, stepB, List.of(attemptId));
        assertEquals(CheckpointAppendResult.CONFLICT, conflict);

        assertEquals(1, checkpointStore.findById(cp.id()).orElseThrow().version());
        assertEquals(1, checkpointStore.loadSteps(cp.id()).size());
        assertEquals(
                "Step observation A",
                checkpointStore.loadSteps(cp.id()).getFirst().observation());
    }

    @Test
    void appendResultIfAbsent_Conflict_SplitAttemptsAcrossSteps() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att1 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        String att2 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());

        checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step("Step 1"), List.of(att1));
        checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, step("Step 2"), List.of(att2));

        // Attempting to append with both attempts linked to different steps
        CheckpointAppendResult split =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 2, step("Step 3"), List.of(att1, att2));
        assertEquals(CheckpointAppendResult.CONFLICT, split);
    }

    @Test
    void appendResultIfAbsent_Conflict_UnknownAttempt() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        CheckpointAppendResult result = checkpointStore.appendResultIfAbsent(
                workspaceId, cp.id(), 0, step("Step"), List.of("unknown-attempt-id"));
        assertEquals(CheckpointAppendResult.CONFLICT, result);
    }

    @Test
    void appendResultIfAbsent_Conflict_WrongSessionAttempt() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String otherSessionId = "other-ses-" + UUID.randomUUID();
        String attOtherSession =
                admitAttempt(workspaceId, otherSessionId, UUID.randomUUID().toString());

        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step("Step"), List.of(attOtherSession));
        assertEquals(CheckpointAppendResult.CONFLICT, result);
    }

    @Test
    void appendResultIfAbsent_Conflict_CrossWorkspace() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());

        String otherWorkspace = "other-ws-" + UUID.randomUUID();
        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(otherWorkspace, cp.id(), 0, step("Step"), List.of(att));
        assertEquals(CheckpointAppendResult.CONFLICT, result);
    }

    @Test
    void appendResultIfAbsent_Conflict_InvalidParameters() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto step = step("Step");

        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of()));
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of("")));
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of(att, att)));
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, 999999L, 0, step, List.of(att)));
    }

    @Test
    void appendResultIfAbsent_StaleVersion_RollsBackWithoutPhantomWrites() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto step = step("Step");

        // Expected version is 5, but current is 0
        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 5, step, List.of(att));
        assertEquals(CheckpointAppendResult.STALE, result);

        // Verify zero phantom writes
        CheckpointDto reloaded = checkpointStore.findById(cp.id()).orElseThrow();
        assertEquals(0, reloaded.version());
        assertTrue(checkpointStore.loadSteps(cp.id()).isEmpty());
    }

    @Test
    void appendResultIfAbsent_ParallelBatch_LinksAllAttemptsToSingleStep() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att1 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        String att2 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto batchStep = step("Aggregated parallel batch result");

        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, batchStep, List.of(att1, att2));
        assertEquals(CheckpointAppendResult.APPENDED, result);

        assertEquals(1, checkpointStore.findById(cp.id()).orElseThrow().version());
        assertEquals(1, checkpointStore.loadSteps(cp.id()).size());

        // Replay with both attempt IDs returns ALREADY_PRESENT
        CheckpointAppendResult replay =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, batchStep, List.of(att1, att2));
        assertEquals(CheckpointAppendResult.ALREADY_PRESENT, replay);
    }

    @Test
    void appendResultIfAbsent_Conflict_CrossCheckpoint() {
        CheckpointDto cp1 = saveCheckpoint(sessionId, "role1", 0);
        CheckpointDto cp2 = saveCheckpoint(sessionId, "role2", 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());

        CheckpointAppendResult first =
                checkpointStore.appendResultIfAbsent(workspaceId, cp1.id(), 0, step("Step 1"), List.of(att));
        assertEquals(CheckpointAppendResult.APPENDED, first);

        CheckpointAppendResult cross =
                checkpointStore.appendResultIfAbsent(workspaceId, cp2.id(), 0, step("Step 1"), List.of(att));
        assertEquals(CheckpointAppendResult.CONFLICT, cross);
    }

    @Test
    void appendResultIfAbsent_Conflict_MismatchedPersistedFieldsOnReplay() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto base = new ReasoningStepDto(
                "{\"thought\":\"t1\"}", "act", "{}", "obs", 10, 20, true, "E0", "tr-1", "RootCause");
        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, base, List.of(att)));

        // Mismatched thought
        ReasoningStepDto badThought = new ReasoningStepDto(
                "{\"thought\":\"diff\"}", "act", "{}", "obs", 10, 20, true, "E0", "tr-1", "RootCause");
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, badThought, List.of(att)));

        // Mismatched tokens
        ReasoningStepDto badTokens = new ReasoningStepDto(
                "{\"thought\":\"t1\"}", "act", "{}", "obs", 99, 20, true, "E0", "tr-1", "RootCause");
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, badTokens, List.of(att)));

        // Mismatched traceId
        ReasoningStepDto badTrace = new ReasoningStepDto(
                "{\"thought\":\"t1\"}", "act", "{}", "obs", 10, 20, true, "E0", "tr-diff", "RootCause");
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, badTrace, List.of(att)));

        // Mismatched rootCauseClass
        ReasoningStepDto badCause = new ReasoningStepDto(
                "{\"thought\":\"t1\"}", "act", "{}", "obs", 10, 20, true, "E0", "tr-1", "OtherCause");
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, badCause, List.of(att)));
    }

    @Test
    void appendResultIfAbsent_Conflict_NullOrBlankWorkspace() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto step = step("Step");

        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(null, cp.id(), 0, step, List.of(att)));
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent("   ", cp.id(), 0, step, List.of(att)));
    }

    @Test
    void appendResultIfAbsent_WorkspaceIsolation_SeparateWorkspacesDoNotConflict() {
        CheckpointDto cp1 = saveCheckpoint(sessionId, ROLE, 0);
        String att1 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(workspaceId, cp1.id(), 0, step("WS1 Step"), List.of(att1)));

        String otherWs = "ws-other-" + UUID.randomUUID();
        String otherSession = "ses-other-" + UUID.randomUUID();
        CheckpointDto cp2 = saveCheckpoint(otherSession, ROLE, 0);
        String att2 = admitAttempt(otherWs, otherSession, UUID.randomUUID().toString());
        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(otherWs, cp2.id(), 0, step("WS2 Step"), List.of(att2)));
    }

    private String admitAttempt(String ws, String ses, String opId) {
        ToolAttemptAdmissionRequest req = new ToolAttemptAdmissionRequest(
                ws,
                ses,
                opId,
                null,
                "test-tool",
                ACTION_FINGERPRINT,
                1,
                TARGET_FINGERPRINT,
                ToolAttemptIdempotencyMode.IDEMPOTENT,
                "DIRECT",
                "{}",
                Instant.now());
        ToolAttemptAdmissionResult res = attemptStore.admit(req);
        assertNotNull(res.attempt());
        return res.attempt().attemptId();
    }

    private CheckpointDto saveCheckpoint(String ses, String role, int version) {
        CheckpointDto base = CheckpointDtoHelper.create(
                ses, role, CheckpointStatus.IN_PROGRESS.name(), "FULL", "{}", "goal", 1, "{}");
        return checkpointStore.save(CheckpointDtoHelper.withVersion(base, version));
    }

    private static ReasoningStepDto step(String obs) {
        return new ReasoningStepDto(
                "{\"thought\":\"test\"}", "test-action", "{\"arg\":\"val\"}", obs, 10, 20, true, null, null, null);
    }
}
