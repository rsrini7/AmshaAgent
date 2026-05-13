package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.AgentCheckpointEntity;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStoreImpl;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Tests for checkpoint expiry logic.
 * <p>
 * No @BeforeEach cleanup - each test uses a unique UUID session ID for
 * complete isolation. This avoids the SQLite cross-transaction visibility
 * issues caused by DELETE/TRUNCATE operations.
 */
@QuarkusTest
class StatefulAgentStoreExpiryTest {

    @Inject
    StatefulAgentStoreImpl store;

    @Inject
    EntityManager em;

    /**
     * Helper: creates an AWAITING_APPROVAL checkpoint and backdates its updatedAt,
     * all within a single committed transaction. Using a single transaction avoids
     * the cross-transaction invisibility issue where a second transaction cannot
     * see the first's committed data.
     * <p>
     * The entity is detached before the JPQL UPDATE to prevent Hibernate's
     * dirty-check from overwriting the backdated timestamp at commit time.
     */
    private void createAndBackdate(String sessionId, Instant backdateTo) {
        QuarkusTransaction.requiringNew().run(() -> {
            AgentCheckpoint cp = new AgentCheckpointEntity();
            cp.setSessionId(sessionId);
            cp.setAgentRole("coordinator");
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            cp.setAutonomyLevel(AutonomyLevel.FULL);
            em.persist(cp);
            em.flush();

            Long cpId = cp.getId();
            assertNotNull(cpId, "Checkpoint ID must be generated after flush");

            // Detach the entity so Hibernate won't re-flush the in-memory updatedAt
            // (set by @PrePersist) over the JPQL-backdated value at commit time.
            em.detach(cp);

            int updated = em.createQuery(
                "UPDATE AgentCheckpoint c SET c.updatedAt = :time WHERE c.id = :id")
                .setParameter("time", backdateTo)
                .setParameter("id", cpId)
                .executeUpdate();
            assertEquals(1, updated, "Backdate should update exactly 1 row");
        });
    }

    @Test
    void isCheckpointExpired_trueForOldCheckpoint() {
        String sessionId = "expiry-" + UUID.randomUUID();
        createAndBackdate(sessionId, Instant.now().minus(25, ChronoUnit.HOURS));

        assertTrue(store.isCheckpointExpired(sessionId, 24),
            "Checkpoint from 25h ago should be expired for 24h window");
    }

    @Test
    void isCheckpointExpired_falseForFreshCheckpoint() {
        String sessionId = "fresh-" + UUID.randomUUID();
        createAndBackdate(sessionId, Instant.now().minus(1, ChronoUnit.HOURS));

        assertFalse(store.isCheckpointExpired(sessionId, 24),
            "Checkpoint from 1h ago should NOT be expired for 24h window");
    }
}
