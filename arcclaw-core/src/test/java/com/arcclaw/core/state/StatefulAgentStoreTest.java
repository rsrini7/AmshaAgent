package com.arcclaw.core.state;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the stateful agent store — checkpoint persistence,
 * deserialization, and lifecycle transitions.
 */
@QuarkusTest
class StatefulAgentStoreTest {

    private static final String SESSION = "test-checkpoint-session";
    private static final String ROLE = "coordinator";

    @Inject
    StatefulAgentStore store;

    @Inject
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
        WorkspaceScopeHolder.run(SESSION, () -> {
            var steps = List.of(
                    new ReasoningStep("Analyzing the repo", "github",
                            Map.of("repo", "test/repo"), "Repository has 10 files", 0, 0),
                    new ReasoningStep("Done analyzing", ReasoningStep.FINISH,
                            Map.of("answer", "Analysis complete"), "Task completed.", 0, 0));

            // Save
            var checkpoint = store.save(SESSION, ROLE, steps);
            assertNotNull(checkpoint.getId(), "Checkpoint should have a generated ID");
            assertEquals(SESSION, checkpoint.getSessionId());
            assertEquals(ROLE, checkpoint.getAgentRole());
            assertEquals(CheckpointStatus.IN_PROGRESS, checkpoint.getStatus());
            assertNotNull(checkpoint.getStateJson());
            assertNotNull(checkpoint.getCreatedAt());

            // Load
            var loaded = store.load(SESSION, ROLE);
            assertTrue(loaded.isPresent(), "Checkpoint should be loadable");
            assertEquals(checkpoint.getId(), loaded.get().getId());

            // Deserialize
            var deserializedSteps = store.deserializeSteps(loaded.get());
            assertEquals(2, deserializedSteps.size());
            assertEquals("Analyzing the repo", deserializedSteps.get(0).thought());
            assertEquals("github", deserializedSteps.get(0).action());
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
            var steps = List.of(
                    new ReasoningStep("Trying something", "github",
                            Map.of("repo", "fail/repo"), "Error occurred", 0, 0));

            var checkpoint = store.save(sessionFail, ROLE, steps);
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
            var steps = List.of(
                    new ReasoningStep("All done", ReasoningStep.FINISH,
                            Map.of("answer", "42"), "Task completed.", 0, 0));

            var checkpoint = store.save(sessionComplete, ROLE, steps);
            store.markCompleted(checkpoint.getId());

            var loaded = store.load(sessionComplete, ROLE);
            assertTrue(loaded.isPresent());
            assertEquals(CheckpointStatus.COMPLETED, loaded.get().getStatus());
        });
    }

    /**
     * Verify upsert behavior — saving twice to same session+role updates, not
     * duplicates.
     */
    @Test
    void testUpsertBehavior() {
        String sessionUpsert = SESSION + "-upsert";
        WorkspaceScopeHolder.run(sessionUpsert, () -> {
            var steps1 = List.of(
                    new ReasoningStep("Step 1", "github", Map.of(), "obs1", 0, 0));
            var steps2 = List.of(
                    new ReasoningStep("Step 1", "github", Map.of(), "obs1", 0, 0),
                    new ReasoningStep("Step 2", "github", Map.of(), "obs2", 0, 0));

            var cp1 = store.save(sessionUpsert, ROLE, steps1);
            var cp2 = store.save(sessionUpsert, ROLE, steps2);

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
            var steps1 = List.of(
                    new ReasoningStep("Thinking 1", "github",
                            Map.of("repo", "old"), "obs", 0, 0)
            );
            var cp1 = store.save("cron-" + cronId + "-old", role, steps1);
            cp1.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            store.save(cp1);

            // 2) Newer AWAITING_APPROVAL
            var steps2 = List.of(
                    new ReasoningStep("Thinking 2", "github",
                            Map.of("repo", "new"), "obs", 0, 0)
            );
            var cp2 = store.save("cron-" + cronId + "-new", role, steps2);
            cp2.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            store.save(cp2);

            // 3) COMPLETED (should be ignored)
            var steps3 = List.of(
                    new ReasoningStep("Done", ReasoningStep.FINISH,
                            Map.of("answer", "42"), "Task completed.", 0, 0)
            );
            var cp3 = store.save("cron-" + cronId + "-completed", role, steps3);
            cp3.setStatus(CheckpointStatus.COMPLETED);
            store.save(cp3);

            // When: we query latest awaiting approval for this cronId
            var found = store.findLatestAwaitingApprovalForCron(cronId);

            // Then: we get the newest AWAITING_APPROVAL checkpoint (cp2)
            assertTrue(found.isPresent(), "Expected an awaiting-approval checkpoint");
            AgentCheckpoint checkpoint = found.get();
            assertEquals("cron-" + cronId + "-new", checkpoint.getSessionId(),
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
                    new ReasoningStep("Step A", "cmd1", Map.of("p", "v"), "obs1", 10, 5),
                    new ReasoningStep("Step B", ReasoningStep.FINISH, Map.of("ans", "42"), "obs2", 20, 10));

            // Save and finalize
            var cp = store.saveAndFinalize(sessionFinal, ROLE, steps, CheckpointStatus.COMPLETED);

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
