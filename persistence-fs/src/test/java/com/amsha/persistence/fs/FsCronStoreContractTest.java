package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract suite for {@link FsCronStoreAdapter}, driven against a real {@link FilesystemFileStore} rooted at a
 * temporary directory (no mocks — the JSON round-trip is part of what the contract asserts).
 *
 * <p>This class deliberately mirrors {@code SqliteCronStoreContractTest} and
 * {@code PostgresCronStoreContractTest} method-for-method so the three backends are held to one specification.
 * The three adapters live in separate Maven modules with no shared test-jar, so the spec is duplicated by
 * convention rather than inherited; keep the three files in step when the port changes.
 */
@SuppressWarnings({"java:S1192", "java:S5443"}) // Test-only: duplicated literals and temp dirs are acceptable
class FsCronStoreContractTest {

    private static final String USER = "user-1";
    private static final String OTHER_USER = "user-2";
    private static final String SESSION = "session-1";
    private static final List<String> BY_ID = List.of("crons", "by-id");

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsCronStoreAdapter store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-cron-contract-");
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

        store = new FsCronStoreAdapter();
        setField(store, "store", fileStore);
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

    // ─── save / findById ───────────────────────────────────────

    @Test
    void saveAndFindByIdRoundTripThroughJson() {
        store.save(cron("c-1", USER, SESSION, CronStatus.ACTIVE, null, null));

        Optional<AgentCronDto> found = store.findById("c-1");
        assertTrue(found.isPresent());
        assertEquals("goal-c-1", found.get().goal());
        assertEquals(USER, found.get().userId());
        assertEquals(SESSION, found.get().sessionId());
    }

    @Test
    void findByIdRejectsNullAndBlankIds() {
        assertTrue(store.findById(null).isEmpty());
        assertTrue(store.findById("").isEmpty());
        assertTrue(store.findById("   ").isEmpty());
    }

    @Test
    void saveWithoutUserIdSkipsTheUserIndex() {
        store.save(cron("c-nouser", null, SESSION, CronStatus.ACTIVE, null, null));

        assertTrue(store.findById("c-nouser").isPresent());
        assertFalse(fileStore.exists(List.of("crons", "indices", "by-user", "null.json")));
    }

    @Test
    void savingTheSameIdTwiceKeepsTheUserIndexIdempotent() {
        store.save(cron("c-dup", USER, SESSION, CronStatus.ACTIVE, null, null));
        store.save(cron("c-dup", USER, SESSION, CronStatus.ACTIVE, null, null));

        assertEquals(1, store.findByUserId(USER).size());
    }

    // ─── read-side finders ─────────────────────────────────────

    @Test
    void findDueCronsSelectsOnlyActiveCronsWhoseFireTimeHasPassed() {
        Instant now = Instant.now();
        store.save(cron("due", USER, SESSION, CronStatus.ACTIVE, now.minusSeconds(60), null));
        store.save(cron("future", USER, SESSION, CronStatus.ACTIVE, now.plusSeconds(3600), null));
        store.save(cron("notactive", USER, SESSION, CronStatus.COMPLETED, now.minusSeconds(60), null));
        store.save(cron("nofiretime", USER, SESSION, CronStatus.ACTIVE, null, null));

        List<AgentCronDto> due = store.findDueCrons();
        assertEquals(1, due.size());
        assertEquals("due", due.get(0).id());
    }

    @Test
    void userScopedFindersFilterOnUserIdAndApprovalState() {
        store.save(cron("u1-aa", USER, SESSION, CronStatus.AWAITING_APPROVAL, null, null));
        store.save(cron("u1-active", USER, SESSION, CronStatus.ACTIVE, null, null));
        store.save(cron("u2-aa", OTHER_USER, "session-2", CronStatus.AWAITING_APPROVAL, null, null));

        assertEquals(2, store.findByUserId(USER).size());
        assertTrue(store.findByUserId("nobody").isEmpty());

        List<AgentCronDto> awaitingForUser = store.findAwaitingApprovalByUserId(USER);
        assertEquals(1, awaitingForUser.size());
        assertEquals("u1-aa", awaitingForUser.get(0).id());

        assertEquals(2, store.findAllAwaitingApproval().size());
    }

    // ─── claim / release ───────────────────────────────────────

    @Test
    void claimForFireRejectsBlankArgumentsMissingRowsAndWrongStatus() {
        assertFalse(store.claimForFire(null, "d"));
        assertFalse(store.claimForFire("", "d"));
        assertFalse(store.claimForFire("   ", "d"));
        assertFalse(store.claimForFire("missing", "d"));
        assertFalse(store.claimForFire("c-1", null));
        assertFalse(store.claimForFire("c-1", "  "));

        store.save(cron("c-1", USER, SESSION, CronStatus.COMPLETED, null, null));
        assertFalse(store.claimForFire("c-1", "d"));
    }

