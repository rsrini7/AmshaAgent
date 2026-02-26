package com.arcclaw.core.state;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AgentCheckpointVersionTest {

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void testAgentCheckpoint_versionStartsAtZero() {
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setSessionId("test-session-v0");
        checkpoint.setAgentRole("coordinator");
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        checkpoint.setCreatedAt(LocalDateTime.now());
        checkpoint.setUpdatedAt(LocalDateTime.now());

        em.persist(checkpoint);
        em.flush();

        assertNotNull(checkpoint.getId());
        assertEquals(0, checkpoint.getVersion(), "Hibernate should auto-set version to 0 on first persist");
    }

    @Test
    @Transactional
    void testAgentCheckpoint_versionIncrementsOnUpdate() {
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setSessionId("test-session-v1");
        checkpoint.setAgentRole("coordinator");
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);

        em.persist(checkpoint);
        em.flush();
        int initialVersion = checkpoint.getVersion();

        checkpoint.setStatus(CheckpointStatus.COMPLETED);
        em.merge(checkpoint);
        em.flush();

        assertEquals(initialVersion + 1, checkpoint.getVersion(), "Version should increment on update");
    }

    @Test
    void testAgentCheckpoint_optimisticLockExceptionOnStaleUpdate() {
        String sessionId = "test-session-ole";
        
        // 1. Initial persist
        Long id = persistInNewTransaction(sessionId);

        // 2. Load two detached copies
        AgentCheckpoint copy1 = em.find(AgentCheckpoint.class, id);
        AgentCheckpoint copy2 = em.find(AgentCheckpoint.class, id);
        assertEquals(0, copy1.getVersion());
        assertEquals(0, copy2.getVersion());

        // 3. Update copy1 inside a transaction
        updateInNewTransaction(id, CheckpointStatus.COMPLETED);

        // 4. Attempt to update copy2 (which still has version 0)
        copy2.setStatus(CheckpointStatus.FAILED);
        
        // Use a new transaction for the stale update - expect RollbackException wrapping OptimisticLockException
        assertThrows(Exception.class, () -> {
            tryMergeInNewTransaction(copy2);
        });
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Long persistInNewTransaction(String sessionId) {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId(sessionId);
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.IN_PROGRESS);
        em.persist(cp);
        return cp.getId();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void updateInNewTransaction(Long id, CheckpointStatus newStatus) {
        AgentCheckpoint cp = em.find(AgentCheckpoint.class, id);
        cp.setStatus(newStatus);
        em.merge(cp);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void tryMergeInNewTransaction(AgentCheckpoint cp) {
        em.merge(cp);
    }
}
