package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteCheckpointStoreAdapterDirectTest {

    @Inject
    SqliteCheckpointStoreAdapter adapter;

    @Inject
    Jdbi jdbi;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "chk-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void testSaveAndFindMethods() {
        CheckpointDto dto = new CheckpointDto(
                null,
                sessionId,
                "coordinator",
                CheckpointStatus.IN_PROGRESS.name(),
                "FULL",
                "{}",
                "platform-1",
                "CLI",
                "test goal",
                null,
                null,
                null,
                "shell",
                null,
                "{}",
                null,
                10,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);

        CheckpointDto saved = adapter.save(dto);
        assertNotNull(saved.id());
        assertEquals(sessionId, saved.sessionId());

        Optional<CheckpointDto> byId = adapter.findById(saved.id());
        assertTrue(byId.isPresent());
        assertEquals("IN_PROGRESS", byId.get().status());

        Optional<CheckpointDto> byRole = adapter.findBySessionAndRole(sessionId, "coordinator");
        assertTrue(byRole.isPresent());

        List<CheckpointDto> active = adapter.findActiveBySession(sessionId);
        assertEquals(1, active.size());

        adapter.transitionStatus(saved.id(), CheckpointStatus.COMPLETED.name());

        List<CheckpointDto> activeAfter = adapter.findActiveBySession(sessionId);
        assertTrue(activeAfter.isEmpty());
    }

    @Test
    void testUpsertWithStepsAndFinalize() {
        CheckpointUpsertResult res1 =
                adapter.upsertWithSteps(sessionId, "coordinator", "{}", "FULL", 1, "Goal 1", "{}");
        assertNotNull(res1.checkpoint());
        assertTrue(res1.created());

        ReasoningStepDto step = new ReasoningStepDto("thought", "tool", "{}", "result", 10, 20, true, null, null, null);
        CheckpointUpsertResult res2 =
                adapter.upsertAndFinalize(sessionId, "coordinator", "{}", "COMPLETED", List.of(step), "Goal 1", "{}");
        assertNotNull(res2.checkpoint());
        assertEquals("COMPLETED", res2.checkpoint().status());

        List<ReasoningStepDto> steps = adapter.loadSteps(res2.checkpoint().id());
        assertEquals(1, steps.size());
        assertEquals("tool", steps.get(0).action());
    }

    @Test
    void testClaimForResumeAndClearBatchResuming() {
        CheckpointDto dto = new CheckpointDto(
                null,
                sessionId,
                "coordinator",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                "FULL",
                "{}",
                "p1",
                "CLI",
                "Goal",
                null,
                null,
                null,
                null,
                null,
                "{}",
                null,
                10,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);
        adapter.save(dto);

        boolean claimed = adapter.claimForResume(sessionId, "coordinator");
        assertTrue(claimed);

        boolean claimAgain = adapter.claimForResume(sessionId, "coordinator");
        assertFalse(claimAgain);
    }

    @Test
    void testTransitionsAndDeletions() {
        CheckpointDto dto = new CheckpointDto(
                null,
                sessionId,
                "coder",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                "FULL",
                "{}",
                "p1",
                "CLI",
                "Goal",
                null,
                null,
                null,
                "tool",
                "{}",
                "{}",
                null,
                10,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);
        CheckpointDto saved = adapter.save(dto);

        assertTrue(adapter.atomicTransition(saved.id(), "AWAITING_APPROVAL", "RESUMING"));
        adapter.markCompleted(saved.id());
        adapter.markFailed(saved.id(), "err", "CODE", "trace", "Class");
        adapter.markOrphaned(saved.id(), "orphan");
        adapter.archive(saved.id());
        adapter.stampChannelInfo(saved.id(), "web", "platform");

        adapter.deleteSteps(saved.id());
        adapter.deleteCheckpoint(sessionId, "coder");
        adapter.purgeOldChatHistory(Instant.now().plusSeconds(3600));

        assertFalse(adapter.findById(saved.id()).isPresent());
    }

    @Test
    void testQueriesAndStepsAndBatchOps() {
        CheckpointDto dto = new CheckpointDto(
                null,
                sessionId,
                "coordinator",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                "FULL",
                "{}",
                "platform-chat-1",
                "CLI",
                "Goal Awaiting",
                null,
                null,
                null,
                "tool_test",
                "{\"param\":\"value\"}",
                "{}",
                null,
                10,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);
        CheckpointDto saved = adapter.save(dto);

        assertTrue(adapter.hasPendingApproval(sessionId));
        assertTrue(adapter.hasPendingApprovalForChat("platform-chat-1"));
        assertFalse(adapter.hasPendingApprovalForChat("unknown-chat"));

        assertTrue(adapter.findLatestAwaitingApproval(sessionId).isPresent());
        assertTrue(adapter.findLatestAwaitingApprovalBySessionContaining(sessionId.substring(0, 5))
                .isPresent());
        assertTrue(adapter.findLatestBySession(sessionId).isPresent());
        assertTrue(adapter.findLatestNonArchivedBySession(sessionId).isPresent());
        assertTrue(adapter.findLatestBySessionAndStatus(sessionId, CheckpointStatus.AWAITING_APPROVAL.name())
                .isPresent());

        List<CheckpointDto> byStatus = adapter.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name());
        assertFalse(byStatus.isEmpty());

        List<CheckpointDto> byStatusAndSessions =
                adapter.findByStatusAndSessionIds(CheckpointStatus.AWAITING_APPROVAL.name(), List.of(sessionId), 10, 0);
        assertEquals(1, byStatusAndSessions.size());

        long count = adapter.countByStatusAndSessionIds(CheckpointStatus.AWAITING_APPROVAL.name(), List.of(sessionId));
        assertEquals(1, count);

        List<CheckpointDto> allActive = adapter.findAllActive();
        assertFalse(allActive.isEmpty());

        // Steps operations
        ReasoningStepDto step1 = new ReasoningStepDto(
                "thought", "tool_test", "{\"param\":\"value\"}", "output 1", 10, 20, true, null, null, null);
        adapter.saveSteps(saved.id(), List.of(step1));
        List<ReasoningStepDto> steps = adapter.loadSteps(saved.id());
        assertEquals(1, steps.size());
        assertEquals("tool_test", steps.get(0).action());

        // Batch tool decisions
        adapter.atomicUpdateBatchToolDecision(sessionId, "coordinator", "tool_test", "APPROVED");

        // Claim for retry
        adapter.claimForRetry(saved.id());
        adapter.releaseResumeClaim(sessionId, "coordinator");
    }
}
