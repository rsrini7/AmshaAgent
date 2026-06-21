package com.arcclaw.core.memory.tiered;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryWriteGovernanceTest {

    MemoryWriteGovernance governance;

    @BeforeEach
    void setup() {
        governance = new MemoryWriteGovernance();
        governance.coreWritesPerMinute = 3;
        governance.archivalWritesPerHour = 2;
    }

    @Test
    void testCoreWrite_withinLimit_succeeds() {
        assertDoesNotThrow(() -> governance.checkCoreWrite());
        assertDoesNotThrow(() -> governance.checkCoreWrite());
        assertDoesNotThrow(() -> governance.checkCoreWrite());
    }

    @Test
    void testCoreWrite_exceedsLimit_throws() {
        governance.checkCoreWrite();
        governance.checkCoreWrite();
        governance.checkCoreWrite();

        var ex =
                assertThrows(MemoryWriteGovernance.RateLimitExceededException.class, () -> governance.checkCoreWrite());
        assertEquals("core_memory", ex.toolType());
        assertEquals(3, ex.limit());
        assertEquals("minute", ex.period());
    }

    @Test
    void testArchivalWrite_withinLimit_succeeds() {
        assertDoesNotThrow(() -> governance.checkArchivalWrite());
        assertDoesNotThrow(() -> governance.checkArchivalWrite());
    }

    @Test
    void testArchivalWrite_exceedsLimit_throws() {
        governance.checkArchivalWrite();
        governance.checkArchivalWrite();

        var ex = assertThrows(
                MemoryWriteGovernance.RateLimitExceededException.class, () -> governance.checkArchivalWrite());
        assertEquals("archival_memory", ex.toolType());
        assertEquals(2, ex.limit());
        assertEquals("hour", ex.period());
    }

    @Test
    void testCoreAndArchival_independentBuckets() {
        // Core limit is 3, archival limit is 2
        // Exhausting core should not affect archival
        governance.checkCoreWrite();
        governance.checkCoreWrite();
        governance.checkCoreWrite();
        assertThrows(MemoryWriteGovernance.RateLimitExceededException.class, () -> governance.checkCoreWrite());

        // Archival still has budget
        assertDoesNotThrow(() -> governance.checkArchivalWrite());
    }

    /**
     * Rate limits are node-local and in-memory by design (documented in Javadoc).
     * A new MemoryWriteGovernance instance — as would happen on process restart —
     * starts with a fresh bucket, confirming limits reset on restart.
     * This test documents that behavior explicitly, not as a bug.
     */
    @Test
    void testRateLimitResetsOnNewInstance_intentionalNodeLocalBehavior() {
        // Exhaust the core budget on instance 1
        governance.checkCoreWrite();
        governance.checkCoreWrite();
        governance.checkCoreWrite();
        assertThrows(MemoryWriteGovernance.RateLimitExceededException.class, () -> governance.checkCoreWrite());

        // New instance (simulates process restart) — fresh bucket, budget restored
        MemoryWriteGovernance newInstance = new MemoryWriteGovernance();
        newInstance.coreWritesPerMinute = 3;
        newInstance.archivalWritesPerHour = 2;

        assertDoesNotThrow(
                newInstance::checkCoreWrite,
                "Rate limits reset on new instance — this is intentional node-local behavior, not a bug");
    }

    /**
     * Verifies thread safety: concurrent calls from multiple virtual threads
     * should not over-consume tokens or corrupt bucket state.
     */
    @Test
    void testConcurrentCalls_threadSafe() throws Exception {
        MemoryWriteGovernance concurrentGov = new MemoryWriteGovernance();
        concurrentGov.coreWritesPerMinute = 50;
        concurrentGov.archivalWritesPerHour = 50;

        int threadCount = 20;
        int callsPerThread = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicBoolean exceptionSeen = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        try {
                            concurrentGov.checkCoreWrite();
                            successCount.incrementAndGet();
                        } catch (MemoryWriteGovernance.RateLimitExceededException e) {
                            // Expected once budget exhausted
                        }
                    }
                } catch (Exception e) {
                    exceptionSeen.set(true);
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(!exceptionSeen.get(), "No unexpected exceptions during concurrent access");
        assertTrue(successCount.get() > 0, "At least some calls succeeded");
        assertTrue(successCount.get() <= 50, "Success count should not exceed bucket max");
    }
}
