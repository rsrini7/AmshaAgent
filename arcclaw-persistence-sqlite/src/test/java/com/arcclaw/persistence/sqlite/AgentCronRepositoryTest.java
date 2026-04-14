package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link AgentCronRepository} using real in-memory SQLite.
 */
@QuarkusTest
class AgentCronRepositoryTest {

    @Inject
    AgentCronRepository repository;

    @Inject
    EntityManager em;
    
    @BeforeEach
    void cleanup() {
        // cleanup omitted. UUID isolation is sufficient and avoids cross-test data wipes in parallel runs.
    }

    private AgentCronEntity createEntity(String userId, CronStatus status, Instant nextFireTime) {
        AgentCronEntity e = new AgentCronEntity();
        e.id = UUID.randomUUID().toString();
        e.sessionId = "session-" + e.id;
        e.userId = userId;
        e.chatId = "chat-1";
        e.cronExpression = "0 9 * * 1";
        e.timezone = "UTC";
        e.runOnce = false;
        e.goal = "CRON: Test goal";
        e.nextFireTime = nextFireTime;
        e.status = status;
        e.createdAt = Instant.now();
        return e;
    }

    @Test
    void testFindDueCronsReturnsDueRows() {
        AgentCronEntity e = createEntity("user-due-1", CronStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertTrue(due.stream().anyMatch(d -> d.id.equals(e.id)));
    }

    @Test
    void testFindDueCronsExcludesFutureRows() {
        AgentCronEntity e = createEntity("user-due-2", CronStatus.ACTIVE,
                Instant.now().plus(1, ChronoUnit.HOURS));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id.equals(e.id)));
    }

    @Test
    void testDeleteAllByUserId() {
        AgentCronEntity e1 = createEntity("user-del-1", CronStatus.ACTIVE, Instant.now());
        AgentCronEntity e2 = createEntity("user-del-2", CronStatus.ACTIVE, Instant.now());
        repository.persist(e1);
        repository.persist(e2);

        repository.deleteAllByUserId("user-del-1");

        assertNull(repository.findById(e1.id));
        assertNotNull(repository.findById(e2.id), "Other user's jobs must remain intact");
    }
}
