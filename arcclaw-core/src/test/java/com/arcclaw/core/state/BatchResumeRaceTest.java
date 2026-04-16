package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BatchResumeRaceTest {

    @Test
    void markBatchResuming_isExclusiveAndClears() {
        StatefulAgentStoreImpl store = new StatefulAgentStoreImpl();
        store.resumeGuard = new ResumeCoordinationGuard();
        String sessionId = "sess-123";

        // First call success
        assertTrue(store.markBatchResuming(sessionId), "First call should succeed");
        
        // Parallel call fail
        assertFalse(store.markBatchResuming(sessionId), "Second concurrent call should fail");

        // Clear works
        store.clearBatchResuming(sessionId);
        assertTrue(store.markBatchResuming(sessionId), "Call after clearing should succeed again");
    }

    @Test
    void markBatchResuming_isConcurrentThreadSafe() throws InterruptedException {
        StatefulAgentStoreImpl store = new StatefulAgentStoreImpl();
        store.resumeGuard = new ResumeCoordinationGuard();
        String sessionId = "race-456";
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (store.markBatchResuming(sessionId)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Assertions.assertEquals(1, successCount.get(), "Exactly one thread should successfully mark as resuming");
    }
}
