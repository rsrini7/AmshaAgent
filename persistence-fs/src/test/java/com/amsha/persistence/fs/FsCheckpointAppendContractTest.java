package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity contract suite verifying compare-and-set checkpoint result persistence and single-envelope
 * crash atomicity for {@link FsCheckpointStoreAdapter} per Proposal §12.4 and §12.6.
 */
@SuppressWarnings({"java:S1192", "java:S5443"})
class FsCheckpointAppendContractTest {

    private static final String ROLE = "worker";
    private static final String ACTION_FINGERPRINT = ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "a".repeat(64);
    private static final String TARGET_FINGERPRINT = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX + "b".repeat(64);

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsCheckpointHelper helper;
    private FsToolAttemptHelper toolAttemptHelper;
    private FsCheckpointStoreAdapter checkpointStore;
    private FsToolAttemptStoreAdapter attemptStore;

    private String workspaceId;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-checkpoint-append-contract-");
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
        fileStore = new FilesystemFileStore(props);
        setField(fileStore, "dataDir", tempDir.toAbsolutePath().normalize());

        helper = new FsCheckpointHelper();
        setField(helper, "store", fileStore);
        helper.initIdSeq();

        toolAttemptHelper = new FsToolAttemptHelper(fileStore);

        checkpointStore = new FsCheckpointStoreAdapter(fileStore, helper, toolAttemptHelper);
        attemptStore = new FsToolAttemptStoreAdapter(toolAttemptHelper);

        workspaceId = "ws-" + UUID.randomUUID();
        sessionId = "ses-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
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
    void appendResultIfAbsent_Conflict_InvalidAttemptScenarios() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        ReasoningStepDto step = step("Step");

