package com.arcclaw.core.state;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CompactionSummaryRepositoryTest {

    @Inject
    CompactionSummaryRepository repository;

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void testFindLatestBySessionId_returnsEmptyWhenMissing() {
        String sessionId = "missing-" + System.currentTimeMillis();
        Optional<CompactionSummaryEntity> result = repository.findLatestBySessionId(sessionId);
        assertTrue(result.isEmpty());
    }

    @Test
    @Transactional
    void testUpsertSummary_insertsNewEntity() {
        String sessionId = "insert-" + System.currentTimeMillis();
        repository.upsertSummary(sessionId, "summary-1", 3);
        em.flush();
        em.clear();

        Optional<CompactionSummaryEntity> result = repository.findLatestBySessionId(sessionId);
        assertTrue(result.isPresent());
        CompactionSummaryEntity entity = result.get();
        assertEquals(sessionId, entity.sessionId);
        assertEquals("summary-1", entity.summary);
        assertEquals(3, entity.messagesCompacted);
        assertNotNull(entity.compactedAt);
    }

    @Test
    @Transactional
    void testUpsertSummary_updatesExistingEntity() {
        String sessionId = "update-" + System.currentTimeMillis();
        repository.upsertSummary(sessionId, "summary-1", 1);
        em.flush();
        em.clear();

        Instant firstCompactedAt = repository.findLatestBySessionId(sessionId).orElseThrow().compactedAt;

        repository.upsertSummary(sessionId, "summary-2", 4);
        em.flush();
        em.clear();

        CompactionSummaryEntity updated = repository.findLatestBySessionId(sessionId).orElseThrow();
        assertEquals("summary-2", updated.summary);
        assertEquals(4, updated.messagesCompacted);
        assertNotNull(updated.compactedAt);
        assertFalse(updated.compactedAt.isBefore(firstCompactedAt));
    }

    @Test
    @Transactional
    void testDeleteBySessionId_removesEntity() {
        String sessionId = "delete-" + System.currentTimeMillis();
        repository.upsertSummary(sessionId, "summary-1", 2);
        em.flush();
        em.clear();

        repository.deleteBySessionId(sessionId);
        em.flush();
        em.clear();

        assertTrue(repository.findLatestBySessionId(sessionId).isEmpty());
    }
}
