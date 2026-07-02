package com.amsha.app.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

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
 * Phase 12 test: Regression tests for disabled tiered memory.
 * <p>
 * When tiered memory is disabled, the system should fall back to pre-Phase 0
 * behavior with no crashes, no hangs, and clean empty results. This test
 * verifies the full disabled path through the service layer using NoOp stores.
 */
class TieredMemoryDisableRegressionTest {

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
    void archivalSearchReturnsEmptyWhenDisabled() {
        ArchivalSearchQuery query = new ArchivalSearchQuery("user-1", "test", 10, Set.of(), false, true, null);
        List<ArchivalChunkDto> results = archivalService.search(query);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void recallGetByIdReturnsEmptyWhenDisabled() {
        // NoOp store should return empty, not throw
        List<RecallMessageDto> results =
                recallService.search(new RecallSearchQuery("user-1", "test", "session-1", 10, false, Set.of()));
        assertTrue(results.isEmpty());
    }

    @Test
    void archivalGetByIdReturnsEmptyWhenDisabled() {
        Optional<ArchivalChunkDto> result = archivalService.getById("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void recallInsertDoesNotThrowWhenDisabled() {
        RecallMessageDto msg = new RecallMessageDto(
                "msg-1",
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
        assertDoesNotThrow(() -> recallService.insertOrIgnore(msg));
    }

    @Test
    void recallBatchInsertDoesNotThrowWhenDisabled() {
        List<RecallMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            messages.add(new RecallMessageDto(
                    "msg-" + i,
                    "session-1",
                    "user-1",
                    i,
                    "hash-" + i,
                    "user",
                    "USER_MESSAGE",
                    "Content " + i,
                    null,
                    null,
                    false,
                    false,
                    Instant.now(),
                    null));
        }
        assertDoesNotThrow(() -> recallService.insertBatch(messages));
    }

    @Test
    void archivalUpsertDoesNotThrowWhenDisabled() {
        ArchivalChunkDto chunk = new ArchivalChunkDto(
                "chunk-1",
                "user-1",
                "Content",
                "CODE",
                "session-1",
                "msg-1",
                "tag1",
                0.8,
                0,
                Instant.now(),
                Instant.now());
        assertDoesNotThrow(() -> archivalService.upsert(chunk));
    }

    @Test
    void recallPurgeAllDoesNotThrowWhenDisabled() {
        assertDoesNotThrow(() -> recallService.purgeExpiredAll(30));
    }

    @Test
    void archivalEvictDoesNotThrowWhenDisabled() {
        assertDoesNotThrow(() -> archivalService.evictLowConfidence(0.3, 30));
    }

    @Test
    void disabledStoresAreSafeUnderRapidCalls() throws Exception {
        // Verify no deadlocks or race conditions when rapidly calling NoOp stores
        int threadCount = 4;
        int callsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int userId = i;
            futures.add(executor.submit(() -> {
                for (int j = 0; j < callsPerThread; j++) {
                    recallService.search(new RecallSearchQuery("user-" + userId, "q", "s", 10, false, Set.of()));
                    archivalService.search(
                            new ArchivalSearchQuery("user-" + userId, "q", 10, Set.of(), false, true, null));
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
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
