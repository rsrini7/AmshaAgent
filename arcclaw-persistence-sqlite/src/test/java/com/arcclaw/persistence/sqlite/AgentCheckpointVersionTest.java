package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AgentCheckpointVersionTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createQuery("DELETE FROM ReasoningStepEntity").executeUpdate();
        em.createQuery("DELETE FROM AgentCheckpoint").executeUpdate();
    }

    @Test
    @Transactional
    void testAgentCheckpoint_versionStartsAtZero() {
        String sessionId = "v0-" + UUID.randomUUID();
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setSessionId(sessionId);
        checkpoint.setAgentRole("coordinator");
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        checkpoint.setCreatedAt(Instant.now());
        checkpoint.setUpdatedAt(Instant.now());

        em.persist(checkpoint);
        em.flush();

        assertNotNull(checkpoint.getId());
        assertEquals(0, checkpoint.getVersion(), "Initial version should be 0");
    }

    @Test
    void testAgentCheckpoint_versionIncrementsOnUpdate() {
        String sessionId = "v1-" + UUID.randomUUID();
        
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            AgentCheckpoint cp = new AgentCheckpoint();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.IN_PROGRESS);
            em.persist(cp);
            return cp.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
            assertNotNull(cp);
            cp.setStatus(CheckpointStatus.COMPLETED);
        });

        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
            assertEquals(1, cp.getVersion());
        });
    }

    @Test
    void testAgentCheckpoint_optimisticLockExceptionOnStaleUpdate() {
        String sessionId = "ole-" + UUID.randomUUID();

        Long id = QuarkusTransaction.requiringNew().call(() -> {
            AgentCheckpoint cp = new AgentCheckpoint();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.IN_PROGRESS);
            em.persist(cp);
            return cp.getId();
        });

        AgentCheckpoint copy2 = QuarkusTransaction.requiringNew().call(() -> {
            AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
            em.detach(cp);
            return cp;
        });

        // Advance version via another transaction
        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
            cp.setStatus(CheckpointStatus.COMPLETED);
        });

        // Attempt stale update
        copy2.setStatus(CheckpointStatus.FAILED);
        assertThrows(Exception.class, () -> {
            QuarkusTransaction.requiringNew().run(() -> {
                em.merge(copy2);
            });
        });
    }
}
