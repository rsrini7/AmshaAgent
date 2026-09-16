package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.amsha.common.util.TemporalUtils;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Contract suite for {@link SqliteCronStoreAdapter}, driven against a real SQLite database.
 *
 * <p>Mirrors {@code FsCronStoreContractTest} and {@code PostgresCronStoreContractTest} method-for-method. The three
 * adapters live in separate Maven modules with no shared test-jar, so the spec is duplicated by convention rather
 * than inherited; keep the three files in step when the port changes.
 *
 * <p>Where a backend genuinely diverges, the test pins the <em>observed</em> behaviour and says so in a comment
 * rather than being silently weakened. The divergences this suite surfaced are collected in the Phase 3 findings
 * section of {@code docs/TEST_COVERAGE_ANALYSIS.md}.
 *
 * <p>The table is shared across the whole module's test run, so every assertion is either scoped to a freshly
 * generated user/session or expressed as a delta against the count observed before seeding.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@SuppressWarnings("java:S1192")
class SqliteCronStoreContractTest {

    @Inject
    SqliteCronStoreAdapter store;

    @Inject
    Jdbi jdbi;

    private String user;
    private String session;

    @BeforeEach
    void setUp() {
        String scope = UUID.randomUUID().toString();
        user = "user-" + scope;
        session = "session-" + scope;
    }

    @AfterEach
    void tearDown() {
        store.deleteAllByUserId(user);
        store.deleteAllBySessionId(session);
    }

    // ─── save / findById ───────────────────────────────────────

    @Test
    void saveAndFindByIdRoundTripThroughSql() {
        store.save(cron("c-1", user, session, CronStatus.ACTIVE, soon(), null));

        var found = store.findById("c-1");
        assertTrue(found.isPresent());
        assertEquals("goal-c-1", found.get().goal());
        assertEquals(user, found.get().userId());
        assertEquals(session, found.get().sessionId());
    }

    @Test
    void findByIdRejectsNullAndBlankIds() {
        assertTrue(store.findById(null).isEmpty());
        assertTrue(store.findById("").isEmpty());
        assertTrue(store.findById("   ").isEmpty());
    }

    /**
     * Divergence from {@code FsCronStoreAdapter}: the FS backend serialises whatever the DTO carries, so a null
     * {@code nextFireTime} or {@code userId} round-trips happily (its own {@code findDueCrons} even guards for a
     * null fire time). Both SQL schemas declare those columns {@code NOT NULL} — see
     * {@code db/migration/*}{@code /V2__scheduling_and_skills.sql} — so the same save is rejected here.
     */
    @Test
    void saveRejectsColumnsTheSchemaDeclaresNotNull() {
        AgentCronDto noFireTime = cron("c-null-fire", user, session, CronStatus.ACTIVE, soon(), null);
        String fireTimeFailure = assertThrows(
                        RuntimeException.class, () -> store.save(withNextFireTime(noFireTime, null)))
                .getMessage()
                .toLowerCase(Locale.ROOT);
        assertTrue(fireTimeFailure.contains("nextfiretime"), fireTimeFailure);

        AgentCronDto noUser = cron("c-null-user", user, session, CronStatus.ACTIVE, soon(), null);
        String userFailure = assertThrows(RuntimeException.class, () -> store.save(withUserId(noUser, null)))
                .getMessage()
                .toLowerCase(Locale.ROOT);
        assertTrue(userFailure.contains("userid"), userFailure);
    }

    /**
     * Divergence from {@code PostgresCronStoreAdapter}: PostgreSQL's {@code ON CONFLICT DO UPDATE} list omits
     * {@code createdAt}, and the adapter feeds the stored value back in, so the original creation time survives a
     * re-save. SQLite uses {@code INSERT OR REPLACE}, which rewrites the whole row from the DTO — the caller's
     * {@code createdAt} wins. The FS backend behaves like SQLite.
     */
    @Test
    void saveUpsertsAndTakesTheCallersCreatedAt() {
        Instant first = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600);
        store.save(cronAt("c-1", user, session, CronStatus.ACTIVE, soon(), null, first));

        Instant later = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(cronAt("c-1", user, session, CronStatus.FAILED, soon(), null, later));

