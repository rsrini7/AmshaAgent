package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.security.AutonomyLevel;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StatefulAgentStoreExpiryTest {

    @Inject
    StatefulAgentStoreImpl store;

    @Inject
    EntityManager em;

    private static final String SESSION_ID = "test-expiry-session";

    @BeforeEach
    @Transactional
    void setup() {
        em.createQuery("DELETE FROM AgentCheckpoint c WHERE c.sessionId = :sid")
          .setParameter("sid", SESSION_ID)
          .executeUpdate();
    }

    @Test
    @Transactional
    void isCheckpointExpired_trueForOldCheckpoint() {
        // Create an AWAITING_APPROVAL checkpoint updated 25 hours ago
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId(SESSION_ID);
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setAutonomyLevel(AutonomyLevel.FULL);
        em.persist(cp);
        em.flush();
        
        // Force updatedAt to be 25 hours ago bypassing @PreUpdate hook
        em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
          .setParameter("time", LocalDateTime.ofInstant(
                Instant.now().minus(25, ChronoUnit.HOURS), ZoneOffset.UTC))
          .setParameter("id", cp.getId())
          .executeUpdate();
        em.clear();

        boolean expired = store.isCheckpointExpired(SESSION_ID, 24);
        assertTrue(expired, "Checkpoint updated 25 hours ago should be expired for 24h window");
    }

    @Test
    @Transactional
    void isCheckpointExpired_falseForFreshCheckpoint() {
        // Create an AWAITING_APPROVAL checkpoint updated 1 hour ago
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId(SESSION_ID);
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setAutonomyLevel(AutonomyLevel.FULL);
        em.persist(cp);
        em.flush();
        
        // Force updatedAt to be 1 hour ago bypassing @PreUpdate hook
        em.createQuery("UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
          .setParameter("time", LocalDateTime.ofInstant(
                Instant.now().minus(1, ChronoUnit.HOURS), ZoneOffset.UTC))
          .setParameter("id", cp.getId())
          .executeUpdate();
        em.clear();

        boolean expired = store.isCheckpointExpired(SESSION_ID, 24);
        assertFalse(expired, "Checkpoint updated 1 hour ago should NOT be expired for 24h window");
    }
}
