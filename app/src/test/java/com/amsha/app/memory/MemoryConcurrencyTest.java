package com.amsha.app.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.app.observability.MemoryMetricsObserver;
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Test: Verifies thread safety of memory operations.
 * <p>
 * Tests concurrent access to the MemoryMetricsObserver (shared mutable state)
 * and concurrent NoOp store calls through the service layer.
 */
class MemoryConcurrencyTest {

    private MeterRegistry registry;
    private MemoryMetricsObserver observer;
    private RecallMemoryService recallService;
    private ArchivalMemoryService archivalService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observer = new MemoryMetricsObserver();
        setField(observer, "registry", registry);
        observer.init();

        recallService = new RecallMemoryService();
        archivalService = new ArchivalMemoryService();
        setField(recallService, "recallStore", new NoOpRecallStore());
        setField(archivalService, "store", new NoOpArchivalStore());
    }

    @Test
    void concurrentRecallSearchMetricsAreAccurate() throws Exception {
        int threadCount = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < opsPerThread; j++) {
                    observer.recordRecallSearch(1_000_000, 5);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        Counter counter = registry.find("memory.recall.search").counter();
        assertNotNull(counter);
        assertEquals(threadCount * opsPerThread, (long) counter.count());

        Timer timer = registry.find("memory.recall.search.latency").timer();
        assertNotNull(timer);
        assertEquals(threadCount * opsPerThread, timer.count());

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentArchivalSearchMetricsAreAccurate() throws Exception {
        int threadCount = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < opsPerThread; j++) {
                    observer.recordArchivalSearch(2_000_000, 3, 0.8);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        Counter counter = registry.find("memory.archival.search").counter();
        assertNotNull(counter);
        assertEquals(threadCount * opsPerThread, (long) counter.count());

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentMixedOperationsAreThreadSafe() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger(0);

        // Threads doing recall searches
        for (int i = 0; i < 4; i++) {
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < 200; j++) {
                        observer.recordRecallSearch(1_000_000, j % 10);
                        recallService.search(new RecallSearchQuery("user-1", "q", "s", 10, false, Set.of()));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }

        // Threads doing archival searches
        for (int i = 0; i < 4; i++) {
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < 200; j++) {
                        observer.recordArchivalSearch(2_000_000, j % 5, 0.7);
                        archivalService.search(new ArchivalSearchQuery("user-1", "q", 10, Set.of(), false, true, null));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        assertEquals(0, errors.get(), "Concurrent operations should not throw");

        Counter recallCounter = registry.find("memory.recall.search").counter();
        Counter archivalCounter = registry.find("memory.archival.search").counter();
        assertNotNull(recallCounter);
        assertNotNull(archivalCounter);
        assertEquals(800, (long) recallCounter.count());
        assertEquals(800, (long) archivalCounter.count());

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentPurgeAndEvictMetricsAreThreadSafe() throws Exception {
        int threadCount = 4;
        int opsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount / 2; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < opsPerThread; j++) {
                    observer.recordRecallPurge();
                }
            }));
        }

        for (int i = 0; i < threadCount / 2; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < opsPerThread; j++) {
                    observer.recordArchivalEvict();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        Counter purgeCounter = registry.find("memory.recall.purge").counter();
        Counter evictCounter = registry.find("memory.archival.evict").counter();
        assertNotNull(purgeCounter);
        assertNotNull(evictCounter);
        assertEquals((threadCount / 2) * opsPerThread, (long) purgeCounter.count());
        assertEquals((threadCount / 2) * opsPerThread, (long) evictCounter.count());

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