    @Test
    void claimForFireMarksRunningAndRecordsTheDeliveryId() {
        store.save(cron("c-1", USER, SESSION, CronStatus.ACTIVE, null, null));

        assertTrue(store.claimForFire("c-1", "delivery-1"));

        AgentCronDto claimed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.RUNNING.name(), claimed.status());
        assertEquals("delivery-1", claimed.lastDeliveryId());
    }

    @Test
    void claimForResumeAcceptsAwaitingStatesAndTheRunningOwnerOnly() {
        assertFalse(store.claimForResume(null, "d"));
        assertFalse(store.claimForResume("", "d"));
        assertFalse(store.claimForResume("missing", "d"));
        assertFalse(store.claimForResume("c-1", null));
        assertFalse(store.claimForResume("c-1", " "));

        store.save(cron("c-aa", USER, SESSION, CronStatus.AWAITING_APPROVAL, null, "d0"));
        assertTrue(store.claimForResume("c-aa", "d1"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-aa").orElseThrow().status());

        store.save(cron("c-retry", USER, SESSION, CronStatus.AWAITING_RETRY, null, "d0"));
        assertTrue(store.claimForResume("c-retry", "d2"));

        store.save(cron("c-running-owner", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertTrue(store.claimForResume("c-running-owner", "owner"));

        store.save(cron("c-running-other", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertFalse(store.claimForResume("c-running-other", "someone-else"));

        store.save(cron("c-done", USER, SESSION, CronStatus.COMPLETED, null, "d0"));
        assertFalse(store.claimForResume("c-done", "d0"));
    }

    @Test
    void releaseClaimToActiveOnlyAppliesToRunningCrons() {
        store.save(cron("c-run", USER, SESSION, CronStatus.RUNNING, null, "d"));
        store.releaseClaimToActive("c-run");
        assertEquals(
                CronStatus.ACTIVE.name(), store.findById("c-run").orElseThrow().status());

        store.save(cron("c-active", USER, SESSION, CronStatus.ACTIVE, null, "d"));
        store.releaseClaimToActive("c-active");
        assertEquals(
                CronStatus.ACTIVE.name(),
                store.findById("c-active").orElseThrow().status());

        store.releaseClaimToActive("missing");
        store.releaseClaimToActive(null);
    }

    // ─── unconditional status mutators ─────────────────────────

    @Test
    void markFiredAcceptsNullTimestamps() {
        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "d"));

        store.markFired("c-1", null, null);
        AgentCronDto fired = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.ACTIVE.name(), fired.status());
        assertNull(fired.lastFireTime());
        assertNull(fired.nextFireTime());

        Instant last = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant next = last.plusSeconds(60);
        store.markFired("c-1", last, next);

        fired = store.findById("c-1").orElseThrow();
        assertEquals(last, fired.lastFireTime());
        assertEquals(next, fired.nextFireTime());
    }

    @Test
    void markCompletedClearsTheNextFireTime() {
        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, Instant.now(), "d"));

        Instant last = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.markCompleted("c-1", last);

        AgentCronDto completed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.COMPLETED.name(), completed.status());
        assertNull(completed.nextFireTime());
        assertEquals(last, completed.lastFireTime());
    }

    @Test
    void statusMutatorsWriteThrough() {
        store.save(cron("c-1", USER, SESSION, CronStatus.ACTIVE, null, null));

        store.markAwaitingApproval("c-1");
        assertEquals(
                CronStatus.AWAITING_APPROVAL.name(),
                store.findById("c-1").orElseThrow().status());

        store.markRunning("c-1");
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        store.setDeliveryId("c-1", "delivery-x");
        assertEquals("delivery-x", store.findById("c-1").orElseThrow().lastDeliveryId());

        store.markFailed("c-1", "boom");
        AgentCronDto failed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.FAILED.name(), failed.status());
        assertEquals("boom", failed.errorReason());

        Instant next = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(60);
        store.markSkipped("c-1", next, Instant.now());
        AgentCronDto skipped = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.SKIPPED.name(), skipped.status());
        assertEquals(next, skipped.nextFireTime());
    }

    @Test
    void mutatorsWithABlankIdAreSilentNoOps() {
        store.markCompleted("", Instant.now());
        store.markRunning(null);
        store.markFailed("   ", "reason");
        store.setDeliveryId(null, "d");
        store.markSkipped("", null, null);
        store.markAwaitingApproval(null);
        store.markFired(null, Instant.now(), Instant.now());

        assertTrue(store.findAll(null, 0, 10).isEmpty());
    }

    // ─── stale reclaim ─────────────────────────────────────────

    @Test
    void reclaimStaleCronsOnlyTouchesStaleRunningRows() {
        Instant stale = Instant.now().minusSeconds(7200);
        Instant threshold = Instant.now().minusSeconds(3600);

        store.save(cronAt("legacy", USER, SESSION, CronStatus.RUNNING, null, "d", stale, null));
        store.save(cronAt("stale-running", USER, SESSION, CronStatus.RUNNING, null, "d", stale, stale));
        store.save(cronAt("fresh-running", USER, SESSION, CronStatus.RUNNING, null, "d", stale, Instant.now()));
        store.save(cronAt("stale-active", USER, SESSION, CronStatus.ACTIVE, null, "d", stale, stale));

        store.reclaimStaleCrons(threshold);

        assertEquals(
                CronStatus.ACTIVE.name(), store.findById("legacy").orElseThrow().status());
        assertEquals(
                CronStatus.ACTIVE.name(),
                store.findById("stale-running").orElseThrow().status());
        assertEquals(
                CronStatus.RUNNING.name(),
                store.findById("fresh-running").orElseThrow().status());
        assertEquals(
                CronStatus.ACTIVE.name(),
                store.findById("stale-active").orElseThrow().status());
    }

    /**
     * Divergence from the SQL backends: {@code SqliteCronStoreAdapter} short-circuits on a null threshold and
     * {@code PostgresCronStoreAdapter} lets SQL's three-valued logic absorb it, so both are no-ops. Here the
     * comparator dereferences the threshold directly.
     */
    @Test
    void reclaimStaleCronsWithANullThresholdFailsOnTheFsBackend() {
        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "d"));

        assertThrows(NullPointerException.class, () -> store.reclaimStaleCrons(null));
    }

    // ─── lease renewal ─────────────────────────────────────────

    @Test
    void renewLeaseRequiresARunningRowOwnedByTheCaller() {
        assertFalse(store.renewLease(null, "d"));
        assertFalse(store.renewLease("", "d"));
        assertFalse(store.renewLease("missing", "d"));
        assertFalse(store.renewLease("c-1", null));
        assertFalse(store.renewLease("c-1", "  "));

        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertFalse(store.renewLease("c-1", "other"));
        assertTrue(store.renewLease("c-1", "owner"));

        store.save(cron("c-2", USER, SESSION, CronStatus.ACTIVE, null, "owner"));
        assertFalse(store.renewLease("c-2", "owner"));
    }

    // ─── ownership-checked transitions ─────────────────────────

    @Test
    void markCompletedIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markCompletedIfOwner(null, "d", Instant.now()));
        assertFalse(store.markCompletedIfOwner("", "d", Instant.now()));
        assertFalse(store.markCompletedIfOwner("missing", "d", Instant.now()));

        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertFalse(store.markCompletedIfOwner("c-1", "other", Instant.now()));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markCompletedIfOwner("c-1", "owner", Instant.now()));
        assertEquals(
                CronStatus.COMPLETED.name(), store.findById("c-1").orElseThrow().status());

        store.save(cron("c-2", USER, SESSION, CronStatus.ACTIVE, null, "owner"));
        assertFalse(store.markCompletedIfOwner("c-2", "owner", Instant.now()));
    }

    @Test
    void markFiredIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markFiredIfOwner(null, "d", Instant.now(), Instant.now()));
        assertFalse(store.markFiredIfOwner("", "d", Instant.now(), Instant.now()));
        assertFalse(store.markFiredIfOwner("missing", "d", Instant.now(), Instant.now()));

        Instant last = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant next = last.plusSeconds(300);

        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertFalse(store.markFiredIfOwner("c-1", "other", last, next));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markFiredIfOwner("c-1", "owner", last, next));
        AgentCronDto fired = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.ACTIVE.name(), fired.status());
        assertEquals(next, fired.nextFireTime());
        assertEquals(last, fired.lastFireTime());

        store.save(cron("c-2", USER, SESSION, CronStatus.ACTIVE, null, "owner"));
        assertFalse(store.markFiredIfOwner("c-2", "owner", last, next));
    }

    @Test
    void markAwaitingApprovalIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markAwaitingApprovalIfOwner(null, "d"));
        assertFalse(store.markAwaitingApprovalIfOwner("", "d"));
        assertFalse(store.markAwaitingApprovalIfOwner("missing", "d"));

        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertFalse(store.markAwaitingApprovalIfOwner("c-1", "other"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markAwaitingApprovalIfOwner("c-1", "owner"));
        assertEquals(
                CronStatus.AWAITING_APPROVAL.name(),
                store.findById("c-1").orElseThrow().status());

        store.save(cron("c-2", USER, SESSION, CronStatus.ACTIVE, null, "owner"));
        assertFalse(store.markAwaitingApprovalIfOwner("c-2", "owner"));
    }

    @Test
    void markAwaitingRetryIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markAwaitingRetryIfOwner(null, "d"));
        assertFalse(store.markAwaitingRetryIfOwner("", "d"));
        assertFalse(store.markAwaitingRetryIfOwner("missing", "d"));

        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, "owner"));
        assertFalse(store.markAwaitingRetryIfOwner("c-1", "other"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markAwaitingRetryIfOwner("c-1", "owner"));
        assertEquals(
                CronStatus.AWAITING_RETRY.name(),
                store.findById("c-1").orElseThrow().status());

        store.save(cron("c-2", USER, SESSION, CronStatus.ACTIVE, null, "owner"));
        assertFalse(store.markAwaitingRetryIfOwner("c-2", "owner"));
    }

    @Test
    void markFailedIfOwnerAcceptsRunningAndBothAwaitingStates() {
        assertFalse(store.markFailedIfOwner(null, "d", "e"));
        assertFalse(store.markFailedIfOwner("", "d", "e"));
        assertFalse(store.markFailedIfOwner("missing", "d", "e"));

        for (CronStatus status : List.of(CronStatus.RUNNING, CronStatus.AWAITING_APPROVAL, CronStatus.AWAITING_RETRY)) {
            String id = "c-" + status.name();
            store.save(cron(id, USER, SESSION, status, null, "owner"));

            assertFalse(store.markFailedIfOwner(id, "other", "boom"));
            assertEquals(status.name(), store.findById(id).orElseThrow().status());

            assertTrue(store.markFailedIfOwner(id, "owner", "boom"));
            AgentCronDto failed = store.findById(id).orElseThrow();
            assertEquals(CronStatus.FAILED.name(), failed.status());
            assertEquals("boom", failed.errorReason());
        }

        store.save(cron("c-done", USER, SESSION, CronStatus.COMPLETED, null, "owner"));
        assertFalse(store.markFailedIfOwner("c-done", "owner", "boom"));
    }

    @Test
    void markFailedIfStatusHonoursTheExpectedStatusIncludingNull() {
        assertFalse(store.markFailedIfStatus(null, CronStatus.ACTIVE.name(), "e"));
        assertFalse(store.markFailedIfStatus("", CronStatus.ACTIVE.name(), "e"));
        assertFalse(store.markFailedIfStatus("missing", CronStatus.ACTIVE.name(), "e"));

        store.save(cron("c-1", USER, SESSION, CronStatus.RUNNING, null, null));

        assertFalse(store.markFailedIfStatus("c-1", CronStatus.ACTIVE.name(), "e"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markFailedIfStatus("c-1", null, "no expectation"));
        AgentCronDto failed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.FAILED.name(), failed.status());
        assertEquals("no expectation", failed.errorReason());
    }

    // ─── deletion ──────────────────────────────────────────────

    @Test
    void deleteByIdAndUserIdOnlyDeletesForTheOwningUser() {
        store.save(cron("c-1", USER, SESSION, CronStatus.ACTIVE, null, null));

        assertEquals(0, store.deleteByIdAndUserId("c-1", "someone-else"));
        assertTrue(store.findById("c-1").isPresent());

        assertEquals(1, store.deleteByIdAndUserId("c-1", USER));
        assertTrue(store.findById("c-1").isEmpty());

        assertEquals(0, store.deleteByIdAndUserId("missing", USER));
    }

    @Test
    void deleteAllByUserIdRemovesRowsAndTheIndex() {
        store.save(cron("a", USER, SESSION, CronStatus.ACTIVE, null, null));
        store.save(cron("b", USER, SESSION, CronStatus.ACTIVE, null, null));
        store.save(cron("c", OTHER_USER, SESSION, CronStatus.ACTIVE, null, null));

        assertEquals(2, store.deleteAllByUserId(USER));

        assertTrue(store.findById("a").isEmpty());
        assertTrue(store.findById("b").isEmpty());
        assertTrue(store.findById("c").isPresent());
        assertFalse(fileStore.exists(List.of("crons", "indices", "by-user", USER + ".json")));
        assertEquals(0, store.deleteAllByUserId(USER));
    }

    @Test
    void deleteAllBySessionIdCountsMatchesIncludingRowsWithoutAUser() {
        store.save(cron("s1", USER, SESSION, CronStatus.ACTIVE, null, null));
        store.save(cron("s2", null, SESSION, CronStatus.ACTIVE, null, null));
        store.save(cron("other", USER, "session-2", CronStatus.ACTIVE, null, null));

        assertEquals(2, store.deleteAllBySessionId(SESSION));

        assertTrue(store.findById("s1").isEmpty());
        assertTrue(store.findById("s2").isEmpty());
        assertTrue(store.findById("other").isPresent());
        assertEquals(0, store.deleteAllBySessionId("no-such-session"));
    }

    // ─── pagination and counting ───────────────────────────────

    @Test
    void findAllFiltersByStatusAndPaginates() {
        seedThreeForPagination();

        List<String> allIds =
                store.findAll(null, 0, 10).stream().map(AgentCronDto::id).toList();
        assertEquals(List.of("p1", "p2", "p3"), allIds);

        assertEquals(3, store.findAll("   ", 0, 10).size());
        assertEquals(2, store.findAll(CronStatus.ACTIVE.name(), 0, 10).size());
        assertEquals(1, store.findAll(CronStatus.FAILED.name(), 0, 10).size());
        assertEquals(1, store.findAll(null, 1, 1).size());
        assertTrue(store.findAll(null, 99, 10).isEmpty());
    }

    @Test
    void countAllHonoursStatusIncludingBlank() {
        seedThreeForPagination();

        assertEquals(3, store.countAll(null));
        assertEquals(3, store.countAll("   "));
        assertEquals(2, store.countAll(CronStatus.ACTIVE.name()));
        assertEquals(0, store.countAll(CronStatus.COMPLETED.name()));
    }

    @Test
    void findByUserIdWithStatusAndPagination() {
        seedThreeForPagination();

        assertEquals(2, store.findByUserId(USER, null, 0, 10).size());
        assertEquals(2, store.findByUserId(USER, "   ", 0, 10).size());
        assertEquals(
                1, store.findByUserId(USER, CronStatus.ACTIVE.name(), 0, 10).size());
        assertEquals(1, store.findByUserId(USER, null, 1, 10).size());
        assertTrue(store.findByUserId(USER, null, 99, 10).isEmpty());
        assertTrue(store.findByUserId("nobody", null, 0, 10).isEmpty());
    }

    @Test
    void countByUserIdHonoursStatusIncludingBlank() {
        seedThreeForPagination();

        assertEquals(2, store.countByUserId(USER, null));
        assertEquals(2, store.countByUserId(USER, "   "));
        assertEquals(1, store.countByUserId(USER, CronStatus.ACTIVE.name()));
        assertEquals(1, store.countByUserId(USER, CronStatus.FAILED.name()));
        assertEquals(0, store.countByUserId(USER, CronStatus.COMPLETED.name()));
        assertEquals(0, store.countByUserId("nobody", null));
    }

    // ─── helpers ───────────────────────────────────────────────

    private void seedThreeForPagination() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(cronAt("p1", USER, SESSION, CronStatus.ACTIVE, base.plusSeconds(100), null, base, base));
        store.save(cronAt("p2", USER, SESSION, CronStatus.FAILED, base.plusSeconds(200), null, base, base));
        store.save(cronAt("p3", OTHER_USER, SESSION, CronStatus.ACTIVE, null, null, base, base));
    }

    private static AgentCronDto cron(
            String id, String userId, String sessionId, CronStatus status, Instant nextFireTime, String deliveryId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return cronAt(id, userId, sessionId, status, nextFireTime, deliveryId, now, now);
    }

    private static AgentCronDto cronAt(
            String id,
            String userId,
            String sessionId,
            CronStatus status,
            Instant nextFireTime,
            String deliveryId,
            Instant createdAt,
            Instant updatedAt) {
        return new AgentCronDto(
                id,
                sessionId,
                userId,
                "chat-1",
                "telegram",
                "platform-1",
                "0 * * * *",
                "UTC",
                false,
                "goal-" + id,
                nextFireTime,
                null,
                status.name(),
                "coordinator",
                null,
                deliveryId,
                createdAt,
                updatedAt);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Keeps {@link #BY_ID} referenced so the constant documents the on-disk layout under test. */
    @Test
    void cronsAreStoredUnderTheByIdDirectory() {
        store.save(cron("c-layout", USER, SESSION, CronStatus.ACTIVE, null, null));
        assertEquals(List.of("c-layout.json"), fileStore.list(BY_ID, ".json"));
    }
}
