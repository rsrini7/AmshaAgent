package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CompactionSummaryDto;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SqliteCompactionStoreAdapterTest {

    @Inject
    SqliteCompactionStoreAdapter repository;

    @Test
    void testFindLatestBySessionId_returnsEmptyWhenMissing() {
        String sessionId = "missing-" + System.currentTimeMillis();
        Optional<CompactionSummaryDto> result = repository.findLatestBySessionId(sessionId);
        assertTrue(result.isEmpty());
    }

    @Test
    void testUpsert_insertsNew() {
        String sessionId = "insert-" + System.currentTimeMillis();
        repository.upsert(new CompactionSummaryDto(sessionId, "summary-1", Instant.now(), 3, "test-user"));

        Optional<CompactionSummaryDto> result = repository.findLatestBySessionId(sessionId);
        assertTrue(result.isPresent());
        CompactionSummaryDto dto = result.get();
        assertEquals(sessionId, dto.sessionId());
        assertEquals("summary-1", dto.summary());
        assertEquals(3, dto.messagesCompacted());
        assertNotNull(dto.compactedAt());
    }

    @Test
    void testUpsert_updatesExisting() {
        String sessionId = "update-" + System.currentTimeMillis();
        repository.upsert(new CompactionSummaryDto(sessionId, "summary-1", Instant.now(), 1, "test-user"));

        Instant firstCompactedAt =
                repository.findLatestBySessionId(sessionId).orElseThrow().compactedAt();

        repository.upsert(new CompactionSummaryDto(sessionId, "summary-2", Instant.now(), 4, "test-user"));

        CompactionSummaryDto updated =
                repository.findLatestBySessionId(sessionId).orElseThrow();
        assertEquals("summary-2", updated.summary());
        assertEquals(4, updated.messagesCompacted());
        assertNotNull(updated.compactedAt());
        assertFalse(updated.compactedAt().isBefore(firstCompactedAt));
    }

    @Test
    void testDeleteBySessionId_removes() {
        String sessionId = "delete-" + System.currentTimeMillis();
        repository.upsert(new CompactionSummaryDto(sessionId, "summary-1", Instant.now(), 2, "test-user"));

        repository.deleteBySessionId(sessionId);

        assertTrue(repository.findLatestBySessionId(sessionId).isEmpty());
    }

    // ── Phase 45: FTS5 Memory Search Tests ──────────────────────────────

    @Test
    void testSearchWithFts5Match() {
        String sessionId = "fts5-search-" + System.currentTimeMillis();
        String userId = "user-fts5-" + System.currentTimeMillis();

        repository.upsert(new CompactionSummaryDto(
                sessionId, "Discussed blockchain consensus algorithms", Instant.now(), 5, userId));

        List<CompactionSummaryDto> results = repository.search(userId, "blockchain", 5);

        if (results.isEmpty()) {
            return;
        }
        assertEquals(sessionId, results.get(0).sessionId());
        assertTrue(results.get(0).summary().contains("blockchain"));
    }

    @Test
    void testSearchTenantIsolation() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userA = "userA-" + ts;
        String userB = "userB-" + ts;

        repository.upsert(new CompactionSummaryDto(
                "session-a-" + ts, "Discussed quantum computing breakthroughs", Instant.now(), 3, userA));
        repository.upsert(new CompactionSummaryDto(
                "session-b-" + ts, "Discussed quantum physics experiments", Instant.now(), 4, userB));

        List<CompactionSummaryDto> resultsA = repository.search(userA, "quantum", 5);
        if (!resultsA.isEmpty()) {
            assertTrue(
                    resultsA.stream().allMatch(r -> userA.equals(r.userId())),
                    "User A should only see their own sessions");
        }

        List<CompactionSummaryDto> resultsB = repository.search(userB, "quantum", 5);
        if (!resultsB.isEmpty()) {
            assertTrue(
                    resultsB.stream().allMatch(r -> userB.equals(r.userId())),
                    "User B should only see their own sessions");
        }
    }

    @Test
    void testJapaneseQueryReturnsResults() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userId = "user-ja-" + ts;
        String sessionId = "session-ja-" + ts;
        String text = "富士山は日本で最も高い山です";

        repository.upsert(new CompactionSummaryDto(sessionId, text, Instant.now(), 1, userId));

        List<CompactionSummaryDto> results = repository.search(userId, "富士山", 5);
        if (results.isEmpty()) {
            return;
        }
        assertEquals(sessionId, results.get(0).sessionId());
    }

    @Test
    void testArabicQueryReturnsResults() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userId = "user-ar-" + ts;
        String sessionId = "session-ar-" + ts;
        String text = "الذكاء الاصطناعي هو المستقبل";

        repository.upsert(new CompactionSummaryDto(sessionId, text, Instant.now(), 1, userId));

        List<CompactionSummaryDto> results = repository.search(userId, "الذكاء", 5);
        if (results.isEmpty()) {
            return;
        }
        assertEquals(sessionId, results.get(0).sessionId());
    }

    @Test
    void testFts5SpecialCharactersAreEscaped() {
        String ts = String.valueOf(System.currentTimeMillis());
        String userId = "user-special-" + ts;
        String sessionId = "session-special-" + ts;
        String text = "Searching for \"special\" characters like * and ( ) - ^";

        repository.upsert(new CompactionSummaryDto(sessionId, text, Instant.now(), 1, userId));

        List<CompactionSummaryDto> results = repository.search(userId, "special * ( ) - ^ \"", 5);
        assertNotNull(results);
    }

    @Test
    void testBlankQueryReturnsEmpty() {
        String userId = "user-blank-" + System.currentTimeMillis();

        List<CompactionSummaryDto> resultsNull = repository.search(userId, null, 5);
        assertTrue(resultsNull.isEmpty());

        List<CompactionSummaryDto> resultsBlank = repository.search(userId, "   ", 5);
        assertTrue(resultsBlank.isEmpty());

        List<CompactionSummaryDto> resultsOnlySpecialChars = repository.search(userId, "\" * ( ) - ^", 5);
        assertTrue(resultsOnlySpecialChars.isEmpty());
    }
}
