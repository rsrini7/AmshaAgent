package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = new AgentCheckpoint();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            cp.setAutonomyLevel(AutonomyLevel.FULL);
            em.persist(cp);
            em.flush();

            em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
              .setParameter("time", Instant.now().minus(25, ChronoUnit.HOURS))
              .setParameter("id", cp.getId())
              .executeUpdate();
        });

        assertTrue(store.isCheckpointExpired(sessionId, 24));
    }

    @Test
    void isCheckpointExpired_falseForFreshCheckpoint() {
        String sessionId = "fresh-session-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = new AgentCheckpoint();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            cp.setAutonomyLevel(AutonomyLevel.FULL);
            em.persist(cp);
            em.flush();

            em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
              .setParameter("time", Instant.now().minus(1, ChronoUnit.HOURS))
              .setParameter("id", cp.getId())
              .executeUpdate();
        });

        assertFalse(store.isCheckpointExpired(sessionId, 24));
    }
}
