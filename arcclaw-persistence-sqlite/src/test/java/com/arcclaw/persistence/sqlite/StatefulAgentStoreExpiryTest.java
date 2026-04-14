package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStoreImpl;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@QuarkusTest
class StatefulAgentStoreExpiryTest {

    @Inject
    StatefulAgentStoreImpl store;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void setup() {
        em.createQuery("DELETE FROM AgentCheckpoint").executeUpdate();
    }

    @Test
    void isCheckpointExpired_trueForOldCheckpoint() {
        String sessionId = "expiry-session-" + UUID.randomUUID();
        
        // Transaction 1: Create record in AWAITING_APPROVAL
        Long cpId = QuarkusTransaction.requiringNew().call(() -> {
            AgentCheckpoint cp = new AgentCheckpoint();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            cp.setAutonomyLevel(AutonomyLevel.FULL);
            em.persist(cp);
            em.flush();
            return cp.getId();
        });
        assertNotNull(cpId, "Checkpoint ID must be populated after persist");

        // Transaction 2: Backdate to 25 hours ago
        QuarkusTransaction.requiringNew().run(() -> {
            int rows = em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
              .setParameter("time", Instant.now().minus(25, ChronoUnit.HOURS))
              .setParameter("id", cpId)
              .executeUpdate();
            if (rows != 1) throw new RuntimeException("Backdate updated " + rows + " rows, expected 1");
        });

        assertTrue(store.isCheckpointExpired(sessionId, 24), "Checkpoint from 25h ago should be expired for 24h window");
    }

    @Test
    void isCheckpointExpired_falseForFreshCheckpoint() {
        String sessionId = "fresh-session-" + UUID.randomUUID();
        
        // Transaction 1: Create record in AWAITING_APPROVAL
        Long cpId = QuarkusTransaction.requiringNew().call(() -> {
            AgentCheckpoint cp = new AgentCheckpoint();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            cp.setAutonomyLevel(AutonomyLevel.FULL);
            em.persist(cp);
            em.flush();
            return cp.getId();
        });
        assertNotNull(cpId, "Checkpoint ID must be populated after persist");

        // Transaction 2: Backdate to only 1 hour ago
        QuarkusTransaction.requiringNew().run(() -> {
            int rows = em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
              .setParameter("time", Instant.now().minus(1, ChronoUnit.HOURS))
              .setParameter("id", cpId)
              .executeUpdate();
            if (rows != 1) throw new RuntimeException("Backdate updated " + rows + " rows, expected 1");
        });

        // The adapter should now see 1 record for this session with AWAITING_APPROVAL status
        // and a timestamp that is NOT before the (now - 24h) cutoff.
        assertFalse(store.isCheckpointExpired(sessionId, 24), "Checkpoint updated 1h ago should NOT be expired for 24h window");
    }
}
