package com.amsha.app.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.memory.tiered.ArchivalMemoryService;
import com.amsha.core.memory.tiered.RecallMemoryService;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test: Verifies end-to-end sessions with tiered memory disabled.
 * <p>
 * Exercises the NoOp store contract: search returns empty, inserts are no-ops,
 * get methods return empty. This validates the actual disabled path through
 * the service layer using inline NoOp port implementations.
 */
class EndToEndDisabledBehaviorTest {

    private RecallMemoryService recallService;
    private ArchivalMemoryService archivalService;

    @BeforeEach
    void setUp() {
        recallService = new RecallMemoryService();
        archivalService = new ArchivalMemoryService();

        setField(recallService, "recallStore", new NoOpRecallStore());
        setField(archivalService, "store", new NoOpArchivalStore());
    }

    @Test
    void recallSearchReturnsEmptyWhenDisabled() {
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test", "session-1", 10, false, Set.of());
        List<RecallMessageDto> results = recallService.search(query);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void recallCrossSessionSearchReturnsEmptyWhenDisabled() {
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test", null, 10, false, Set.of());
        List<RecallMessageDto> results = recallService.search(query);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void recallInsertIsNoOpWhenDisabled() {
        RecallMessageDto message = sampleRecallMessage("msg-1");
        assertDoesNotThrow(() -> recallService.insertOrIgnore(message));
    }

    @Test
    void recallBatchInsertIsNoOpWhenDisabled() {
        List<RecallMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(sampleRecallMessage("msg-" + i));
        }
        assertDoesNotThrow(() -> recallService.insertBatch(messages));
    }

    @Test
    void recallPurgeIsNoOpWhenDisabled() {
        assertDoesNotThrow(() -> recallService.purgeExpired("user-1", 30));
    }

    @Test
    void recallPurgeAllIsNoOpWhenDisabled() {
        assertDoesNotThrow(() -> recallService.purgeExpiredAll(30));
    }

    @Test
    void archivalSearchReturnsEmptyWhenDisabled() {
        ArchivalSearchQuery query = new ArchivalSearchQuery("user-1", "test", 10, Set.of(), false, true, null);
        List<ArchivalChunkDto> results = archivalService.search(query);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void archivalUpsertIsNoOpWhenDisabled() {
        ArchivalChunkDto chunk = sampleArchivalChunk("chunk-1");
        assertDoesNotThrow(() -> archivalService.upsert(chunk));
    }

    @Test
    void archivalGetByIdReturnsEmptyWhenDisabled() {
        Optional<ArchivalChunkDto> result = archivalService.getById("chunk-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void archivalDeleteIsNoOpWhenDisabled() {
        assertDoesNotThrow(() -> archivalService.delete("chunk-1"));
    }

    @Test
    void archivalEvictIsNoOpWhenDisabled() {
        assertDoesNotThrow(() -> archivalService.evictLowConfidence(0.3, 30));
    }

    private RecallMessageDto sampleRecallMessage(String id) {
        return new RecallMessageDto(
                id,
                "session-1",
                "user-1",
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "Hello",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
    }

    private ArchivalChunkDto sampleArchivalChunk(String id) {
        return new ArchivalChunkDto(
                id, "user-1", "Content", "CODE", "session-1", "msg-1", "tag1", 0.8, 0, Instant.now(), Instant.now());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    /** Inline NoOp Recall store — mirrors NoOpRecallMemoryStore contract. */
    private static class NoOpRecallStore implements RecallMemoryStorePort {
        @Override
        public void insertOrIgnore(RecallMessageDto message) {}

        @Override
        public void insertBatch(List<RecallMessageDto> messages) {}

        @Override
        public List<RecallMessageDto> search(RecallSearchQuery query) {
            return List.of();
        }

        @Override
        public Optional<RecallMessageDto> getById(String messageId) {
            return Optional.empty();
        }

        @Override
        public void purgeExpired(String userId, int retentionDays) {}

        @Override
        public void purgeExpiredAll(int retentionDays) {}
    }

    /** Inline NoOp Archival store — mirrors NoOpArchivalMemoryStore contract. */
    private static class NoOpArchivalStore implements ArchivalMemoryStorePort {
        @Override
        public void upsert(ArchivalChunkDto chunk) {}

        @Override
        public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
            return List.of();
        }

        @Override
        public Optional<ArchivalChunkDto> getById(String chunkId) {
            return Optional.empty();
        }

        @Override
        public void incrementAccessCount(String chunkId) {}

        @Override
        public void incrementAccessCounts(List<String> chunkIds) {}

        @Override
        public void delete(String chunkId) {}

        @Override
        public void evictLowConfidence(double threshold, int minAgeDays) {}
    }
}
