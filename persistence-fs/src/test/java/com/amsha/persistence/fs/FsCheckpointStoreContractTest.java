package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract suite for {@link FsCheckpointStoreAdapter}, driven against a real {@link FilesystemFileStore} and a real
 * {@link FsCheckpointHelper} rooted at a temporary directory (no mocks — the JSON round-trip and the on-disk
 * {@code checkpoints/<session>/<role>.json} layout are part of what the contract asserts).
 *
 * <p>Fixtures are written straight through {@link FilesystemFileStore#write} rather than via
 * {@code CheckpointStorePort.save}, because {@code save} stamps {@code updatedAt = Instant.now()} through
 * {@code CheckpointDtoHelper.withStatus} — and half of the queries under test here are defined in terms of how old
 * {@code updatedAt} is. Writing directly is the only way to place a checkpoint in the past.
 *
 * <p>Mirrors the shape of {@code SqliteCheckpointStoreContractTest} and
 * {@code PostgresCheckpointStoreContractTest}; see {@code FsCronStoreContractTest} for why the spec is duplicated
 * by convention rather than inherited.
 */
@SuppressWarnings({"java:S1192", "java:S5443"}) // Test-only: duplicated literals and temp dirs are acceptable
class FsCheckpointStoreContractTest {

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsCheckpointStoreAdapter store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-checkpoint-contract-");
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
        fileStore.ensureDataDir();

        FsCheckpointHelper helper = new FsCheckpointHelper();
        setField(helper, "store", fileStore);
        helper.initIdSeq();

        store = new FsCheckpointStoreAdapter();
        setField(store, "store", fileStore);
        setField(store, "helper", helper);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    /* best-effort cleanup */
                }
            });
        }
    }

    // ─── findActiveBySessionTree ───────────────────────────────

    @Test
    void findActiveBySessionTreeRejectsANullRootSession() {
        persist("root", "r1", 1L, CheckpointStatus.IN_PROGRESS.name(), now(), "chat-1");

        assertTrue(store.findActiveBySessionTree(null).isEmpty());
    }

    @Test
    void findActiveBySessionTreeMatchesTheRootAndItsDelegatePrefixOnly() {
        Instant t = now();
        persist("root", "r1", 1L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("root:delegate:sub", "r1", 2L, CheckpointStatus.AWAITING_APPROVAL.name(), t, "chat-1");
        persist("root:delegates-x", "r1", 3L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("other", "r1", 4L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("root", "r2", 5L, CheckpointStatus.AWAITING_RETRY.name(), t, "chat-1");
        persist("root", "r3", 6L, CheckpointStatus.COMPLETED.name(), t, "chat-1");

        assertEquals(List.of(1L, 2L, 5L), ids(store.findActiveBySessionTree("root")));
    }

    @Test
    void findActiveBySessionTreeSkipsRowsWithANullSessionOrStatus() {
        Instant t = now();
        persist(null, "r1", 1L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("root", "r2", 2L, null, t, "chat-1");
        persist("root", "r3", 3L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");

        assertEquals(List.of(3L), ids(store.findActiveBySessionTree("root")));
    }

    // ─── releaseResumeClaim ────────────────────────────────────

    @Test
    void releaseResumeClaimOnlyDemotesResumingCheckpoints() {
        Instant t = now();
        persist("sess", "resuming", 1L, CheckpointStatus.RESUMING.name(), t, "chat-1");
        persist("sess", "in-progress", 2L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");

        store.releaseResumeClaim("sess", "resuming");
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findBySessionAndRole("sess", "resuming").orElseThrow().status());

        store.releaseResumeClaim("sess", "in-progress");
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findBySessionAndRole("sess", "in-progress").orElseThrow().status());

        store.releaseResumeClaim("sess", "missing");
    }

    // ─── stale approvals ───────────────────────────────────────

    @Test
    void findStaleApprovalsSelectsOnlyApprovalsOlderThanTheCutoff() {
        Instant cutoff = now().minusSeconds(3600);
        persist("s1", "r", 1L, CheckpointStatus.AWAITING_APPROVAL.name(), now().minusSeconds(7200), "chat-1");
        persist("s2", "r", 2L, CheckpointStatus.AWAITING_APPROVAL.name(), now(), "chat-1");
        persist("s3", "r", 3L, CheckpointStatus.IN_PROGRESS.name(), now().minusSeconds(7200), "chat-1");
        persist("s4", "r", 4L, CheckpointStatus.AWAITING_APPROVAL.name(), null, "chat-1");

        assertEquals(List.of(1L), ids(store.findStaleApprovals(cutoff)));
    }

    @Test
    void isCheckpointExpiredLooksOnlyAtTheLatestApprovalInTheSession() {
        Instant stale = now().minusSeconds(7200);
        persist("stale", "r", 1L, CheckpointStatus.AWAITING_APPROVAL.name(), stale, "chat-1");
        persist("fresh", "r", 2L, CheckpointStatus.AWAITING_APPROVAL.name(), now(), "chat-1");
        persist("no-approval", "r", 3L, CheckpointStatus.IN_PROGRESS.name(), stale, "chat-1");
        persist("null-updated", "r", 4L, CheckpointStatus.AWAITING_APPROVAL.name(), null, "chat-1");

        assertTrue(store.isCheckpointExpired("stale", 1));
        assertFalse(store.isCheckpointExpired("fresh", 1));
        assertFalse(store.isCheckpointExpired("no-approval", 1));
        assertFalse(store.isCheckpointExpired("null-updated", 1));
        assertFalse(store.isCheckpointExpired("missing", 1));
    }

    // ─── chat-scoped approval probe ────────────────────────────

    @Test
    void hasPendingApprovalForChatIgnoresCronSessionsAndNonApprovals() {
        Instant t = now();
        persist("s1", "r", 1L, CheckpointStatus.AWAITING_APPROVAL.name(), t, "chat-1");
        persist("s2", "r", 2L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("cron-only", "r", 3L, CheckpointStatus.AWAITING_APPROVAL.name(), t, "chat-cron");
        persist("s3", "r", 4L, CheckpointStatus.AWAITING_APPROVAL.name(), t, "chat-3");

        assertTrue(store.hasPendingApprovalForChat("chat-1"));
        assertFalse(store.hasPendingApprovalForChat("chat-cron"));
        assertTrue(store.hasPendingApprovalForChat("chat-3"));
        assertFalse(store.hasPendingApprovalForChat("chat-absent"));
    }

    // ─── zombies ───────────────────────────────────────────────

    @Test
    void findInProgressZombiesSelectsOnlyStaleInProgressRows() {
        Instant cutoff = now().minusSeconds(3600);
        persist("s1", "r", 1L, CheckpointStatus.IN_PROGRESS.name(), now().minusSeconds(7200), "chat-1");
        persist("s2", "r", 2L, CheckpointStatus.IN_PROGRESS.name(), now(), "chat-1");
        persist("s3", "r", 3L, CheckpointStatus.AWAITING_APPROVAL.name(), now().minusSeconds(7200), "chat-1");
        persist("s4", "r", 4L, CheckpointStatus.IN_PROGRESS.name(), null, "chat-1");

        assertEquals(List.of(1L), ids(store.findInProgressZombies(cutoff)));
    }

    @Test
    void recoverInProgressZombiesFailsThemAndReturnsOnlyThatFailReason() {
        Instant cutoff = now().minusSeconds(3600);
        persist("sess", "zombie", 1L, CheckpointStatus.IN_PROGRESS.name(), now().minusSeconds(7200), "chat-1");
        persist("sess", "fresh", 2L, CheckpointStatus.IN_PROGRESS.name(), now(), "chat-1");
        persist("sess", "other-failure", 3L, CheckpointStatus.FAILED.name(), now().minusSeconds(7200), "chat-1");

        List<CheckpointDto> recovered = store.recoverInProgressZombies(cutoff, "zombie sweep");

        assertEquals(1, recovered.size());
        assertEquals(1L, recovered.get(0).id());
        assertEquals("zombie sweep", recovered.get(0).failReason());
        assertEquals("ZOMBIE", recovered.get(0).errorCode());
        assertNotNull(recovered.get(0).failedAt());

        assertEquals(
                CheckpointStatus.FAILED.name(),
                store.findBySessionAndRole("sess", "zombie").orElseThrow().status());
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findBySessionAndRole("sess", "fresh").orElseThrow().status());
    }

    @Test
    void recoverInProgressZombiesIsANoOpWhenNothingIsStale() {
        persist("sess", "fresh", 1L, CheckpointStatus.IN_PROGRESS.name(), now(), "chat-1");

        assertTrue(store.recoverInProgressZombies(now().minusSeconds(3600), "zombie sweep")
                .isEmpty());
    }

    // ─── bulk failure sweep ────────────────────────────────────

    @Test
    void markCheckpointsFailedMarksEveryNonTerminalCheckpointInTheSession() {
        Instant t = now();
        persist("sess", "in-progress", 1L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("sess", "awaiting", 2L, CheckpointStatus.AWAITING_APPROVAL.name(), t, "chat-1");
        persist("sess", "completed", 3L, CheckpointStatus.COMPLETED.name(), t, "chat-1");
        persist("sess", "already-failed", 4L, CheckpointStatus.FAILED.name(), t, "chat-1");
        persist("sess", "null-status", 5L, null, t, "chat-1");
        persist("other-session", "r", 6L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");

        store.markCheckpointsFailed("sess");

        CheckpointDto inProgress =
                store.findBySessionAndRole("sess", "in-progress").orElseThrow();
        assertEquals(CheckpointStatus.FAILED.name(), inProgress.status());
        assertEquals("Session terminated", inProgress.failReason());
        assertEquals("SESSION_TERMINATED", inProgress.errorCode());
        assertNotNull(inProgress.failedAt());

        assertEquals(
                CheckpointStatus.FAILED.name(),
                store.findBySessionAndRole("sess", "awaiting").orElseThrow().status());
        assertEquals(
                CheckpointStatus.COMPLETED.name(),
                store.findBySessionAndRole("sess", "completed").orElseThrow().status());
        assertNull(
                store.findBySessionAndRole("sess", "null-status").orElseThrow().status());
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findBySessionAndRole("other-session", "r").orElseThrow().status());
    }

    // ─── retry claim ───────────────────────────────────────────

    @Test
    void claimForRetryOnlyAcceptsFailedOrOrphanedCheckpoints() {
        Instant t = now();

        assertFalse(store.claimForRetry(999L));

        persist("sess", "failed", 1L, CheckpointStatus.FAILED.name(), t, "chat-1");
        persist("sess", "orphaned", 2L, CheckpointStatus.ORPHANED.name(), t, "chat-1");
        persist("sess", "completed", 3L, CheckpointStatus.COMPLETED.name(), t, "chat-1");

        assertTrue(store.claimForRetry(1L));
        CheckpointDto retried = store.findBySessionAndRole("sess", "failed").orElseThrow();
        assertEquals(CheckpointStatus.IN_PROGRESS.name(), retried.status());
        assertNull(retried.failReason());
        assertNull(retried.errorCode());

        assertTrue(store.claimForRetry(2L));
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findBySessionAndRole("sess", "orphaned").orElseThrow().status());

        assertFalse(store.claimForRetry(3L));
        assertEquals(
                CheckpointStatus.COMPLETED.name(),
                store.findBySessionAndRole("sess", "completed").orElseThrow().status());
    }

    // ─── findAllActive ─────────────────────────────────────────

    @Test
    void findAllActiveExcludesEveryTerminalStatus() {
        Instant t = now();
        persist("s1", "r", 1L, CheckpointStatus.IN_PROGRESS.name(), t, "chat-1");
        persist("s2", "r", 2L, CheckpointStatus.NOT_STARTED.name(), t, "chat-1");
        persist("s3", "r", 3L, CheckpointStatus.AWAITING_APPROVAL.name(), t, "chat-1");
        persist("s4", "r", 4L, CheckpointStatus.AWAITING_RETRY.name(), t, "chat-1");
        persist("s5", "r", 5L, CheckpointStatus.COMPLETED.name(), t, "chat-1");
        persist("s6", "r", 6L, CheckpointStatus.FAILED.name(), t, "chat-1");
        persist("s7", "r", 7L, CheckpointStatus.LOOP_EXCEEDED.name(), t, "chat-1");
        persist("s8", "r", 8L, CheckpointStatus.DENIED.name(), t, "chat-1");
        persist("s9", "r", 9L, CheckpointStatus.COMPROMISED.name(), t, "chat-1");
        persist("s10", "r", 10L, CheckpointStatus.ARCHIVED.name(), t, "chat-1");
        persist("s11", "r", 11L, CheckpointStatus.ORPHANED.name(), t, "chat-1");
        persist("s12", "r", 12L, CheckpointStatus.RESUMING.name(), t, "chat-1");

        assertEquals(Set.of(1L, 2L, 3L, 4L), Set.copyOf(ids(store.findAllActive())));
    }

    // ─── helpers ───────────────────────────────────────────────

    /**
     * Writes a checkpoint straight to disk so {@code updatedAt} is exactly what the test asks for — {@code save}
     * would overwrite it with {@code Instant.now()}. A null {@code sessionId} lands in the store's {@code _}
     * directory, which is how a legacy row with no session would appear.
     */
    private void persist(
            String sessionId, String role, Long id, String status, Instant updatedAt, String channelPlatformId) {
        CheckpointDto cp = new CheckpointDto(
                id,
                sessionId,
                role,
                status,
                "FULL",
                "{}",
                channelPlatformId,
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
                updatedAt,
                updatedAt);
        fileStore.write(
                // Arrays.asList, not List.of: the null-sessionId case is a deliberate fixture and List.of rejects
                // nulls.
                Arrays.asList(FsCheckpointHelper.DOMAIN, sessionId, FsCheckpointHelper.sanitizeRole(role) + ".json"),
                cp);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static List<Long> ids(List<CheckpointDto> checkpoints) {
        return checkpoints.stream().map(CheckpointDto::id).sorted().toList();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
