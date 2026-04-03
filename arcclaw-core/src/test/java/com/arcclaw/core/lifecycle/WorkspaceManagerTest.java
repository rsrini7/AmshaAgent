package com.arcclaw.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link WorkspaceManager}.
 * <p>
 * Verifies zombie recovery on startup, GC sweep expiration,
 * and session termination behavior.
 */
@QuarkusTest
class WorkspaceManagerTest {

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    SessionReaper sessionReaper;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    AuditService auditService;

    @Inject
    EntityManager em;

    @BeforeEach
    void cleanup() {
        // Wait for any background audit writes to finish to avoid lock contention
        auditService.waitForIdle();

        // Clear all checkpoints between tests
        QuarkusTransaction.requiringNew().run(() -> {
            em.createQuery("DELETE FROM AgentCheckpoint").executeUpdate();
        });
        heartbeatTracker.clearAll();
    }

    /**
     * Test 1: Zombie Recovery.
     * Create an IN_PROGRESS checkpoint manually, then invoke the startup
     * method and verify it gets marked FAILED.
     */
    @Test
    void testZombieRecoveryMarksInProgressAsFailed() {
        // Arrange: create an IN_PROGRESS checkpoint (simulates a crash)
        WorkspaceScopeHolder.run("zombie-session-1", () -> {
            var steps = List.of(ReasoningStep.pending("thinking", "action", Map.of()));
            var cp = agentStore.save("zombie-session-1", "coordinator", steps);
            cp.setUpdatedAt(java.time.LocalDateTime.now().minusMinutes(20));
            agentStore.save(cp);
        });

        // Verify it's IN_PROGRESS
        WorkspaceScopeHolder.run("zombie-session-1", () -> {
            var loaded = QuarkusTransaction.requiringNew().call(() -> agentStore.load("zombie-session-1", "coordinator"));
            assertTrue(loaded.isPresent(), "Checkpoint should exist");
            assertEquals(CheckpointStatus.IN_PROGRESS, loaded.get().getStatus());
        });

        // Act: simulate startup zombie recovery
        sessionReaper.onStartup(null);

        // Assert: checkpoint should now be FAILED
        WorkspaceScopeHolder.run("zombie-session-1", () -> {
            var afterRecovery = QuarkusTransaction.requiringNew().call(() -> agentStore.load("zombie-session-1", "coordinator"));
            assertTrue(afterRecovery.isPresent(), "Checkpoint should still exist");
            assertEquals(CheckpointStatus.FAILED, afterRecovery.get().getStatus());
            assertNotNull(afterRecovery.get().getFailedAt(), "failedAt should be set");
            assertTrue(afterRecovery.get().getFailReason().contains("JVM crash recovery"),
                    "Fail reason should mention crash recovery");
        });
    }

