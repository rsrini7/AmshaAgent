package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ReasoningStepDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-filesystem coverage for {@link FsCheckpointHelper}.
 *
 * <p>{@link FsStoreErrorPathTest} binds the helper to a store whose data directory is <em>empty</em>, so
 * {@code initIdSeq}'s nested scan never enters either loop and the helper's id sequence is always zero. That
 * leaves the id-recovery logic — the whole reason the method exists — unexecuted, along with the step
 * persistence/cleanup pair and the JSON-map helper. This class seeds a real checkpoint tree first.
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsCheckpointHelperDeepTest {

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsCheckpointHelper helper;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-checkpoint-helper-deep-");
        fileStore = new FilesystemFileStore(props(tempDir.toString()));
        fileStore.ensureDataDir();

        helper = new FsCheckpointHelper();
        helper.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void initIdSeqRecoversTheHighestPersistedCheckpointId() {
        persist("sess-1", "shiva", 7L);
        persist("sess-1", "vishnu", 3L);
        persist("sess-2", "shiva", 12L);
        // index.json is a bookkeeping file, not a checkpoint — it must be skipped even though its id is higher.
        persist("sess-2", "index", 99L);

        helper.initIdSeq();

        assertEquals(13L, helper.nextId(), "the sequence resumes above the highest persisted id");
        assertEquals(14L, helper.nextId());
    }

    @Test
    void initIdSeqIgnoresCheckpointFilesWithNoId() {
        persist("sess-1", "shiva", 5L);
        persist("sess-1", "noid", null);

        helper.initIdSeq();

        assertEquals(6L, helper.nextId(), "an id-less file does not derail the scan");
    }

    @Test
    void initIdSeqLeavesTheSequenceAtZeroForAnEmptyStore() {
        helper.initIdSeq();

        assertEquals(1L, helper.nextId());
    }

    @Test
    void loadSessionSkipsTheIndexFile() {
        persist("sess-1", "shiva", 1L);
        persist("sess-1", "vishnu", 2L);
        persist("sess-1", "index", 3L);

        assertEquals(List.of(1L, 2L), ids(helper.loadSession("sess-1")));
    }

    @Test
    void loadSessionIsEmptyForAnUnknownSession() {
        assertTrue(helper.loadSession("no-such-session").isEmpty());
    }

    /**
     * Documents a real limitation rather than asserting a graceful degradation: {@code readOptional} wraps a
     * Jackson failure in an {@link UncheckedIOException} instead of returning empty, so one corrupt file takes
     * down the whole read.
     */
    @Test
    void aCorruptCheckpointFilePropagatesInsteadOfBeingSkipped() throws IOException {
        Files.createDirectories(fileStore.resolve(List.of("checkpoints", "sess-1")));
        Files.writeString(fileStore.resolve(List.of("checkpoints", "sess-1", "garbage.json")), "{not json");

        assertThrows(UncheckedIOException.class, () -> helper.loadSession("sess-1"));
        assertThrows(UncheckedIOException.class, helper::initIdSeq);
    }

    @Test
    void loadAllWalksEverySessionDirectory() {
        persist("sess-1", "shiva", 1L);
        persist("sess-2", "shiva", 2L);

        assertEquals(2, helper.loadAll().size());
    }

    @Test
    void findBySessionAndRoleSanitizesTheRoleIntoTheFileName() {
        persist("sess-1", "shiva", 1L);

        assertTrue(helper.findBySessionAndRole("sess-1", "shiva").isPresent());
        assertEquals("a_b", FsCheckpointHelper.sanitizeRole("a/b"));
        assertTrue(
                helper.findBySessionAndRole("sess-1", "a/b").isEmpty(),
                "a role with a path separator is sanitized, so it cannot escape the session directory");
    }

    @Test
    void persistStepsNumbersTheStepFilesFromOne() {
        helper.persistSteps(42L, List.of(step("a1"), step("a2"), step("a3")));

        assertEquals(
                List.of("1.json", "2.json", "3.json"), fileStore.list(List.of("checkpoints", "steps", "42"), ".json"));
        assertEquals(
                "a1",
                fileStore
                        .read(List.of("checkpoints", "steps", "42", "1.json"), ReasoningStepDto.class)
                        .action());
    }

    @Test
    void persistStepsIsANoOpForAnEmptyStepList() {
        helper.persistSteps(42L, List.of());

        assertTrue(
                fileStore.list(List.of("checkpoints", "steps", "42"), ".json").isEmpty());
    }

    @Test
    void deleteStepsForSessionRemovesTheWholeStepDirectory() {
        persist("sess-1", "shiva", 9L);
        helper.persistSteps(9L, List.of(step("a1")));
        assertFalse(
                fileStore.list(List.of("checkpoints", "steps", "9"), ".json").isEmpty());

        helper.deleteStepsForSession("sess-1", "shiva");

        assertFalse(fileStore.exists(List.of("checkpoints", "steps", "9")), "the step directory is gone");
    }

    @Test
    void deleteStepsForSessionIsANoOpWhenTheCheckpointIsUnknown() {
        helper.deleteStepsForSession("no-such-session", "shiva");

        assertFalse(fileStore.exists(List.of("checkpoints", "steps")));
    }

    // ─── updateJsonMapIfPending ────────────────────────────────

    @Test
    void updateJsonMapIfPendingTreatsABlankPayloadAsAnEmptyMap() {
        for (String blank : new String[] {null, "", "  ", "{}"}) {
            assertTrue(
                    FsCheckpointHelper.updateJsonMapIfPending(blank, "k", "v").isEmpty(),
                    "an empty map has no PENDING key to rewrite, but it must not throw");
        }
    }

    @Test
    void updateJsonMapIfPendingOnlyRewritesAPendingKey() {
        assertEquals(
                Optional.of("{\"k\":\"done\"}"),
                FsCheckpointHelper.updateJsonMapIfPending("{\"k\":\"PENDING\"}", "k", "done"));
        assertTrue(FsCheckpointHelper.updateJsonMapIfPending("{\"k\":\"APPROVED\"}", "k", "done")
                .isEmpty());
        assertTrue(FsCheckpointHelper.updateJsonMapIfPending("{\"other\":\"PENDING\"}", "k", "done")
                .isEmpty());
    }

    @Test
    void updateJsonMapIfPendingSwallowsMalformedJson() {
        assertTrue(
                FsCheckpointHelper.updateJsonMapIfPending("{not json", "k", "v").isEmpty(),
                "a parse failure degrades to empty rather than propagating");
    }

    // ─── Helpers ───────────────────────────────────────────────

    private void persist(String sessionId, String role, Long id) {
        fileStore.write(
                List.of("checkpoints", sessionId, FsCheckpointHelper.sanitizeRole(role) + ".json"),
                checkpoint(id, sessionId, role));
    }

    private static List<Long> ids(List<CheckpointDto> checkpoints) {
        return checkpoints.stream().map(CheckpointDto::id).toList();
    }

    private static CheckpointDto checkpoint(Long id, String sessionId, String agentRole) {
        Instant now = Instant.now();
        return new CheckpointDto(
                id,
                sessionId,
                agentRole,
                "IN_PROGRESS",
                "FULL",
                "{}",
                null,
                "telegram",
                "goal-" + id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                null,
                null,
                "{}",
                null,
                null,
                null,
                null,
                0,
                now,
                now);
    }

    private static ReasoningStepDto step(String action) {
        return new ReasoningStepDto(
                "{\"thought\":\"" + action + "\"}", action, "{}", "observation", 1, 1, true, null, null, null);
    }

    private static FsStoreProperties props(String dataDir) {
        return new FsStoreProperties() {
            @Override
            public String dataDir() {
                return dataDir;
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
    }
}
