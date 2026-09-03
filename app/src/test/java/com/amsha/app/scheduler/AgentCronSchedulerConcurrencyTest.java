package com.amsha.app.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Concurrency-specific tests for {@link AgentCronScheduler}.
 */
@QuarkusTest
@TestProfile(AgentCronSchedulerConcurrencyTest.SmallPoolProfile.class)
class AgentCronSchedulerConcurrencyTest {

    public static class SmallPoolProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("amsha.cron.max-concurrent", "2");
        }
    }

    @Inject
    AgentCronScheduler scheduler;

    @InjectMock
    CronStorePort cronStore;

    @InjectMock
    AdvisoryLockPort advisoryLockPort;

    @InjectMock
    CronJobRunner jobRunner;

    private AgentCronDto createDueJob(boolean runOnce) {
        String id = UUID.randomUUID().toString();
        return new AgentCronDto(
                id,
                "session-" + id,
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                runOnce,
                "CRON: Test goal",
                Instant.now(),
                null,
                "ACTIVE",
                null,
                null,
                null,
                null,
                null);
    }

    @BeforeEach
    void setup() {
        reset(cronStore, jobRunner, advisoryLockPort);

        when(advisoryLockPort.tryLock(anyString(), ArgumentMatchers.anyInt())).thenReturn(true);

        // Ensure exactly 2 permits are available via delegated method call
        scheduler.resetConcurrencyLimit(2);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testThrottledJobIsSkippedNotMarkedFailed() throws Exception {
        AgentCronDto job1 = createDueJob(false);
        AgentCronDto job2 = createDueJob(false);
        AgentCronDto job3 = createDueJob(false);

        when(cronStore.findDueCrons()).thenReturn(List.of(job1, job2, job3));
        when(cronStore.claimForFire(anyString(), anyString())).thenReturn(true);

        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch gateLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        doAnswer(inv -> {
                    try {
                        startLatch.countDown();
                        gateLatch.await(60, TimeUnit.SECONDS);
                        return null;
                    } finally {
                        finishLatch.countDown();
                    }
                })
                .when(jobRunner)
                .execute(any(), anyString());

        scheduler.pollAndDispatch();

        // Job 3 should be throttled because permits = 2
        verify(cronStore).releaseClaimToActive(job3.id());
        verify(cronStore, never()).markFailed(eq(job3.id()), anyString());

        assertTrue(startLatch.await(60, TimeUnit.SECONDS), "Jobs did not start for throttle test");

        // Release them
        gateLatch.countDown();
        assertTrue(finishLatch.await(60, TimeUnit.SECONDS), "Jobs did not finish for throttle test");

        waitForPermits(2);
    }

    private void waitForPermits(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (scheduler.getAvailablePermits() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(
                scheduler.getAvailablePermits() == expected,
                "Expected " + expected + " permits, but got " + scheduler.getAvailablePermits());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSemaphoreReleasedAfterSuccessfulCompletion() throws Exception {
        AgentCronDto job1 = createDueJob(true);
        AgentCronDto job2 = createDueJob(true);
        AgentCronDto job3 = createDueJob(true);

        when(cronStore.findDueCrons()).thenReturn(List.of(job1, job2)).thenReturn(List.of(job3));
        when(cronStore.claimForFire(anyString(), anyString())).thenReturn(true);

        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch gateLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        doAnswer(inv -> {
                    try {
                        startLatch.countDown();
                        gateLatch.await(60, TimeUnit.SECONDS);
                        return null;
                    } finally {
                        finishLatch.countDown();
                    }
                })
                .when(jobRunner)
                .execute(any(), anyString());

        // Fire first two jobs
        scheduler.pollAndDispatch();
        assertTrue(startLatch.await(60, TimeUnit.SECONDS), "Jobs 1-2 did not start successfully");

        // Let them finish
        gateLatch.countDown();
        assertTrue(finishLatch.await(60, TimeUnit.SECONDS), "Jobs 1-2 did not finish successfully");

        // Wait for permit release
        waitForPermits(2);

        // Fire third job - should succeed if semaphore was released
        CountDownLatch finishLatch3 = new CountDownLatch(1);
        doAnswer(inv -> {
                    try {
                        return null;
                    } finally {
                        finishLatch3.countDown();
                    }
                })
                .when(jobRunner)
                .execute(any(), anyString());

        scheduler.pollAndDispatch();
        assertTrue(finishLatch3.await(60, TimeUnit.SECONDS), "Job 3 did not finish after release");

        verify(jobRunner, times(3)).execute(any(), anyString());
    }

    private void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSemaphoreReleasedAfterInnerException() throws Exception {
        AgentCronDto job1 = createDueJob(true);
        AgentCronDto job2 = createDueJob(true);
        AgentCronDto job3 = createDueJob(true);

        when(cronStore.findDueCrons()).thenReturn(List.of(job1, job2)).thenReturn(List.of(job3));
        when(cronStore.claimForFire(anyString(), anyString())).thenReturn(true);

        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch finishLatch = new CountDownLatch(2);

        doAnswer(inv -> {
                    try {
                        startLatch.countDown();
                        throw new RuntimeException("inner-failure");
                    } finally {
                        finishLatch.countDown();
                    }
                })
                .when(jobRunner)
                .execute(any(), anyString());

        // Fire first two jobs that throw
        scheduler.pollAndDispatch();
        assertTrue(startLatch.await(60, TimeUnit.SECONDS), "Jobs 1-2 did not start for exception test");
        assertTrue(finishLatch.await(60, TimeUnit.SECONDS), "Jobs 1-2 did not finish with exception");

        // Wait for permit release
        waitForPermits(2);

        // Fire third job - should execute if permits were released
        CountDownLatch finishLatch3 = new CountDownLatch(1);
        doAnswer(inv -> {
                    try {
                        return null;
                    } finally {
                        finishLatch3.countDown();
                    }
                })
                .when(jobRunner)
                .execute(any(), anyString());

        scheduler.pollAndDispatch();
        assertTrue(finishLatch3.await(60, TimeUnit.SECONDS), "Job 3 did not execute after exceptions");

        verify(jobRunner, times(3)).execute(any(), anyString());
    }
}
