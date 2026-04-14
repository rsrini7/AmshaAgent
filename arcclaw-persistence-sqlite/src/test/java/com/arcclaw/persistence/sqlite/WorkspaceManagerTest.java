package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.SessionReaper;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

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
        auditService.waitForIdle();
        heartbeatTracker.clearAll();
    }

    @Test
    void testZombieRecoveryMarksInProgressAsFailed() {
        String sessionId = "zombie-" + UUID.randomUUID();
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.save(sessionId, "coordinator", List.of());
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :backdate WHERE c.id = :id")
                  .setParameter("backdate", Instant.now().minus(20, ChronoUnit.MINUTES))
                  .setParameter("id", cp.getId())
                  .executeUpdate();
            });
        });

        sessionReaper.onStartup(null);

        WorkspaceScopeHolder.run(sessionId, () -> {
            var afterRecovery = agentStore.load(sessionId, "coordinator");
            assertTrue(afterRecovery.isPresent());
            assertEquals(CheckpointStatus.FAILED, afterRecovery.get().getStatus());
        });
    }

    @Test
    void testTerminateSession_removesHeartbeatAndMarksCheckpointFailed() {
        String sessionId = "terminate-" + UUID.randomUUID();
        WorkspaceScopeHolder.run(sessionId, () -> {
            agentStore.save(sessionId, "debugger", List.of());
        });
        heartbeatTracker.recordHeartbeat(sessionId);

        workspaceManager.terminateSession(sessionId);

        assertEquals(0, heartbeatTracker.activeSessionCount());
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.load(sessionId, "debugger");
            assertTrue(cp.isPresent());
            assertEquals(CheckpointStatus.FAILED, cp.get().getStatus());
        });
    }

    @Test
    void testTerminationMarksAllCheckpointsFailed() {
        String sessionId = "term-all-" + UUID.randomUUID();
        WorkspaceScopeHolder.run(sessionId, () -> {
            agentStore.save(sessionId, "coordinator", List.of());
            agentStore.save(sessionId, "debugger", List.of());
        });

        workspaceManager.terminateSession(sessionId);

        WorkspaceScopeHolder.run(sessionId, () -> {
            assertTrue(agentStore.load(sessionId, "coordinator").isPresent());
            assertEquals(CheckpointStatus.FAILED, agentStore.load(sessionId, "coordinator").get().getStatus());
        });
    }

    @Test
    void testTerminationSkipsCompletedCheckpoints() {
        String sessionId = "skip-comp-" + UUID.randomUUID();
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.save(sessionId, "researcher", List.of());
            agentStore.markCompleted(cp.getId());
        });

        workspaceManager.terminateSession(sessionId);

        WorkspaceScopeHolder.run(sessionId, () -> {
            assertEquals(CheckpointStatus.COMPLETED, agentStore.load(sessionId, "researcher").get().getStatus());
        });
    }

    @Test
    void testZombieRecoverySurvivesAwaitingApproval() {
        String sessionId = "survive-appr-" + UUID.randomUUID();
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.save(sessionId, "coordinator", List.of());
            QuarkusTransaction.requiringNew().run(() -> {
                AgentCheckpoint entity = em.find(AgentCheckpoint.class, cp.getId());
                entity.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            });
        });

        sessionReaper.onStartup(null);

        WorkspaceScopeHolder.run(sessionId, () -> {
            assertEquals(CheckpointStatus.AWAITING_APPROVAL, agentStore.load(sessionId, "coordinator").get().getStatus());
        });
    }

    @Test
    void testResetSessionState_pathTraversalRejected() {
        assertThrows(SecurityException.class, () -> workspaceManager.resetSessionState("../../../etc/passwd"));
    }
}
