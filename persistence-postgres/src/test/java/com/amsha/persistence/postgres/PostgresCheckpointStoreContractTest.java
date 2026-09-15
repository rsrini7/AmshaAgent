package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Contract suite for {@link PostgresCheckpointStoreAdapter}, driven against a real PostgreSQL instance
 * (Testcontainers when Docker is available, otherwise the zonky embedded server — see {@link PostgresTestResource}).
 *
 * <p>Mirrors {@code FsCheckpointStoreContractTest} and {@code SqliteCheckpointStoreContractTest} in structure; see
 * {@code FsCronStoreContractTest} for why the spec is duplicated by convention rather than inherited.
 *
 * <p>This is deliberately <em>not</em> a duplicate of {@code PostgresCheckpointStoreAdapterTest}: that suite mocks
 * {@code Jdbi} and so only proves the adapter delegates to the right DAO method. It cannot tell whether the SQL
 * behind that method is valid, whether the column aliases still line up, or what the query actually returns. Those
 * are the questions this suite answers, so the two are complementary rather than redundant.
 *
 * <p>Where a backend genuinely diverges, the test pins the <em>observed</em> behaviour and says so in a comment
 * rather than being silently weakened.
 *
 * <p>{@code agent_checkpoint} is keyed by {@code (session_id, agent_role)} and the table is shared across the whole
 * module's test run, so every test uses a fresh {@code UUID}-derived session prefix. Queries scoped by session (or
 * by a session prefix that is itself unique) get exact assertions; the global sweeps
 * ({@code findInProgressZombies}, {@code findStaleApprovals}, {@code recoverInProgressZombies}) get membership
 * assertions, and their fixtures are back-dated so nothing another test class left behind can be mistaken for them.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
@SuppressWarnings("java:S1192")
class PostgresCheckpointStoreContractTest {

    private static final String ROLE = "coder";

    /** Safely before any other suite's fixtures; see {@link #purgeOldChatHistoryDeletesOldRowsAndTheirSteps}. */
    private static final Instant ANCIENT = Instant.parse("2001-01-01T00:00:00Z");

    @Inject
    PostgresCheckpointStoreAdapter store;

    @Inject
    Jdbi jdbi;

    private String session;

