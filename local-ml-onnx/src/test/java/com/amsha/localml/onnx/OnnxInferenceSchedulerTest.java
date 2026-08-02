package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class OnnxInferenceSchedulerTest {

    @Test
    void rejectsWorkWhenBoundedQueueIsFull() throws Exception {
        OnnxInferenceScheduler scheduler = scheduler(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var first = scheduler.submit(deadline(), () -> {
            started.countDown();
            release.await();
            return "first";
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        var second = scheduler.submit(deadline(), () -> "second");

        OnnxInferenceSchedulingException error = assertThrows(
                OnnxInferenceSchedulingException.class, () -> scheduler.submit(deadline(), () -> "rejected"));

        assertEquals(OnnxInferenceSchedulingException.Reason.OVERLOAD_REJECTED, error.reason());
        release.countDown();
        assertEquals("first", first.get(1, TimeUnit.SECONDS));
        assertEquals("second", second.get(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void completesQueuedWorkAsTimeoutWhenItsDeadlinePasses() throws Exception {
        OnnxInferenceScheduler scheduler = scheduler(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var first = scheduler.submit(deadline(), () -> {
            started.countDown();
            release.await();
            return "first";
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        var expired = scheduler.submit(Instant.now().plusMillis(10), () -> "too late");

        Thread.sleep(25);
        release.countDown();
        assertEquals("first", first.get(1, TimeUnit.SECONDS));
        CompletionException error = assertThrows(CompletionException.class, expired::join);

        assertEquals(
                OnnxInferenceSchedulingException.Reason.TIMEOUT,
                ((OnnxInferenceSchedulingException) error.getCause()).reason());
        scheduler.shutdown();
    }

    @Test
    void cancellationInterruptsRunningInferenceWork() throws Exception {
        OnnxInferenceScheduler scheduler = scheduler(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        var future = scheduler.submit(deadline(), () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return "unreachable";
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void rejectsAlreadyExpiredDeadlineWithoutQueueingWork() {
        OnnxInferenceScheduler scheduler = scheduler(1, 1);

        OnnxInferenceSchedulingException error = assertThrows(
                OnnxInferenceSchedulingException.class, () -> scheduler.submit(Instant.now(), () -> "too late"));

        assertEquals(OnnxInferenceSchedulingException.Reason.TIMEOUT, error.reason());
        scheduler.shutdown();
    }

    private static OnnxInferenceScheduler scheduler(int threads, int capacity) {
        OnnxInferenceScheduler scheduler = new OnnxInferenceScheduler(threads, capacity, Duration.ofSeconds(1));
        scheduler.initialize();
        return scheduler;
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(5);
    }
}
