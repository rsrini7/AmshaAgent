package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;

import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.sqlite.entity.AgentCheckpointEntity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentCheckpoint @Version behaviour.
 * <p>
 * Uses single-transaction pattern with flush/clear to avoid cross-transaction
 * visibility issues in SQLite WAL mode. UUID session IDs ensure test isolation
 * without needing destructive cleanup.
 */
@QuarkusTest
class AgentCheckpointVersionTest {

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void testAgentCheckpoint_versionStartsAtZero() {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("v0-" + UUID.randomUUID());
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.IN_PROGRESS);

        em.persist(cp);
        em.flush();

        assertNotNull(cp.getId(), "ID should be generated after flush");
        assertEquals(0, cp.getVersion(), "Initial version should be 0");
    }

    @Test
    @Transactional
    void testAgentCheckpoint_versionIncrementsOnUpdate() {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("v1-" + UUID.randomUUID());
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.IN_PROGRESS);

        em.persist(cp);
        em.flush();
        Long id = cp.getId();
        assertEquals(0, cp.getVersion(), "Initial version should be 0");

        // Modify and flush to trigger @Version increment
        cp.setStatus(CheckpointStatus.COMPLETED);
        em.flush();

        // Clear L1 cache and re-read from DB to verify version
        em.clear();
        AgentCheckpoint reloaded = em.find(AgentCheckpointEntity.class, id);
        assertNotNull(reloaded, "Checkpoint should be readable after flush+clear");
        assertEquals(1, reloaded.getVersion(), "Version should increment to 1 after update");
    }

    @Test
    @Transactional
    void testAgentCheckpoint_optimisticLockExceptionOnStaleUpdate() {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("ole-" + UUID.randomUUID());
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.IN_PROGRESS);

        em.persist(cp);
        em.flush();
        Long id = cp.getId();

        // Simulate a concurrent update by bumping the version directly in the DB.
        // This bypasses JPA, so the L1 cache entity still has version=0.
        em.createNativeQuery("UPDATE agent_checkpoint SET version = version + 1 WHERE checkpoint_id = ?1")
          .setParameter(1, id)
          .executeUpdate();

        // Now the entity in L1 has version=0, but the DB row has version=1.
        // Attempting to flush should trigger an optimistic lock failure.
        cp.setStatus(CheckpointStatus.FAILED);
        assertThrows(OptimisticLockException.class, () -> em.flush(),
            "Flush should throw OptimisticLockException due to version mismatch");
    }
}
