package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.SessionReaper;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            var cp = agentStore.save(sessionId, "coordinator", List.of(), "test goal");
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
            agentStore.save(sessionId, "debugger", List.of(), "test goal");
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
            agentStore.save(sessionId, "coordinator", List.of(), "test goal");
            agentStore.save(sessionId, "debugger", List.of(), "test goal");
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
        final Long[] checkpointId = new Long[1];
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.save(sessionId, "researcher", List.of(), "test goal");
            checkpointId[0] = cp.getId();
            agentStore.markCompleted(cp.getId());
        });

        workspaceManager.terminateSession(sessionId);

        // Use EM directly because load() filters out archived checkpoints
        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = em.find(AgentCheckpointEntity.class, checkpointId[0]);
            // §7.31: Terminal COMPLETED checkpoints are immediately archived
            assertEquals(CheckpointStatus.ARCHIVED, cp.getStatus());
        });
    }

    @Test
    void testZombieRecoverySurvivesAwaitingApproval() {
        String sessionId = "survive-appr-" + UUID.randomUUID();
        WorkspaceScopeHolder.run(sessionId, () -> {
            var cp = agentStore.save(sessionId, "coordinator", List.of(), "test goal");
            QuarkusTransaction.requiringNew().run(() -> {
                AgentCheckpoint entity = em.find(AgentCheckpointEntity.class, cp.getId());
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