    @BeforeEach
    void setUp() {
        session = "pgcp-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbi.useHandle(h -> h.createUpdate(
                        "DELETE FROM reasoning_step WHERE checkpoint_id IN (SELECT checkpoint_id FROM agent_checkpoint WHERE session_id LIKE :p)")
                .bind("p", session + "%")
                .execute());
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM agent_checkpoint WHERE session_id LIKE :p")
                .bind("p", session + "%")
                .execute());
    }

    // ─── save ──────────────────────────────────────────────────

    /**
     * The {@code AWAITING_APPROVAL}-on-{@code AWAITING_APPROVAL} merge arm: a decision already recorded on the stored
     * row wins over a {@code PENDING} entry in the incoming row, so an in-flight approval cannot be reverted by a
     * concurrent state save.
     */
    @Test
    void saveMergesBatchDecisionsWhenBothRowsAreAwaitingApproval() {
        store.save(checkpoint(session, ROLE, CheckpointStatus.AWAITING_APPROVAL, "{\"tool\":\"APPROVED\"}"));

        CheckpointDto merged = store.save(checkpoint(
                session, ROLE, CheckpointStatus.AWAITING_APPROVAL, "{\"tool\":\"PENDING\",\"other\":\"PENDING\"}"));

        assertTrue(merged.batchToolDecisions().contains("APPROVED"), merged.batchToolDecisions());
        assertTrue(merged.batchToolDecisions().contains("other"), merged.batchToolDecisions());
    }

    /** The merge is skipped when the incoming row is not itself awaiting approval. */
    @Test
    void saveDoesNotMergeBatchDecisionsOutsideAwaitingApproval() {
        store.save(checkpoint(session, ROLE, CheckpointStatus.AWAITING_APPROVAL, "{\"tool\":\"APPROVED\"}"));

        CheckpointDto saved =
                store.save(checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, "{\"tool\":\"PENDING\"}"));

        assertTrue(saved.batchToolDecisions().contains("PENDING"), saved.batchToolDecisions());
    }

    // ─── saveCheckpointWithSteps ───────────────────────────────

    @Test
    void saveCheckpointWithStepsPersistsTheCheckpointAndItsSteps() {
        CheckpointDto saved = store.saveCheckpointWithSteps(
                checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("a"), step("b")));

        assertNotNull(saved.id());
        assertEquals(
                List.of("a", "b"),
                store.loadSteps(saved.id()).stream()
                        .map(ReasoningStepDto::thoughtJson)
                        .toList());
    }

    @Test
    void saveCheckpointWithStepsReplacesThePreviousStepSet() {
        store.saveCheckpointWithSteps(
                checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("a"), step("b")));

        CheckpointDto second = store.saveCheckpointWithSteps(
                checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("c")));

        assertEquals(
                List.of("c"),
                store.loadSteps(second.id()).stream()
                        .map(ReasoningStepDto::thoughtJson)
                        .toList());
    }

    @Test
    void saveCheckpointWithStepsSkipsTheStepWritesForAnEmptyList() {
        CheckpointDto saved =
                store.saveCheckpointWithSteps(checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of());

        assertNotNull(saved.id());
        assertTrue(store.loadSteps(saved.id()).isEmpty());
    }

    /**
     * When the incoming DTO already carries an id, the adapter resolves the row through {@code findById} rather than
     * through {@code (session_id, agent_role)}. {@code upsertCheckpoint} never inserts an explicit
     * {@code checkpoint_id} (the primary key is auto-assigned and the conflict target is the session/role pair), so
     * the only observable effect of that lookup is that the row's original {@code created_at} survives the re-save.
     */
    @Test
    void saveCheckpointWithStepsResolvesAnExplicitIdThroughFindById() {
        CheckpointDto first = store.saveCheckpointWithSteps(
                checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("a")));

        CheckpointDto second =
                store.saveCheckpointWithSteps(store.findById(first.id()).orElseThrow(), List.of(step("b")));

        assertEquals(first.createdAt(), second.createdAt());
        assertEquals(second.id(), first.id());
        assertEquals(
                List.of("b"),
                store.loadSteps(second.id()).stream()
                        .map(ReasoningStepDto::thoughtJson)
                        .toList());
    }

    // ─── findActiveBySessionTree ───────────────────────────────

    @Test
    void findActiveBySessionTreeRejectsANullRootSession() {
        assertTrue(store.findActiveBySessionTree(null).isEmpty());
    }

    @Test
    void findActiveBySessionTreeMatchesTheRootAndItsDelegatePrefixOnly() {
        store.save(checkpoint(session, "r1", CheckpointStatus.IN_PROGRESS, null));
        store.save(checkpoint(session + ":delegate:sub", "r1", CheckpointStatus.AWAITING_APPROVAL, null));
        store.save(checkpoint(session + ":delegate:other", "r1", CheckpointStatus.AWAITING_RETRY, null));
        store.save(checkpoint(session + ":delegates-x", "r1", CheckpointStatus.IN_PROGRESS, null));
        store.save(checkpoint("other-" + session, "r1", CheckpointStatus.IN_PROGRESS, null));
        store.save(checkpoint(session, "r2", CheckpointStatus.COMPLETED, null));

        // sortedKeys() sorts lexicographically, so "delegate:other" precedes "delegate:sub".
        assertEquals(
                List.of(session + "/r1", session + ":delegate:other/r1", session + ":delegate:sub/r1"),
                sortedKeys(store.findActiveBySessionTree(session)));
    }

    // ─── atomicUpdateBatchToolDecision ─────────────────────────

    @Test
    void atomicUpdateBatchToolDecisionRejectsAMissingCheckpoint() {
        assertFalse(store.atomicUpdateBatchToolDecision("missing-" + session, ROLE, "tool", "APPROVED"));
    }

    @Test
    void atomicUpdateBatchToolDecisionRejectsAWrongStatus() {
        store.save(checkpoint(session, "wrong-status", CheckpointStatus.IN_PROGRESS, "{\"tool\":\"PENDING\"}"));

        assertFalse(store.atomicUpdateBatchToolDecision(session, "wrong-status", "tool", "APPROVED"));
    }

    @Test
    void atomicUpdateBatchToolDecisionRejectsABlankBatchMap() {
        store.save(checkpoint(session, "blank", CheckpointStatus.AWAITING_APPROVAL, null));
        store.save(checkpoint(session, "empty-string", CheckpointStatus.AWAITING_APPROVAL, "   "));

        assertFalse(store.atomicUpdateBatchToolDecision(session, "blank", "tool", "APPROVED"));
        assertFalse(store.atomicUpdateBatchToolDecision(session, "empty-string", "tool", "APPROVED"));
    }

    @Test
    void atomicUpdateBatchToolDecisionRejectsAToolThatIsNotInTheBatch() {
        store.save(checkpoint(session, "not-in-map", CheckpointStatus.AWAITING_APPROVAL, "{\"other\":\"PENDING\"}"));

        assertFalse(store.atomicUpdateBatchToolDecision(session, "not-in-map", "tool", "APPROVED"));
    }

    @Test
    void atomicUpdateBatchToolDecisionRejectsAToolThatIsAlreadyDecided() {
        store.save(checkpoint(session, "decided", CheckpointStatus.AWAITING_APPROVAL, "{\"tool\":\"APPROVED\"}"));

        assertFalse(store.atomicUpdateBatchToolDecision(session, "decided", "tool", "DENIED"));
    }

    /**
     * An unrecognised decision word throws inside {@code ToolDecision.valueOf} and is swallowed by the adapter's
     * catch block, which reports {@code false} rather than propagating.
     */
    @Test
    void atomicUpdateBatchToolDecisionRejectsAnUnknownDecisionWord() {
        store.save(checkpoint(session, "bad-word", CheckpointStatus.AWAITING_APPROVAL, "{\"tool\":\"PENDING\"}"));

        assertFalse(store.atomicUpdateBatchToolDecision(session, "bad-word", "tool", "MAYBE"));
    }

    @Test
    void atomicUpdateBatchToolDecisionPersistsTheDecision() {
        store.save(checkpoint(session, "pending", CheckpointStatus.AWAITING_APPROVAL, "{\"tool\":\"PENDING\"}"));

        assertTrue(store.atomicUpdateBatchToolDecision(session, "pending", "tool", "APPROVED"));

        String decisions =
                store.findBySessionAndRole(session, "pending").orElseThrow().batchToolDecisions();
        assertTrue(decisions.contains("APPROVED"), decisions);
    }

    // ─── global sweeps ─────────────────────────────────────────

    @Test
    void findInProgressZombiesAndStaleApprovalsSelectByStatusAndAge() {
        Instant old = Instant.now().minusSeconds(7200);
        Instant cutoff = Instant.now().minusSeconds(3600);

        Long zombie = store.save(checkpoint(session, "zombie", CheckpointStatus.IN_PROGRESS, null))
                .id();
        Long fresh = store.save(checkpoint(session, "fresh", CheckpointStatus.IN_PROGRESS, null))
                .id();
        Long staleApproval = store.save(checkpoint(session, "stale-approval", CheckpointStatus.AWAITING_APPROVAL, null))
                .id();
        setUpdatedAt(zombie, old);
        setUpdatedAt(staleApproval, old);

        List<Long> zombies = store.findInProgressZombies(cutoff).stream()
                .map(CheckpointDto::id)
                .toList();
        assertTrue(zombies.contains(zombie));
        assertFalse(zombies.contains(fresh));
        assertFalse(zombies.contains(staleApproval));

        List<Long> stale =
                store.findStaleApprovals(cutoff).stream().map(CheckpointDto::id).toList();
        assertTrue(stale.contains(staleApproval));
        assertFalse(stale.contains(zombie));
    }

    @Test
    void recoverInProgressZombiesFailsTheStaleRowsAndReturnsThem() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        Long zombie = store.save(checkpoint(session, "zombie", CheckpointStatus.IN_PROGRESS, null))
                .id();
        Long fresh = store.save(checkpoint(session, "fresh", CheckpointStatus.IN_PROGRESS, null))
                .id();
        setUpdatedAt(zombie, Instant.now().minusSeconds(7200));

        List<Long> recovered = store.recoverInProgressZombies(cutoff, "zombie sweep").stream()
                .map(CheckpointDto::id)
                .toList();
        assertTrue(recovered.contains(zombie));
        assertFalse(recovered.contains(fresh));

        CheckpointDto after = store.findById(zombie).orElseThrow();
        assertEquals(CheckpointStatus.FAILED.name(), after.status());
        assertEquals("zombie sweep", after.failReason());
        assertEquals("ZOMBIE", after.errorCode());
        assertNotNull(after.failedAt());

        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findById(fresh).orElseThrow().status());
    }

    @Test
    void recoverInProgressZombiesIsANoOpWhenNothingIsStale() {
        store.save(checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null));

        assertTrue(store.recoverInProgressZombies(Instant.now().minus(3650, ChronoUnit.DAYS), "none")
                .isEmpty());
    }

    // ─── session-scoped probes ─────────────────────────────────

    @Test
    void isCheckpointExpiredLooksOnlyAtApprovalsOlderThanTheTimeout() {
        Long staleApproval = store.save(checkpoint(session, ROLE, CheckpointStatus.AWAITING_APPROVAL, null))
                .id();
        setUpdatedAt(staleApproval, Instant.now().minusSeconds(7200));

        String freshSession = session + "-fresh";
        store.save(checkpoint(freshSession, ROLE, CheckpointStatus.AWAITING_APPROVAL, null));

        String noApproval = session + "-no-approval";
        store.save(checkpoint(noApproval, ROLE, CheckpointStatus.IN_PROGRESS, null));

        assertTrue(store.isCheckpointExpired(session, 1));
        assertFalse(store.isCheckpointExpired(freshSession, 1));
        assertFalse(store.isCheckpointExpired(noApproval, 1));
        assertFalse(store.isCheckpointExpired("missing-" + session, 1));
    }

    @Test
    void isSessionActiveIgnoresTerminalStatuses() {
        String active = session + "-active";
        store.save(checkpoint(active, ROLE, CheckpointStatus.IN_PROGRESS, null));

        String done = session + "-done";
        store.save(checkpoint(done, ROLE, CheckpointStatus.COMPLETED, null));

        String failed = session + "-failed";
        store.save(checkpoint(failed, ROLE, CheckpointStatus.FAILED, null));

        assertTrue(store.isSessionActive(active));
        assertFalse(store.isSessionActive(done));
        assertFalse(store.isSessionActive(failed));
        assertFalse(store.isSessionActive("missing-" + session));
    }

    @Test
    void hasPendingApprovalAndChatScopedLookupsSeeTheAwaitingRow() {
        String chatId = "chat-" + UUID.randomUUID();
        Long id = store.save(checkpoint(session, ROLE, CheckpointStatus.AWAITING_APPROVAL, null, chatId))
                .id();

        assertTrue(store.hasPendingApproval(session));
        assertTrue(store.hasPendingApprovalForChat(chatId));
        assertFalse(store.hasPendingApprovalForChat("other-" + chatId));

        assertTrue(store.findLatestAwaitingApproval(session).isPresent());
        assertTrue(store.findLatestAwaitingApprovalBySessionContaining(session.substring(5))
                .isPresent());
        assertTrue(store.findLatestBySession(session).isPresent());
        assertTrue(store.findLatestNonArchivedBySession(session).isPresent());
        assertTrue(store.findLatestBySessionAndStatus(session, CheckpointStatus.AWAITING_APPROVAL.name())
                .isPresent());
        assertFalse(store.findLatestBySessionAndStatus(session, CheckpointStatus.COMPLETED.name())
                .isPresent());
        assertTrue(store.findById(id).isPresent());
    }

    /**
     * A {@code cron-} session is excluded from the chat-scoped probe so a scheduled run's pending approval cannot
     * block an interactive chat.
     */
    @Test
    void hasPendingApprovalForChatIgnoresCronSessions() {
        String chatId = "chat-" + UUID.randomUUID();
        store.save(checkpoint("cron-" + session, ROLE, CheckpointStatus.AWAITING_APPROVAL, null, chatId));

        assertFalse(store.hasPendingApprovalForChat(chatId));
    }

    // ─── stampChannelInfo ──────────────────────────────────────

    @Test
    void stampChannelInfoWritesTheChannelColumns() {
        Long id = store.save(checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null))
                .id();

        store.stampChannelInfo(id, "telegram", "tg-" + session);

        CheckpointDto after = store.findById(id).orElseThrow();
        assertEquals("telegram", after.channelType());
        assertEquals("tg-" + session, after.channelPlatformId());
    }

    // ─── status transitions against real SQL ───────────────────

    /**
     * The mocked {@code PostgresCheckpointStoreAdapterTest} proves these calls reach the DAO; this proves the SQL
     * behind them is valid and has the stated effect. Kept as one test because the assertions share the fixture.
     */
    @Test
    void statusTransitionsAndRetryClaimRoundTripThroughSql() {
        Long completed = store.save(checkpoint(session, "r-completed", CheckpointStatus.IN_PROGRESS, null))
                .id();
        store.markCompleted(completed);
        CheckpointDto afterComplete = store.findById(completed).orElseThrow();
        assertEquals(CheckpointStatus.COMPLETED.name(), afterComplete.status());
        assertNotNull(afterComplete.completedAt());

        Long failed = store.save(checkpoint(session, "r-failed", CheckpointStatus.IN_PROGRESS, null))
                .id();
        store.markFailed(failed, "boom", "E_BOOM", "trace-1", "RootCause");
        CheckpointDto afterFail = store.findById(failed).orElseThrow();
        assertEquals(CheckpointStatus.FAILED.name(), afterFail.status());
        assertEquals("boom", afterFail.failReason());
        assertEquals("E_BOOM", afterFail.errorCode());
        assertEquals("trace-1", afterFail.traceId());
        assertEquals("RootCause", afterFail.rootCauseClass());

        Long orphaned = store.save(checkpoint(session, "r-orphaned", CheckpointStatus.IN_PROGRESS, null))
                .id();
        store.markOrphaned(orphaned, "timeout");
        CheckpointDto afterOrphan = store.findById(orphaned).orElseThrow();
        assertEquals(CheckpointStatus.ORPHANED.name(), afterOrphan.status());
        assertEquals("timeout", afterOrphan.failReason());

        Long archived = store.save(checkpoint(session, "r-archived", CheckpointStatus.IN_PROGRESS, null))
                .id();
        store.archive(archived);
        assertEquals(
                CheckpointStatus.ARCHIVED.name(),
                store.findById(archived).orElseThrow().status());
        // findBySessionAndRole filters ARCHIVED out; findAnyBySessionAndRole does not.
        assertTrue(store.findBySessionAndRole(session, "r-archived").isEmpty());
        assertTrue(store.findLatestNonArchivedBySession(session + "-absent").isEmpty());

        Long transitioned = store.save(checkpoint(session, "r-transitioned", CheckpointStatus.IN_PROGRESS, null))
                .id();
        store.transitionStatus(transitioned, CheckpointStatus.AWAITING_RETRY.name());
        assertEquals(
                CheckpointStatus.AWAITING_RETRY.name(),
                store.findById(transitioned).orElseThrow().status());

        assertTrue(store.atomicTransition(
                transitioned, CheckpointStatus.AWAITING_RETRY.name(), CheckpointStatus.IN_PROGRESS.name()));
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findById(transitioned).orElseThrow().status());
        // The row is no longer AWAITING_RETRY, so the guarded transition must not fire a second time.
        assertFalse(store.atomicTransition(
                transitioned, CheckpointStatus.AWAITING_RETRY.name(), CheckpointStatus.IN_PROGRESS.name()));

        // claimForRetry only fires from AWAITING_RETRY.
        assertFalse(store.claimForRetry(transitioned));
        store.transitionStatus(transitioned, CheckpointStatus.AWAITING_RETRY.name());
        assertTrue(store.claimForRetry(transitioned));
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findById(transitioned).orElseThrow().status());
    }

    // ─── resume claim ──────────────────────────────────────────

    @Test
    void claimForResumeTakesTheAwaitingRowAndReleaseRestoresIt() {
        store.save(checkpoint(session, ROLE, CheckpointStatus.AWAITING_APPROVAL, null));

        assertTrue(store.claimForResume(session, ROLE));
        assertEquals(
                CheckpointStatus.RESUMING.name(),
                store.findBySessionAndRole(session, ROLE).orElseThrow().status());

        store.releaseResumeClaim(session, ROLE);
        assertEquals(
                CheckpointStatus.AWAITING_APPROVAL.name(),
                store.findBySessionAndRole(session, ROLE).orElseThrow().status());
    }

    // ─── saveSteps / deleteSteps ───────────────────────────────

    @Test
    void saveStepsReplacesTheSetAndDeleteStepsClearsIt() {
        CheckpointDto saved = store.save(checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null));

        store.saveSteps(saved.id(), List.of(step("a"), step("b")));
        assertEquals(
                List.of("a", "b"),
                store.loadSteps(saved.id()).stream()
                        .map(ReasoningStepDto::thoughtJson)
                        .toList());

        store.saveSteps(saved.id(), List.of(step("c")));
        assertEquals(
                List.of("c"),
                store.loadSteps(saved.id()).stream()
                        .map(ReasoningStepDto::thoughtJson)
                        .toList());

        store.deleteSteps(saved.id());
        assertTrue(store.loadSteps(saved.id()).isEmpty());
    }

    // ─── bulk failure sweep ────────────────────────────────────

    /**
     * Divergence from {@code FsCheckpointStoreAdapter}, which stamps {@code "Session terminated"} here. Both SQL
     * backends stamp {@code "Session cancelled"} — so a caller matching on the reason string sees different text
     * depending on the configured backend.
     */
    @Test
    void markCheckpointsFailedMarksEveryNonTerminalRowInTheSession() {
        store.save(checkpoint(session, "in-progress", CheckpointStatus.IN_PROGRESS, null));
        store.save(checkpoint(session, "awaiting", CheckpointStatus.AWAITING_APPROVAL, null));
        store.save(checkpoint(session, "completed", CheckpointStatus.COMPLETED, null));
        String other = session + "-other";
        store.save(checkpoint(other, ROLE, CheckpointStatus.IN_PROGRESS, null));

        store.markCheckpointsFailed(session);

        CheckpointDto failed =
                store.findBySessionAndRole(session, "in-progress").orElseThrow();
        assertEquals(CheckpointStatus.FAILED.name(), failed.status());
        assertEquals("Session cancelled", failed.failReason());
        assertNotNull(failed.failedAt());

        assertEquals(
                CheckpointStatus.FAILED.name(),
                store.findBySessionAndRole(session, "awaiting").orElseThrow().status());
        assertEquals(
                CheckpointStatus.COMPLETED.name(),
                store.findBySessionAndRole(session, "completed").orElseThrow().status());
        assertEquals(
                CheckpointStatus.IN_PROGRESS.name(),
                store.findBySessionAndRole(other, ROLE).orElseThrow().status());
    }

    // ─── deleteCheckpoint ──────────────────────────────────────

    /**
     * Divergence from {@code FsCheckpointStoreAdapter}: the FS backend removes the checkpoint's reasoning steps
     * alongside the checkpoint ({@code FsCheckpointHelper.deleteStepsForSession}), while the SQL backends only
     * delete the {@code agent_checkpoint} row and leave the {@code reasoning_step} rows orphaned.
     */
    @Test
    void deleteCheckpointLeavesReasoningStepsBehind() {
        CheckpointDto saved = store.saveCheckpointWithSteps(
                checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("a")));

        store.deleteCheckpoint(session, ROLE);

        assertTrue(store.findBySessionAndRole(session, ROLE).isEmpty());
        assertEquals(1, store.loadSteps(saved.id()).size());
    }

    // ─── purge ─────────────────────────────────────────────────

    /**
     * The purge is global and unguarded, so the fixture is dated {@link #ANCIENT} and the cutoff is 2005: no other
     * suite in this module writes a row that old, so the sweep can only reach the row seeded here.
     */
    @Test
    void purgeOldChatHistoryDeletesOldRowsAndTheirSteps() {
        CheckpointDto old = store.saveCheckpointWithSteps(
                checkpoint(session, ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("old")));
        setUpdatedAt(old.id(), ANCIENT);

        CheckpointDto recent = store.saveCheckpointWithSteps(
                checkpoint(session + "-recent", ROLE, CheckpointStatus.IN_PROGRESS, null), List.of(step("recent")));

        store.purgeOldChatHistory(Instant.parse("2005-01-01T00:00:00Z"));

        assertTrue(store.findById(old.id()).isEmpty());
        assertTrue(store.loadSteps(old.id()).isEmpty());
        assertTrue(store.findById(recent.id()).isPresent());
        assertEquals(1, store.loadSteps(recent.id()).size());
    }

    // ─── helpers ───────────────────────────────────────────────

    /** Postgres binds timestamps as {@code Instant}; the SQLite adapter binds ISO-lexicographic strings instead. */
    private void setUpdatedAt(Long id, Instant updatedAt) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE agent_checkpoint SET updated_at = :ts WHERE checkpoint_id = :id")
                .bind("ts", updatedAt)
                .bind("id", id)
                .execute());
    }

    private static List<String> sortedKeys(List<CheckpointDto> checkpoints) {
        return checkpoints.stream()
                .map(c -> c.sessionId() + "/" + c.agentRole())
                .sorted()
                .toList();
    }

    private static CheckpointDto checkpoint(
            String sessionId, String agentRole, CheckpointStatus status, String batchToolDecisions) {
        return checkpoint(sessionId, agentRole, status, batchToolDecisions, "chat-1");
    }

    private static CheckpointDto checkpoint(
            String sessionId,
            String agentRole,
            CheckpointStatus status,
            String batchToolDecisions,
            String channelPlatformId) {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new CheckpointDto(
                null,
                sessionId,
                agentRole,
                status.name(),
                "FULL",
                "{}",
                channelPlatformId,
                "CLI",
                "goal",
                null,
                null,
                null,
                null,
                batchToolDecisions,
                null,
                null,
                10,
                1,
                null,
                null,
                "{}",
                null,
                null,
                null,
                null,
                0,
                t,
                t);
    }

    private static ReasoningStepDto step(String thought) {
        return new ReasoningStepDto(thought, "act-" + thought, "{}", "obs-" + thought, 10, 20, true, null, null, null);
    }
}
