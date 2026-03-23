package com.arcclaw.core.state;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
        repository.upsertSummary(sessionId, "test-user", "summary-1", 3);
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
        repository.upsertSummary(sessionId, "test-user", "summary-1", 1);
        em.flush();
        em.clear();

        Instant firstCompactedAt = repository.findLatestBySessionId(sessionId).orElseThrow().compactedAt;

        repository.upsertSummary(sessionId, "test-user", "summary-2", 4);
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
        repository.upsertSummary(sessionId, "test-user", "summary-1", 2);
        em.flush();
        em.clear();

        repository.deleteBySessionId(sessionId);
        em.flush();
        em.clear();

        assertTrue(repository.findLatestBySessionId(sessionId).isEmpty());
    }

    // ── Phase 45: FTS5 Memory Search Tests ──────────────────────────────

    @Test
    @Transactional
    void testSearchMemoryWithFts5Match() {
        String sessionId = "fts5-search-" + System.currentTimeMillis();
        String userId = "user-fts5-" + System.currentTimeMillis();

        repository.upsertSummary(sessionId, userId, "Discussed blockchain consensus algorithms", 5);
        
        em.flush();
        em.clear();

        List<CompactionSummaryEntity> results = repository.searchMemory(userId, "blockchain", 5);

        assertFalse(results.isEmpty(), "FTS5 search should return matching results");
        assertEquals(sessionId, results.get(0).sessionId);
        assertTrue(results.get(0).summary.contains("blockchain"));
    }

    @Test
    @Transactional
    void testSearchMemoryTenantIsolation() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userA = "userA-" + ts;
        String userB = "userB-" + ts;

        // Persist session for User A
        repository.upsertSummary("session-a-" + ts, userA, "Discussed quantum computing breakthroughs", 3);

        // Persist session for User B
        repository.upsertSummary("session-b-" + ts, userB, "Discussed quantum physics experiments", 4);

        em.flush();
        em.clear();

        // User A should only see their own results
        List<CompactionSummaryEntity> resultsA = repository.searchMemory(userA, "quantum", 5);
        assertEquals(1, resultsA.size(), "User A should only see their own sessions");
        assertEquals(userA, resultsA.get(0).userId);

        // User B should only see their own results
        List<CompactionSummaryEntity> resultsB = repository.searchMemory(userB, "quantum", 5);
        assertEquals(1, resultsB.size(), "User B should only see their own sessions");
        assertEquals(userB, resultsB.get(0).userId);
    }

    // ── Phase 64-A: Component A3 — Non-ASCII FTS5 Search Tests ──────────

    @Test
    @Transactional
    void testJapaneseQueryReturnsResults() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userId = "user-ja-" + ts;
        String sessionId = "session-ja-" + ts;
        String text = "富士山は日本で最も高い山です"; // Mt Fuji is Japan's highest mountain

        repository.upsertSummary(sessionId, userId, text, 1);
        em.flush();
        em.clear();

        List<CompactionSummaryEntity> results = repository.searchMemory(userId, "富士山", 5);
        assertFalse(results.isEmpty(), "Japanese query should return results");
        assertEquals(sessionId, results.get(0).sessionId);
    }

    @Test
    @Transactional
    void testArabicQueryReturnsResults() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userId = "user-ar-" + ts;
        String sessionId = "session-ar-" + ts;
        String text = "الذكاء الاصطناعي هو المستقبل"; // AI is the future

        repository.upsertSummary(sessionId, userId, text, 1);
        em.flush();
        em.clear();

        List<CompactionSummaryEntity> results = repository.searchMemory(userId, "الذكاء", 5);
        assertFalse(results.isEmpty(), "Arabic query should return results");
        assertEquals(sessionId, results.get(0).sessionId);
    }

    @Test
    @Transactional
    void testFts5SpecialCharactersAreEscaped() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userId = "user-special-" + ts;
        String sessionId = "session-special-" + ts;
        String text = "Searching for \"special\" characters like * and ( ) - ^";

        repository.upsertSummary(sessionId, userId, text, 1);
        em.flush();
        em.clear();

        // Query with special characters that would break FTS5 if not escaped
        List<CompactionSummaryEntity> results = repository.searchMemory(userId, "special * ( ) - ^ \"", 5);
        // It should not throw SQLiteException and return result
        assertNotNull(results);
    }

    @Test
    @Transactional
    void testBlankQueryReturnsEmpty() {
        String userId = "user-blank-" + System.currentTimeMillis();
        
        List<CompactionSummaryEntity> resultsNull = repository.searchMemory(userId, null, 5);
        assertTrue(resultsNull.isEmpty());

        List<CompactionSummaryEntity> resultsBlank = repository.searchMemory(userId, "   ", 5);
        assertTrue(resultsBlank.isEmpty());
        
        List<CompactionSummaryEntity> resultsOnlySpecialChars = repository.searchMemory(userId, "\" * ( ) - ^", 5);
        assertTrue(resultsOnlySpecialChars.isEmpty());
    }
}
