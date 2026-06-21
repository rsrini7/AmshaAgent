package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.scope.WorkspaceScopeHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the stateful agent store - checkpoint persistence,
 * deserialization, and lifecycle transitions.
 */
@QuarkusTest
class StatefulAgentStoreTest {

    private static final String SESSION = "test-checkpoint-session";
    private static final String ROLE = "coordinator";

    @Inject
    StatefulAgentStore store;

    @InjectMock
    AuditService auditService;

    @BeforeEach
    void activate() {
        // Clear any pending background writes from previous tests
        auditService.waitForIdle();
    }

    @AfterEach
    void deactivate() {
        // No-op
    }

    /**
     * Verify that a checkpoint can be saved and loaded with correct
     * deserialization.
     */
    @Test
    void testSaveAndLoadCheckpoint() {
        String session = SESSION + "-" + System.nanoTime();
        WorkspaceScopeHolder.run(session, () -> {
            var steps = List.of(
                    ReasoningStep.of(
                            "Analyzing the repo",
                            ToolCapability.GITHUB.toolName(),
                            Map.of("repo", "test/repo"),
                            "Repository has 10 files",
                            0,
                            0),
                    ReasoningStep.of(
                            "Done analyzing",
                            ReasoningStep.FINISH,
                            Map.of("answer", "Analysis complete"),
                            "Task completed.",
                            0,
                            0));

            // Save
            var checkpoint = store.save(session, ROLE, steps, "Analyzing the repo");
            assertNotNull(checkpoint.getId(), "Checkpoint should have a generated ID");
            assertEquals(session, checkpoint.getSessionId());
            assertEquals(ROLE, checkpoint.getAgentRole());
            assertEquals("Analyzing the repo", checkpoint.getOriginalGoal());
            assertEquals(CheckpointStatus.IN_PROGRESS, checkpoint.getStatus());
            assertNotNull(checkpoint.getStateJson());
            assertNotNull(checkpoint.getCreatedAt());

            // Load
            var loaded = store.load(session, ROLE);
            assertTrue(loaded.isPresent(), "Checkpoint should be loadable");
            assertEquals(checkpoint.getId(), loaded.get().getId());

            // Deserialize
            var deserializedSteps = store.deserializeSteps(loaded.get());
            System.out.println(
                    "DEBUG: step class: " + deserializedSteps.get(1).getClass().getName());
            System.out.println("DEBUG: step action: " + deserializedSteps.get(1).action());
            assertEquals(2, deserializedSteps.size());
            assertEquals("Analyzing the repo", deserializedSteps.get(0).thought());
            assertEquals(
                    ToolCapability.GITHUB.toolName(), deserializedSteps.get(0).action());
            assertEquals("Repository has 10 files", deserializedSteps.get(0).observation());
            assertEquals(ReasoningStep.FINISH, deserializedSteps.get(1).action());
        });
    }

    /**
     * Verify that markFailed sets status, failedAt, and failReason.
     */
    @Test
    void testMarkFailed() {
        String sessionFail = SESSION + "-fail";
        WorkspaceScopeHolder.run(sessionFail, () -> {
            var steps = List.of(ReasoningStep.of(
                    "Trying something",
                    ToolCapability.GITHUB.toolName(),
                    Map.of("repo", "fail/repo"),
                    "Error occurred",
                    0,
                    0));

            var checkpoint = store.save(sessionFail, ROLE, steps, "Trying something");
            store.markFailed(checkpoint.getId(), "LLM API timeout");

            var loaded = store.load(sessionFail, ROLE);
            assertTrue(loaded.isPresent());
            assertEquals(CheckpointStatus.FAILED, loaded.get().getStatus());
            assertNotNull(loaded.get().getFailedAt(), "failedAt should be set");
            assertEquals("LLM API timeout", loaded.get().getFailReason());
        });
    }

    /**
     * Verify that markCompleted transitions the status.
     */
    @Test
    void testMarkCompleted() {
        String sessionComplete = SESSION + "-complete";
        WorkspaceScopeHolder.run(sessionComplete, () -> {
            var steps = List.of(ReasoningStep.of(
                    "All done", ReasoningStep.FINISH, Map.of("answer", "42"), "Task completed.", 0, 0));

            var checkpoint = store.save(sessionComplete, ROLE, steps, "All done");
            store.markCompleted(checkpoint.getId());

            var loaded = store.load(sessionComplete, ROLE);
            assertTrue(loaded.isPresent());
            // §7.31: markCompleted now automatically archives to prevent turn shadowing
            assertEquals(CheckpointStatus.ARCHIVED, loaded.get().getStatus());
        });
    }