    /**
     * Test 2: Session termination removes heartbeat and marks checkpoint FAILED.
     * Verifies terminateSession() cleans up both heartbeat tracking and DB state.
     * The GC sweep path that calls terminateSession() is covered in WorkspaceManagerGcSweepTest.
     */
    @Test
    void testTerminateSession_removesHeartbeatAndMarksCheckpointFailed() {
        String sessionId = "gc-expired-session";

        // Arrange: create a checkpoint for this session
        WorkspaceScopeHolder.run(sessionId, () -> {
            var steps = List.of(ReasoningStep.pending("step1", "action1", Map.of()));
            agentStore.save(sessionId, "debugger", steps);
        });

        // Record heartbeat
        heartbeatTracker.recordHeartbeat(sessionId);
        assertEquals(1, heartbeatTracker.activeSessionCount());

        // Act: call terminateSession directly to verify termination behavior
        workspaceManager.terminateSession(sessionId);

        // Assert: session removed from heartbeats
        assertEquals(0, heartbeatTracker.activeSessionCount());

        // Assert: checkpoint should be marked FAILED
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = QuarkusTransaction.requiringNew().call(() -> agentStore.load(sessionId, "debugger"));
            assertTrue(cp.isPresent());
            assertEquals(CheckpointStatus.FAILED, cp.get().getStatus());
            assertTrue(cp.get().getFailReason().contains("WorkspaceManager"));
        });
    }

    /**
     * Test 3: Session termination marks checkpoints FAILED.
     * Multiple checkpoints for the same session should all be marked FAILED.
     */
    @Test
    void testTerminationMarksAllCheckpointsFailed() {
        String sessionId = "terminate-session";

        // Arrange: create checkpoints for multiple agent roles
        WorkspaceScopeHolder.run(sessionId, () -> {
            agentStore.save(sessionId, "coordinator",
                    List.of(ReasoningStep.pending("t1", "a1", Map.of())));
            agentStore.save(sessionId, "debugger",
                    List.of(ReasoningStep.pending("t2", "a2", Map.of())));
        });

        // Act
        workspaceManager.terminateSession(sessionId);

        // Assert: both checkpoints should be FAILED
        WorkspaceScopeHolder.run(sessionId, () -> {
            var coordinatorCp = QuarkusTransaction.requiringNew().call(() -> agentStore.load(sessionId, "coordinator"));
            var debuggerCp = QuarkusTransaction.requiringNew().call(() -> agentStore.load(sessionId, "debugger"));
            assertTrue(coordinatorCp.isPresent());
            assertTrue(debuggerCp.isPresent());
            assertEquals(CheckpointStatus.FAILED, coordinatorCp.get().getStatus());
            assertEquals(CheckpointStatus.FAILED, debuggerCp.get().getStatus());
        });
    }

    /**
     * Test 4: Already-COMPLETED checkpoints are not re-marked on termination.
     */
    @Test
    void testTerminationSkipsCompletedCheckpoints() {
        String sessionId = "completed-session";

        // Arrange: create and mark COMPLETED
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.save(sessionId, "researcher",
                    List.of(ReasoningStep.of("done", "FINISH", Map.of("answer", "42"), "Task completed.", 0, 0)));
            agentStore.markCompleted(cp.getId());
        });

        // Act
        workspaceManager.terminateSession(sessionId);

        // Assert: COMPLETED checkpoint should remain COMPLETED (not overwritten to
        // FAILED)
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = QuarkusTransaction.requiringNew().call(() -> agentStore.load(sessionId, "researcher"));
            assertTrue(cp.isPresent());
            assertEquals(CheckpointStatus.COMPLETED, cp.get().getStatus(),
                    "Terminated sessions should skip COMPLETED checkpoints");
        });
    }
    /**
     * Test 5: Zombie Recovery for AWAITING_APPROVAL.
     * AWAITING_APPROVAL checkpoints MUST survive JVM restarts (new invariant).
     */
    @Test
    void testZombieRecoverySurvivesAwaitingApproval() {
        String sessionId = "zombie-approval-1";
        String agentRole = "coordinator";
        WorkspaceScopeHolder.run(sessionId, () -> {
            var steps = List.of(ReasoningStep.pending("thought", "action", Map.of()));
            var _ = agentStore.save(sessionId, agentRole, steps);

            // Load via store — CDI proxy ensures em binds correctly in REQUIRES_NEW context
            AgentCheckpoint detachedCp = QuarkusTransaction.requiringNew()
                .call(() -> agentStore.load(sessionId, agentRole).orElseThrow());

            // Mutate off-transaction to simulate pre-restart state
            detachedCp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            detachedCp.setPendingToolName("github");

            // Persist via store
            agentStore.save(detachedCp);
            auditService.waitForIdle();
        });

        // Act
        sessionReaper.onStartup(null);

        // Assert: SHOULD SURVIVE
        WorkspaceScopeHolder.run(sessionId, () -> {
            var loaded = QuarkusTransaction.requiringNew().call(() -> agentStore.load(sessionId, "coordinator"));
            assertTrue(loaded.isPresent());
            assertEquals(CheckpointStatus.AWAITING_APPROVAL, loaded.get().getStatus(), 
                "AWAITING_APPROVAL should survive zombie recovery (new invariant)");
        });
    }
}
