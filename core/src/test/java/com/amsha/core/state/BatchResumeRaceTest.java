package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.persistence.api.CheckpointStorePort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BatchResumeRaceTest {

    @Test
    void claimForResume_isExclusiveAndClears() {
        StatefulAgentStoreImpl store = new StatefulAgentStoreImpl();
        store.resumeGuard = new ResumeCoordinationGuard();
        store.storeAdapter = mock(CheckpointStorePort.class);
        when(store.storeAdapter.claimForResume(anyString(), anyString())).thenReturn(true);

        String sessionId = "sess-123";
        String role = "coordinator";

        // First call success
        assertTrue(store.claimForResume(sessionId, role), "First call should succeed");

        // Parallel call fail
        assertFalse(store.claimForResume(sessionId, role), "Second concurrent call should fail");

        // Clear works
        store.clearBatchResuming(sessionId);
        assertTrue(store.claimForResume(sessionId, role), "Call after clearing should succeed again");
    }

    @Test
    void claimForResume_isConcurrentThreadSafe() throws InterruptedException {
        StatefulAgentStoreImpl store = new StatefulAgentStoreImpl();
        store.resumeGuard = new ResumeCoordinationGuard();
        store.storeAdapter = mock(CheckpointStorePort.class);

        // Only the first call to claimForResume succeeds in the DB
        when(store.storeAdapter.claimForResume(anyString(), anyString()))
                .thenReturn(true)
                .thenReturn(false);

        String sessionId = "race-456";
        String role = "coordinator";
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (store.claimForResume(sessionId, role)) {
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