    /**
     * Verify upsert behavior - saving twice to same session+role updates, not
     * duplicates.
     */
    @Test
    void testUpsertBehavior() {
        String sessionUpsert = SESSION + "-upsert";
        WorkspaceScopeHolder.run(sessionUpsert, () -> {
            var steps1 = List.of(ReasoningStep.of("Step 1", ToolCapability.GITHUB.toolName(), Map.of(), "obs1", 0, 0));
            var steps2 = List.of(
                    ReasoningStep.of("Step 1", ToolCapability.GITHUB.toolName(), Map.of(), "obs1", 0, 0),
                    ReasoningStep.of("Step 2", ToolCapability.GITHUB.toolName(), Map.of(), "obs2", 0, 0));

            var cp1 = store.save(sessionUpsert, ROLE, steps1, "Goal 1");
            var cp2 = store.save(sessionUpsert, ROLE, steps2, "Goal 1");

            assertEquals(cp1.getId(), cp2.getId(), "Upsert should update same entity, not create new");

            var deserialized = store.deserializeSteps(cp2);
            assertEquals(2, deserialized.size(), "Should have updated steps");
        });
    }

    @Test
    void testFindLatestAwaitingApprovalForCron() {
        WorkspaceScopeHolder.run("cron-test-session", () -> {
            // Given: three checkpoints for the same cronId, different sessions and statuses
            String role = "coordinator";
            String cronId = "12345";

            // 1) Old AWAITING_APPROVAL
            var steps1 = List.of(ReasoningStep.of(
                    "Thinking 1", ToolCapability.GITHUB.toolName(), Map.of("repo", "old"), "obs", 0, 0));
            var cp1 = store.save(AgentSentinels.CRON_SESSION_PREFIX + cronId + "-old", role, steps1, "Cron Goal");
            cp1.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            store.save(cp1);

            // 2) Newer AWAITING_APPROVAL
            var steps2 = List.of(ReasoningStep.of(
                    "Thinking 2", ToolCapability.GITHUB.toolName(), Map.of("repo", "new"), "obs", 0, 0));
            var cp2 = store.save(AgentSentinels.CRON_SESSION_PREFIX + cronId + "-new", role, steps2, "Cron Goal");
            cp2.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            store.save(cp2);

            // 3) COMPLETED (should be ignored)
            var steps3 = List.of(
                    ReasoningStep.of("Done", ReasoningStep.FINISH, Map.of("answer", "42"), "Task completed.", 0, 0));
            var cp3 = store.save(AgentSentinels.CRON_SESSION_PREFIX + cronId + "-completed", role, steps3, "Cron Goal");
            cp3.setStatus(CheckpointStatus.COMPLETED);
            store.save(cp3);

            // When: we query latest awaiting approval for this cronId
            var found = store.findLatestAwaitingApprovalBySessionContaining(cronId);

            // Then: we get the newest AWAITING_APPROVAL checkpoint (cp2)
            assertTrue(found.isPresent(), "Expected an awaiting-approval checkpoint");
            AgentCheckpoint checkpoint = found.get();
            assertEquals(
                    AgentSentinels.CRON_SESSION_PREFIX + cronId + "-new",
                    checkpoint.getSessionId(),
                    "Should return the latest AWAITING_APPROVAL cron session");
            assertEquals(CheckpointStatus.AWAITING_APPROVAL, checkpoint.getStatus());
            assertEquals(role, checkpoint.getAgentRole());
        });
    }

    /**
     * Verify that saveAndFinalize persists both the checkpoint and granular
     * steps in a single atomic transaction, and that loadSteps correctly
     * reconstructs them.
     */
    @Test
    void testSaveAndFinalizeAndLoadSteps() {
        String sessionFinal = SESSION + "-finalize";
        WorkspaceScopeHolder.run(sessionFinal, () -> {
            var steps = List.of(
                    ReasoningStep.of("Step A", "cmd1", Map.of("p", "v"), "obs1", 10, 5),
                    ReasoningStep.of("Step B", ReasoningStep.FINISH, Map.of("ans", "42"), "obs2", 20, 10));

            // Save and finalize
            var cp = store.saveAndFinalize(sessionFinal, ROLE, steps, CheckpointStatus.COMPLETED, "Step A");

            assertNotNull(cp.getId());
            assertEquals(CheckpointStatus.COMPLETED, cp.getStatus());
            assertNotNull(cp.getCompletedAt());
            assertNotNull(cp.getStateJson(), "Dual-write stateJson must be populated");

            // Load via loadSteps
            var loadedSteps = store.loadSteps(cp.getId());
            assertEquals(2, loadedSteps.size());

            assertEquals("Step A", loadedSteps.get(0).thought());
            assertEquals("cmd1", loadedSteps.get(0).action());
            assertEquals("v", loadedSteps.get(0).actionInput().get("p"));
            assertEquals("obs1", loadedSteps.get(0).observation());
            assertEquals(10, loadedSteps.get(0).inputTokens());
            assertEquals(5, loadedSteps.get(0).outputTokens());

            assertEquals("Step B", loadedSteps.get(1).thought());
            assertEquals(ReasoningStep.FINISH, loadedSteps.get(1).action());
            assertEquals("42", loadedSteps.get(1).actionInput().get("ans"));
        });
    }
}