        AgentCronDto reloaded = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.FAILED.name(), reloaded.status());
        assertEquals(later, reloaded.createdAt());
    }

    // ─── read-side finders ─────────────────────────────────────

    @Test
    void findDueCronsSelectsOnlyActiveCronsWhoseFireTimeHasPassed() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(cron("due", user, session, CronStatus.ACTIVE, now.minusSeconds(60), null));
        store.save(cron("future", user, session, CronStatus.ACTIVE, now.plusSeconds(3600), null));
        store.save(cron("notactive", user, session, CronStatus.COMPLETED, now.minusSeconds(60), null));

        List<String> dueIds =
                store.findDueCrons().stream().map(AgentCronDto::id).toList();
        assertTrue(dueIds.contains("due"));
        assertFalse(dueIds.contains("future"));
        assertFalse(dueIds.contains("notactive"));
    }

    @Test
    void userScopedFindersFilterOnUserIdAndApprovalState() {
        store.save(cron("u1-aa", user, session, CronStatus.AWAITING_APPROVAL, soon(), null));
        store.save(cron("u1-active", user, session, CronStatus.ACTIVE, soon(), null));

        assertEquals(2, store.findByUserId(user).size());
        assertTrue(store.findByUserId("nobody-" + user).isEmpty());

        List<AgentCronDto> awaitingForUser = store.findAwaitingApprovalByUserId(user);
        assertEquals(1, awaitingForUser.size());
        assertEquals("u1-aa", awaitingForUser.get(0).id());

        List<String> allAwaiting =
                store.findAllAwaitingApproval().stream().map(AgentCronDto::id).toList();
        assertTrue(allAwaiting.contains("u1-aa"));
        assertFalse(allAwaiting.contains("u1-active"));
    }

    // ─── claim / release ───────────────────────────────────────

    @Test
    void claimForFireRejectsBlankArgumentsMissingRowsAndWrongStatus() {
        assertFalse(store.claimForFire(null, "d"));
        assertFalse(store.claimForFire("", "d"));
        assertFalse(store.claimForFire("   ", "d"));
        assertFalse(store.claimForFire("missing-" + user, "d"));
        assertFalse(store.claimForFire("c-1", null));
        assertFalse(store.claimForFire("c-1", "  "));

        store.save(cron("c-1", user, session, CronStatus.COMPLETED, soon(), null));
        assertFalse(store.claimForFire("c-1", "d"));
    }

    @Test
    void claimForFireMarksRunningAndRecordsTheDeliveryId() {
        store.save(cron("c-1", user, session, CronStatus.ACTIVE, soon(), null));

        assertTrue(store.claimForFire("c-1", "delivery-1"));

        AgentCronDto claimed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.RUNNING.name(), claimed.status());
        assertEquals("delivery-1", claimed.lastDeliveryId());
    }

    @Test
    void claimForResumeAcceptsAwaitingStatesAndTheRunningOwnerOnly() {
        assertFalse(store.claimForResume(null, "d"));
        assertFalse(store.claimForResume("", "d"));
        assertFalse(store.claimForResume("missing-" + user, "d"));
        assertFalse(store.claimForResume("c-1", null));
        assertFalse(store.claimForResume("c-1", " "));

        store.save(cron("c-aa", user, session, CronStatus.AWAITING_APPROVAL, soon(), "d0"));
        assertTrue(store.claimForResume("c-aa", "d1"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-aa").orElseThrow().status());

        store.save(cron("c-retry", user, session, CronStatus.AWAITING_RETRY, soon(), "d0"));
        assertTrue(store.claimForResume("c-retry", "d2"));

        store.save(cron("c-running-owner", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertTrue(store.claimForResume("c-running-owner", "owner"));

        store.save(cron("c-running-other", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertFalse(store.claimForResume("c-running-other", "someone-else"));

        store.save(cron("c-done", user, session, CronStatus.COMPLETED, soon(), "d0"));
        assertFalse(store.claimForResume("c-done", "d0"));
    }

    @Test
    void releaseClaimToActiveOnlyAppliesToRunningCrons() {
        store.save(cron("c-run", user, session, CronStatus.RUNNING, soon(), "d"));
        store.releaseClaimToActive("c-run");
        assertEquals(
                CronStatus.ACTIVE.name(), store.findById("c-run").orElseThrow().status());

        store.save(cron("c-active", user, session, CronStatus.ACTIVE, soon(), "d"));
        store.releaseClaimToActive("c-active");
        assertEquals(
                CronStatus.ACTIVE.name(),
                store.findById("c-active").orElseThrow().status());

        store.releaseClaimToActive("missing-" + user);
        store.releaseClaimToActive(null);
        store.releaseClaimToActive("   ");
    }

    // ─── unconditional status mutators ─────────────────────────

    /**
     * Divergence from {@code FsCronStoreAdapter}: the FS backend will happily write a null {@code nextFireTime},
     * because it rewrites the whole record. The SQL backends issue a targeted {@code UPDATE}, and the column is
     * {@code NOT NULL}, so clearing the fire time is rejected while clearing {@code lastFireTime} is fine.
     */
    @Test
    void markFiredAcceptsANullLastFireTimeButRejectsANullNextFireTime() {
        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "d"));

        Instant last = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant next = last.plusSeconds(60);

        store.markFired("c-1", null, next);
        AgentCronDto fired = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.ACTIVE.name(), fired.status());
        assertNull(fired.lastFireTime());
        assertEquals(next, fired.nextFireTime());

        String failure = assertThrows(RuntimeException.class, () -> store.markFired("c-1", last, null))
                .getMessage()
                .toLowerCase(Locale.ROOT);
        assertTrue(failure.contains("nextfiretime"), failure);
    }

    /**
     * Divergence from {@code FsCronStoreAdapter}: completing a job clears {@code nextFireTime} in the FS backend
     * (it rewrites the whole record); the SQL backends only update {@code status}/{@code lastFireTime}, so the
     * column keeps its previous value.
     */
    @Test
    void markCompletedLeavesTheNextFireTimeUntouched() {
        Instant next = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
        store.save(cron("c-1", user, session, CronStatus.RUNNING, next, "d"));

        Instant last = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.markCompleted("c-1", last);

        AgentCronDto completed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.COMPLETED.name(), completed.status());
        assertEquals(last, completed.lastFireTime());
        assertEquals(next, completed.nextFireTime());
    }

    @Test
    void statusMutatorsWriteThrough() {
        store.save(cron("c-1", user, session, CronStatus.ACTIVE, soon(), null));

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

    /** Same NOT NULL divergence as {@link #markFiredAcceptsANullLastFireTimeButRejectsANullNextFireTime}. */
    @Test
    void markSkippedRejectsANullNextFireTime() {
        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "d"));

        String failure = assertThrows(RuntimeException.class, () -> store.markSkipped("c-1", null, Instant.now()))
                .getMessage()
                .toLowerCase(Locale.ROOT);
        assertTrue(failure.contains("nextfiretime"), failure);
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

        assertTrue(store.findById("").isEmpty());
    }

    // ─── stale reclaim ─────────────────────────────────────────

    @Test
    void reclaimStaleCronsOnlyTouchesStaleRunningRows() {
        Instant stale = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(7200);
        Instant threshold = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);

        store.save(cron("stale-running", user, session, CronStatus.RUNNING, soon(), "d"));
        store.save(cron("fresh-running", user, session, CronStatus.RUNNING, soon(), "d"));
        store.save(cron("stale-active", user, session, CronStatus.ACTIVE, soon(), "d"));

        setUpdatedAt("stale-running", stale);
        setUpdatedAt("fresh-running", Instant.now());
        setUpdatedAt("stale-active", stale);

        store.reclaimStaleCrons(threshold);

        assertEquals(
                CronStatus.ACTIVE.name(),
                store.findById("stale-running").orElseThrow().status());
        assertNull(store.findById("stale-running").orElseThrow().lastDeliveryId());
        assertEquals(
                CronStatus.RUNNING.name(),
                store.findById("fresh-running").orElseThrow().status());
        assertEquals(
                CronStatus.ACTIVE.name(),
                store.findById("stale-active").orElseThrow().status());
    }

    @Test
    void reclaimStaleCronsFallsBackToCreatedAtWhenUpdatedAtIsNull() {
        Instant stale = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(7200);
        Instant threshold = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);

        store.save(cron("legacy", user, session, CronStatus.RUNNING, soon(), "d"));
        jdbi.useHandle(
                h -> h.createUpdate("UPDATE agent_cron SET updatedAt = NULL, createdAt = :created WHERE id = :id")
                        .bind("created", TemporalUtils.formatIsoLex(stale))
                        .bind("id", "legacy")
                        .execute());

        store.reclaimStaleCrons(threshold);

        assertEquals(
                CronStatus.ACTIVE.name(), store.findById("legacy").orElseThrow().status());
    }

    @Test
    void reclaimStaleCronsIgnoresANullThreshold() {
        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "d"));

        store.reclaimStaleCrons(null);

        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());
    }

    // ─── lease renewal ─────────────────────────────────────────

    @Test
    void renewLeaseRequiresARunningRowOwnedByTheCaller() {
        assertFalse(store.renewLease(null, "d"));
        assertFalse(store.renewLease("", "d"));
        assertFalse(store.renewLease("missing-" + user, "d"));
        assertFalse(store.renewLease("c-1", null));
        assertFalse(store.renewLease("c-1", "  "));

        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertFalse(store.renewLease("c-1", "other"));
        assertTrue(store.renewLease("c-1", "owner"));

        store.save(cron("c-2", user, session, CronStatus.ACTIVE, soon(), "owner"));
        assertFalse(store.renewLease("c-2", "owner"));
    }

    // ─── ownership-checked transitions ─────────────────────────

    @Test
    void markCompletedIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markCompletedIfOwner(null, "d", Instant.now()));
        assertFalse(store.markCompletedIfOwner("", "d", Instant.now()));
        assertFalse(store.markCompletedIfOwner("missing-" + user, "d", Instant.now()));

        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertFalse(store.markCompletedIfOwner("c-1", "other", Instant.now()));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markCompletedIfOwner("c-1", "owner", Instant.now()));
        assertEquals(
                CronStatus.COMPLETED.name(), store.findById("c-1").orElseThrow().status());

        store.save(cron("c-2", user, session, CronStatus.ACTIVE, soon(), "owner"));
        assertFalse(store.markCompletedIfOwner("c-2", "owner", Instant.now()));
    }

    @Test
    void markFiredIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markFiredIfOwner(null, "d", Instant.now(), Instant.now()));
        assertFalse(store.markFiredIfOwner("", "d", Instant.now(), Instant.now()));
        assertFalse(store.markFiredIfOwner("missing-" + user, "d", Instant.now(), Instant.now()));

        Instant last = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant next = last.plusSeconds(300);

        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertFalse(store.markFiredIfOwner("c-1", "other", last, next));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markFiredIfOwner("c-1", "owner", last, next));
        AgentCronDto fired = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.ACTIVE.name(), fired.status());
        assertEquals(next, fired.nextFireTime());
        assertEquals(last, fired.lastFireTime());

        store.save(cron("c-2", user, session, CronStatus.ACTIVE, soon(), "owner"));
        assertFalse(store.markFiredIfOwner("c-2", "owner", last, next));
    }

    @Test
    void markAwaitingApprovalIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markAwaitingApprovalIfOwner(null, "d"));
        assertFalse(store.markAwaitingApprovalIfOwner("", "d"));
        assertFalse(store.markAwaitingApprovalIfOwner("missing-" + user, "d"));

        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertFalse(store.markAwaitingApprovalIfOwner("c-1", "other"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markAwaitingApprovalIfOwner("c-1", "owner"));
        assertEquals(
                CronStatus.AWAITING_APPROVAL.name(),
                store.findById("c-1").orElseThrow().status());

        store.save(cron("c-2", user, session, CronStatus.ACTIVE, soon(), "owner"));
        assertFalse(store.markAwaitingApprovalIfOwner("c-2", "owner"));
    }

    @Test
    void markAwaitingRetryIfOwnerRequiresTheOwningDeliveryId() {
        assertFalse(store.markAwaitingRetryIfOwner(null, "d"));
        assertFalse(store.markAwaitingRetryIfOwner("", "d"));
        assertFalse(store.markAwaitingRetryIfOwner("missing-" + user, "d"));

        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), "owner"));
        assertFalse(store.markAwaitingRetryIfOwner("c-1", "other"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markAwaitingRetryIfOwner("c-1", "owner"));
        assertEquals(
                CronStatus.AWAITING_RETRY.name(),
                store.findById("c-1").orElseThrow().status());

        store.save(cron("c-2", user, session, CronStatus.ACTIVE, soon(), "owner"));
        assertFalse(store.markAwaitingRetryIfOwner("c-2", "owner"));
    }

    @Test
    void markFailedIfOwnerAcceptsRunningAndBothAwaitingStates() {
        assertFalse(store.markFailedIfOwner(null, "d", "e"));
        assertFalse(store.markFailedIfOwner("", "d", "e"));
        assertFalse(store.markFailedIfOwner("missing-" + user, "d", "e"));

        for (CronStatus status : List.of(CronStatus.RUNNING, CronStatus.AWAITING_APPROVAL, CronStatus.AWAITING_RETRY)) {
            String id = "c-" + status.name();
            store.save(cron(id, user, session, status, soon(), "owner"));

            assertFalse(store.markFailedIfOwner(id, "other", "boom"));
            assertEquals(status.name(), store.findById(id).orElseThrow().status());

            assertTrue(store.markFailedIfOwner(id, "owner", "boom"));
            AgentCronDto failed = store.findById(id).orElseThrow();
            assertEquals(CronStatus.FAILED.name(), failed.status());
            assertEquals("boom", failed.errorReason());
        }

        store.save(cron("c-done", user, session, CronStatus.COMPLETED, soon(), "owner"));
        assertFalse(store.markFailedIfOwner("c-done", "owner", "boom"));
    }

    /**
     * Divergence from {@code FsCronStoreAdapter}: the FS backend treats a null {@code expectedStatus} as "no
     * expectation" and fails the row; the SQL backends compare {@code status = :expectedStatus}, and SQL's
     * three-valued logic means {@code status = NULL} never matches, so the call is a no-op.
     */
    @Test
    void markFailedIfStatusComparesAgainstAnExplicitExpectedStatus() {
        assertFalse(store.markFailedIfStatus(null, CronStatus.ACTIVE.name(), "e"));
        assertFalse(store.markFailedIfStatus("", CronStatus.ACTIVE.name(), "e"));
        assertFalse(store.markFailedIfStatus("missing-" + user, CronStatus.ACTIVE.name(), "e"));

        store.save(cron("c-1", user, session, CronStatus.RUNNING, soon(), null));

        assertFalse(store.markFailedIfStatus("c-1", CronStatus.ACTIVE.name(), "e"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertFalse(store.markFailedIfStatus("c-1", null, "no expectation"));
        assertEquals(
                CronStatus.RUNNING.name(), store.findById("c-1").orElseThrow().status());

        assertTrue(store.markFailedIfStatus("c-1", CronStatus.RUNNING.name(), "boom"));
        AgentCronDto failed = store.findById("c-1").orElseThrow();
        assertEquals(CronStatus.FAILED.name(), failed.status());
        assertEquals("boom", failed.errorReason());
    }

    // ─── deletion ──────────────────────────────────────────────

    @Test
    void deleteByIdAndUserIdOnlyDeletesForTheOwningUser() {
        store.save(cron("c-1", user, session, CronStatus.ACTIVE, soon(), null));

        assertEquals(0, store.deleteByIdAndUserId("c-1", "someone-else"));
        assertTrue(store.findById("c-1").isPresent());

        assertEquals(0, store.deleteByIdAndUserId(null, user));
        assertEquals(0, store.deleteByIdAndUserId("   ", user));
        assertEquals(0, store.deleteByIdAndUserId("missing-" + user, user));

        assertEquals(1, store.deleteByIdAndUserId("c-1", user));
        assertTrue(store.findById("c-1").isEmpty());
    }

    @Test
    void deleteAllByUserIdRemovesOnlyThatUsersRows() {
        store.save(cron("a", user, session, CronStatus.ACTIVE, soon(), null));
        store.save(cron("b", user, session, CronStatus.ACTIVE, soon(), null));

        assertEquals(2, store.deleteAllByUserId(user));

        assertTrue(store.findById("a").isEmpty());
        assertTrue(store.findById("b").isEmpty());
        assertEquals(0, store.deleteAllByUserId(user));
    }

    @Test
    void deleteAllBySessionIdRemovesOnlyThatSessionsRows() {
        store.save(cron("s1", user, session, CronStatus.ACTIVE, soon(), null));
        store.save(cron("other", user, "other-" + session, CronStatus.ACTIVE, soon(), null));

        assertEquals(1, store.deleteAllBySessionId(session));

        assertTrue(store.findById("s1").isEmpty());
        assertTrue(store.findById("other").isPresent());
        assertEquals(0, store.deleteAllBySessionId("no-such-session"));
    }

    // ─── pagination and counting ───────────────────────────────

    @Test
    void findAllFiltersByStatusAndPaginates() {
        long allBefore = store.countAll(null);
        long activeBefore = store.countAll(CronStatus.ACTIVE.name());
        long failedBefore = store.countAll(CronStatus.FAILED.name());
        seedThree();

        assertEquals(allBefore + 3, store.findAll(null, 0, 10_000).size());
        assertEquals(allBefore + 3, store.findAll("   ", 0, 10_000).size());
        assertEquals(
                activeBefore + 2,
                store.findAll(CronStatus.ACTIVE.name(), 0, 10_000).size());
        assertEquals(
                failedBefore + 1,
                store.findAll(CronStatus.FAILED.name(), 0, 10_000).size());
    }

    @Test
    void userScopedPaginationIsDeterministic() {
        seedThree();

        List<String> ids = store.findByUserId(user, null, 0, 10).stream()
                .map(AgentCronDto::id)
                .toList();
        assertEquals(List.of("p1", "p2", "p3"), ids);

        assertEquals(3, store.findByUserId(user, "   ", 0, 10).size());
        assertEquals(
                2, store.findByUserId(user, CronStatus.ACTIVE.name(), 0, 10).size());
        assertEquals(
                1, store.findByUserId(user, CronStatus.FAILED.name(), 0, 10).size());
        assertEquals(1, store.findByUserId(user, null, 1, 1).size());
        assertTrue(store.findByUserId(user, null, 99, 10).isEmpty());
        assertTrue(store.findByUserId("nobody-" + user, null, 0, 10).isEmpty());
    }

    @Test
    void countAllHonoursStatusIncludingBlank() {
        long allBefore = store.countAll(null);
        long activeBefore = store.countAll(CronStatus.ACTIVE.name());
        long failedBefore = store.countAll(CronStatus.FAILED.name());
        seedThree();

        assertEquals(allBefore + 3, store.countAll(null));
        assertEquals(allBefore + 3, store.countAll("   "));
        assertEquals(activeBefore + 2, store.countAll(CronStatus.ACTIVE.name()));
        assertEquals(failedBefore + 1, store.countAll(CronStatus.FAILED.name()));
    }

    @Test
    void countByUserIdHonoursStatusIncludingBlank() {
        seedThree();

        assertEquals(3, store.countByUserId(user, null));
        assertEquals(3, store.countByUserId(user, "   "));
        assertEquals(2, store.countByUserId(user, CronStatus.ACTIVE.name()));
        assertEquals(1, store.countByUserId(user, CronStatus.FAILED.name()));
        assertEquals(0, store.countByUserId(user, CronStatus.COMPLETED.name()));
        assertEquals(0, store.countByUserId("nobody-" + user, null));
    }

    // ─── helpers ───────────────────────────────────────────────

    private void seedThree() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(cron("p1", user, session, CronStatus.ACTIVE, base.plusSeconds(100), null));
        store.save(cron("p2", user, session, CronStatus.FAILED, base.plusSeconds(200), null));
        store.save(cron("p3", user, session, CronStatus.ACTIVE, base.plusSeconds(300), null));
    }

    private void setUpdatedAt(String id, Instant updatedAt) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE agent_cron SET updatedAt = :ts WHERE id = :id")
                .bind("ts", TemporalUtils.formatIsoLex(updatedAt))
                .bind("id", id)
                .execute());
    }

    private static Instant soon() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(3600);
    }

    private static AgentCronDto cron(
            String id, String userId, String sessionId, CronStatus status, Instant nextFireTime, String deliveryId) {
        return cronAt(
                id,
                userId,
                sessionId,
                status,
                nextFireTime,
                deliveryId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static AgentCronDto cronAt(
            String id,
            String userId,
            String sessionId,
            CronStatus status,
            Instant nextFireTime,
            String deliveryId,
            Instant createdAt) {
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
                createdAt);
    }

    private static AgentCronDto withNextFireTime(AgentCronDto dto, Instant nextFireTime) {
        return copy(dto, dto.userId(), nextFireTime);
    }

    private static AgentCronDto withUserId(AgentCronDto dto, String userId) {
        return copy(dto, userId, dto.nextFireTime());
    }

    private static AgentCronDto copy(AgentCronDto dto, String userId, Instant nextFireTime) {
        return new AgentCronDto(
                dto.id(),
                dto.sessionId(),
                userId,
                dto.chatId(),
                dto.channelType(),
                dto.channelPlatformId(),
                dto.cronExpression(),
                dto.timezone(),
                dto.runOnce(),
                dto.goal(),
                nextFireTime,
                dto.lastFireTime(),
                dto.status(),
                dto.executionRole(),
                dto.errorReason(),
                dto.lastDeliveryId(),
                dto.createdAt(),
                dto.updatedAt());
    }
}
