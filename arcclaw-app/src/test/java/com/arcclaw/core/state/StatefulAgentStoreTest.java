package com.arcclaw.core.state;

import com.arcclaw.core.agent.ReasoningStep;
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
    private static final String ROLE = "grok";

    @Inject
    StatefulAgentStore store;

    @BeforeEach
    void activate() {
        WorkspaceScopeHolder.set(SESSION);
    }

    @AfterEach
    void deactivate() {
        WorkspaceScopeHolder.clear();
    }

    /**
     * Verify that a checkpoint can be saved and loaded with correct
     * deserialization.
     */
    @Test
    void testSaveAndLoadCheckpoint() {
        var steps = List.of(
                new ReasoningStep("Analyzing the repo", "github",
                        Map.of("repo", "test/repo"), "Repository has 10 files"),
                new ReasoningStep("Done analyzing", ReasoningStep.FINISH,
                        Map.of("answer", "Analysis complete"), "Task completed."));

        // Save
        var checkpoint = store.save(SESSION, ROLE, steps);
        assertNotNull(checkpoint.id, "Checkpoint should have a generated ID");
        assertEquals(SESSION, checkpoint.sessionId);
        assertEquals(ROLE, checkpoint.agentRole);
        assertEquals(CheckpointStatus.IN_PROGRESS, checkpoint.status);
        assertNotNull(checkpoint.stateJson);
        assertNotNull(checkpoint.createdAt);

        // Load
        var loaded = store.load(SESSION, ROLE);
        assertTrue(loaded.isPresent(), "Checkpoint should be loadable");
        assertEquals(checkpoint.id, loaded.get().id);

        // Deserialize
        var deserializedSteps = store.deserializeSteps(loaded.get());
        assertEquals(2, deserializedSteps.size());
        assertEquals("Analyzing the repo", deserializedSteps.get(0).thought());
        assertEquals("github", deserializedSteps.get(0).action());
        assertEquals("Repository has 10 files", deserializedSteps.get(0).observation());
        assertEquals(ReasoningStep.FINISH, deserializedSteps.get(1).action());
    }

    /**
     * Verify that markFailed sets status, failedAt, and failReason.
     */
    @Test
    void testMarkFailed() {
        var steps = List.of(
                new ReasoningStep("Trying something", "github",
                        Map.of("repo", "fail/repo"), "Error occurred"));

        var checkpoint = store.save(SESSION + "-fail", ROLE, steps);
        store.markFailed(checkpoint.id, "LLM API timeout");

        var loaded = store.load(SESSION + "-fail", ROLE);
        assertTrue(loaded.isPresent());
        assertEquals(CheckpointStatus.FAILED, loaded.get().status);
        assertNotNull(loaded.get().failedAt, "failedAt should be set");
        assertEquals("LLM API timeout", loaded.get().failReason);
    }

    /**
     * Verify that markCompleted transitions the status.
     */
    @Test
    void testMarkCompleted() {
        var steps = List.of(
                new ReasoningStep("All done", ReasoningStep.FINISH,
                        Map.of("answer", "42"), "Task completed."));

        var checkpoint = store.save(SESSION + "-complete", ROLE, steps);
        store.markCompleted(checkpoint.id);

        var loaded = store.load(SESSION + "-complete", ROLE);
        assertTrue(loaded.isPresent());
        assertEquals(CheckpointStatus.COMPLETED, loaded.get().status);
    }

    /**
     * Verify upsert behavior — saving twice to same session+role updates, not
     * duplicates.
     */
    @Test
    void testUpsertBehavior() {
        var steps1 = List.of(
                new ReasoningStep("Step 1", "github", Map.of(), "obs1"));
        var steps2 = List.of(
                new ReasoningStep("Step 1", "github", Map.of(), "obs1"),
                new ReasoningStep("Step 2", "github", Map.of(), "obs2"));

        var cp1 = store.save(SESSION + "-upsert", ROLE, steps1);
        var cp2 = store.save(SESSION + "-upsert", ROLE, steps2);

        assertEquals(cp1.id, cp2.id, "Upsert should update same entity, not create new");

        var deserialized = store.deserializeSteps(cp2);
        assertEquals(2, deserialized.size(), "Should have updated steps");
    }
}