        // Unknown attempt
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of("unknown-att")));

        // Wrong session attempt
        String otherSessionId = "other-ses-" + UUID.randomUUID();
        String attOtherSession =
                admitAttempt(workspaceId, otherSessionId, UUID.randomUUID().toString());
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step, List.of(attOtherSession)));

        // Cross workspace
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent("other-ws-" + UUID.randomUUID(), cp.id(), 0, step, List.of(att)));
    }

    @Test
    void appendResultIfAbsent_Conflict_InvalidParameters() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto step = step("Step");

        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(null, cp.id(), 0, step, List.of(att)));
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent("   ", cp.id(), 0, step, List.of(att)));
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

        // Attempting to link the same attempt to another checkpoint fails with CONFLICT
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

        // Mismatched token counts
        ReasoningStepDto badTokens = new ReasoningStepDto(
                "{\"thought\":\"t1\"}", "act", "{}", "obs", 99, 20, true, "E0", "tr-1", "RootCause");
        assertEquals(
                CheckpointAppendResult.CONFLICT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, badTokens, List.of(att)));
    }

    @Test
    void appendResultIfAbsent_LegacyCheckpointWithoutVersion_DefaultsToZero() throws Exception {
        String legacySession = "legacy-ses-" + UUID.randomUUID();
        long legacyId = helper.nextId();
        String legacyJson = String.format(
                "{\"id\":%d,\"sessionId\":\"%s\",\"agentRole\":\"legacy-role\",\"status\":\"IN_PROGRESS\"}",
                legacyId, legacySession);
        Path sessionDir = tempDir.resolve("checkpoints").resolve(legacySession);
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("legacy-role.json"), legacyJson);

        CheckpointDto loaded = checkpointStore
                .findBySessionAndRole(legacySession, "legacy-role")
                .orElseThrow();
        assertNotNull(loaded.version());
        assertEquals(0, loaded.version());

        String att = admitAttempt(workspaceId, legacySession, UUID.randomUUID().toString());
        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(workspaceId, loaded.id(), 0, step("Legacy step"), List.of(att));
        assertEquals(CheckpointAppendResult.APPENDED, result);

        CheckpointDto updated = checkpointStore.findById(loaded.id()).orElseThrow();
        assertEquals(1, updated.version());
    }

    @Test
    void appendResultIfAbsent_SelfHealsPhantomSidecarFromCrash() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        FsAttemptLink phantom = new FsAttemptLink(cp.id(), att, sessionId, workspaceId, 99L);
        helper.saveAttemptLink(phantom);
        Path sidecarPath = tempDir.resolve("checkpoints")
                .resolve("attempt_links")
                .resolve(FsToolAttemptHelper.encodeWorkspaceId(workspaceId))
                .resolve(att + ".json");
        assertTrue(Files.exists(sidecarPath));

        CheckpointAppendResult result =
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step("Step after crash"), List.of(att));
        assertEquals(CheckpointAppendResult.APPENDED, result);
        Optional<FsAttemptLink> healed = helper.findAttemptLink(workspaceId, att);
        assertTrue(healed.isPresent());
        assertEquals(1L, healed.get().reasoningStepId());
    }

    @Test
    void appendResultIfAbsent_RecoversFromMissingSidecarsAfterCommit() throws Exception {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att1 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        String att2 = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        ReasoningStepDto batchStep = step("Aggregated result");

        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, batchStep, List.of(att1, att2)));

        String encodedWs = FsToolAttemptHelper.encodeWorkspaceId(workspaceId);
        Path linksDir = tempDir.resolve("checkpoints").resolve("attempt_links").resolve(encodedWs);
        Path att1Sidecar = linksDir.resolve(att1 + ".json");
        Path att2Sidecar = linksDir.resolve(att2 + ".json");

        // 1. Partial missing sidecars (crash after att1 sidecar written)
        Files.deleteIfExists(att2Sidecar);
        assertFalse(Files.exists(att2Sidecar));
        assertEquals(
                CheckpointAppendResult.ALREADY_PRESENT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, batchStep, List.of(att1, att2)));
        assertTrue(Files.exists(att2Sidecar), "Missing sidecar must be reconstructed from envelope");

        // 2. Fully missing sidecars (crash immediately after envelope commit)
        Files.deleteIfExists(att1Sidecar);
        Files.deleteIfExists(att2Sidecar);
        assertEquals(
                CheckpointAppendResult.ALREADY_PRESENT,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 1, batchStep, List.of(att1, att2)));
        assertTrue(Files.exists(att1Sidecar));
        assertTrue(Files.exists(att2Sidecar));
        assertEquals(1, checkpointStore.loadSteps(cp.id()).size());
    }

    @Test
    void appendResultIfAbsent_DistinctWorkspaceIdsWithSpecialCharactersDoNotCollide() {
        String wsA = "tenant/alpha";
        String wsB = "tenant_alpha";
        String sesA = "ses-a-" + UUID.randomUUID();
        String sesB = "ses-b-" + UUID.randomUUID();
        CheckpointDto cpA = saveCheckpoint(sesA, ROLE, 0);
        CheckpointDto cpB = saveCheckpoint(sesB, ROLE, 0);
        String attA = admitAttempt(wsA, sesA, UUID.randomUUID().toString());
        String attB = admitAttempt(wsB, sesB, UUID.randomUUID().toString());

        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(wsA, cpA.id(), 0, step("Step A"), List.of(attA)));
        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(wsB, cpB.id(), 0, step("Step B"), List.of(attB)));

        assertTrue(helper.findAttemptLink(wsA, attA).isPresent());
        assertTrue(helper.findAttemptLink(wsB, attB).isPresent());
        assertTrue(helper.findAttemptLink(wsA, attB).isEmpty());
        assertTrue(helper.findAttemptLink(wsB, attA).isEmpty());
    }

    @Test
    void loadSteps_AuthoritativeEmptyEnvelopeDoesNotResurrectLegacySteps() throws Exception {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        Path legacyStepDir =
                tempDir.resolve("checkpoints").resolve("steps").resolve(cp.id().toString());
        Files.createDirectories(legacyStepDir);
        Files.writeString(legacyStepDir.resolve("1.json"), "{\"action\":\"resurrected\"}");

        FsCheckpointEnvelope env =
                new FsCheckpointEnvelope(FsCheckpointEnvelope.CURRENT_VERSION, cp, List.of(), List.of());
        helper.saveEnvelope(env);

        List<ReasoningStepDto> steps = checkpointStore.loadSteps(cp.id());
        assertTrue(steps.isEmpty(), "Empty envelope must be authoritative and not resurrect legacy files");
    }

    @Test
    void deleteSteps_CleansUpSidecarAttemptLinks() {
        CheckpointDto cp = saveCheckpoint(sessionId, ROLE, 0);
        String att = admitAttempt(workspaceId, sessionId, UUID.randomUUID().toString());
        assertEquals(
                CheckpointAppendResult.APPENDED,
                checkpointStore.appendResultIfAbsent(workspaceId, cp.id(), 0, step("Step 1"), List.of(att)));
        assertTrue(helper.findAttemptLink(workspaceId, att).isPresent());

        checkpointStore.deleteSteps(cp.id());
        assertTrue(helper.findAttemptLink(workspaceId, att).isEmpty());
        Path sidecarPath = tempDir.resolve("checkpoints")
                .resolve("attempt_links")
                .resolve(FsToolAttemptHelper.encodeWorkspaceId(workspaceId))
                .resolve(att + ".json");
        assertFalse(Files.exists(sidecarPath));
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

    private static void setField(Object target, String name, Object val) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, val);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
