package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ClassifierShadowExecutorTest {

    @Test
    void rejectsBeyondDedicatedWorkerAndQueueCapacity() throws Exception {
        ClassifierShadowExecutor executor = new ClassifierShadowExecutor(1, 1, Duration.ofSeconds(1));
        executor.initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            started.await(1, TimeUnit.SECONDS);
            executor.execute(() -> await(release));

            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
