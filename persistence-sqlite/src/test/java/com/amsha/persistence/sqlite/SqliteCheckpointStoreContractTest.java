package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.amsha.common.util.TemporalUtils;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Contract suite for {@link SqliteCheckpointStoreAdapter}, driven against a real SQLite database.
 *
 * <p>Mirrors {@code FsCheckpointStoreContractTest} and {@code PostgresCheckpointStoreContractTest} in structure; see
 * {@code FsCronStoreContractTest} for why the spec is duplicated by convention rather than inherited.
 *
 * <p>Where a backend genuinely diverges, the test pins the <em>observed</em> behaviour and says so in a comment
 * rather than being silently weakened.
 *
 * <p>{@code agent_checkpoint} is keyed by {@code (session_id, agent_role)} and is shared across the whole module's
 * test run, so every test uses a fresh {@code UUID}-derived session prefix. Queries that are scoped by session (or
 * by a session prefix that is itself unique) get exact assertions; the global sweeps
 * ({@code findInProgressZombies}, {@code findStaleApprovals}) get membership assertions, and their fixtures are
 * back-dated so nothing another test class left behind can be caught in the sweep.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@SuppressWarnings("java:S1192")
class SqliteCheckpointStoreContractTest {

    private static final String ROLE = "coder";

    @Inject
    SqliteCheckpointStoreAdapter store;

    @Inject
    Jdbi jdbi;

    private String session;

    @BeforeEach
    void setUp() {
        session = "cp-" + UUID.randomUUID();
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
     * through {@code (session_id, agent_role)}. {@code upsertCheckpoint} never inserts an explicit {@code checkpoint_id}
     * (the primary key is auto-assigned and the conflict target is the session/role pair), so the only observable
     * effect of that lookup is that the row's original {@code created_at} survives the re-save.
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

    // ─── helpers ───────────────────────────────────────────────

    private void setUpdatedAt(Long id, Instant updatedAt) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE agent_checkpoint SET updated_at = :ts WHERE checkpoint_id = :id")
                .bind("ts", TemporalUtils.formatIsoLex(updatedAt))
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
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new CheckpointDto(
                null,
                sessionId,
                agentRole,
                status.name(),
                "FULL",
                "{}",
                "chat-1",
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
