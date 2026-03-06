package com.arcclaw.app.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Concurrency-specific tests for {@link AgentCronScheduler}.
 */
@QuarkusTest
@TestProfile(AgentCronSchedulerConcurrencyTest.SmallPoolProfile.class)
class AgentCronSchedulerConcurrencyTest {

    @ApplicationScoped
    public static class EventCollector {
        private final List<AgentJobCompleted> events = new CopyOnWriteArrayList<>();
        void onJobCompleted(@Observes AgentJobCompleted event) {
            events.add(event);
        }
        public List<AgentJobCompleted> getEvents() { return events; }
        public void clear() { events.clear(); }
    }

    public static class SmallPoolProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("arcclaw.cron.max-concurrent", "2");
        }
    }

    @Inject
    AgentCronScheduler scheduler;

    @InjectMock
    AgentCronRepository cronRepository;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    AuditService auditService;

    @Inject
    EventCollector collector;

    private AgentCronEntity createDueJob(boolean runOnce) {
        AgentCronEntity e = new AgentCronEntity();
        e.id = UUID.randomUUID().toString();
        e.sessionId = "session-" + e.id;
        e.userId = "user-1";
        e.chatId = "chat-1";
        e.cronExpression = "0 9 * * 1";
        e.timezone = "UTC";
        e.runOnce = runOnce;
        e.goal = "CRON: Test goal";
        e.nextFireTime = Instant.now();
        e.status = CronStatus.ACTIVE;
        return e;
    }

    @BeforeEach
    void setup() {
        reset(cronRepository, agentLoop, auditService);
        collector.clear();

        // Ensure exactly 2 permits are available via delegated method call
        scheduler.resetConcurrencyLimit(2);
    }

    @Test
    void testThrottledJobIsSkippedNotMarkedFailed() throws Exception {
        AgentCronEntity job1 = createDueJob(false);
        AgentCronEntity job2 = createDueJob(false);
        AgentCronEntity job3 = createDueJob(false);

        when(cronRepository.findDueCrons()).thenReturn(List.of(job1, job2, job3));
        when(cronRepository.claimForFire(anyString())).thenReturn(true);

        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch gateLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt())).thenAnswer(inv -> {
            try {
                startLatch.countDown();
                gateLatch.await(60, TimeUnit.SECONDS);
                return AgentResult.finished("OK", List.of());
            } finally {
                finishLatch.countDown();
            }
        });

        scheduler.pollAndDispatch();

        // Job 3 should be throttled because permits = 2
        verify(cronRepository).releaseClaimToActive(job3.id);
        verify(cronRepository, never()).markFailed(eq(job3.id), anyString());

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
        assertTrue(scheduler.getAvailablePermits() == expected, "Expected " + expected + " permits, but got " + scheduler.getAvailablePermits());
    }

    @Test
    void testSemaphoreReleasedAfterSuccessfulCompletion() throws Exception {
        AgentCronEntity job1 = createDueJob(true);
        AgentCronEntity job2 = createDueJob(true);
        AgentCronEntity job3 = createDueJob(true);

        when(cronRepository.findDueCrons())
            .thenReturn(List.of(job1, job2))
            .thenReturn(List.of(job3));
        when(cronRepository.claimForFire(anyString())).thenReturn(true);

        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch gateLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt())).thenAnswer(inv -> {
            try {
                startLatch.countDown();
                gateLatch.await(60, TimeUnit.SECONDS);
                return AgentResult.finished("done", List.of());
            } finally {
                finishLatch.countDown();
            }
        });

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
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt())).thenAnswer(inv -> {
            try {
                return AgentResult.finished("done", List.of());
            } finally {
                finishLatch3.countDown();
            }
        });

        scheduler.pollAndDispatch();
        assertTrue(finishLatch3.await(60, TimeUnit.SECONDS), "Job 3 did not finish after release");
        
        verify(agentLoop, times(3)).run(anyString(), anyString(), anyString(), any(), anyInt());
    }

    private void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    @Test
    void testSemaphoreReleasedAfterInnerException() throws Exception {
        AgentCronEntity job1 = createDueJob(true);
        AgentCronEntity job2 = createDueJob(true);
        AgentCronEntity job3 = createDueJob(true);

        when(cronRepository.findDueCrons())
            .thenReturn(List.of(job1, job2))
            .thenReturn(List.of(job3));
        when(cronRepository.claimForFire(anyString())).thenReturn(true);

        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch finishLatch = new CountDownLatch(2);

        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenAnswer(inv -> {
                try {
                    startLatch.countDown();
                    throw new RuntimeException("inner-failure");
                } finally {
                    finishLatch.countDown();
                }
            })
            .thenAnswer(inv -> {
                try {
                    startLatch.countDown();
                    throw new RuntimeException("inner-failure");
                } finally {
                    finishLatch.countDown();
                }
            })
            .thenReturn(AgentResult.finished("ok", List.of()));

        // Fire first two jobs that throw
        scheduler.pollAndDispatch();
        assertTrue(startLatch.await(60, TimeUnit.SECONDS), "Jobs 1-2 did not start for exception test");
        assertTrue(finishLatch.await(60, TimeUnit.SECONDS), "Jobs 1-2 did not finish with exception");
        
        // Wait for permit release
        waitForPermits(2);

        verify(cronRepository, times(2)).markFailed(anyString(), eq("inner-failure"));

        // Fire third job - should execute if permits were released
        CountDownLatch finishLatch3 = new CountDownLatch(1);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt())).thenAnswer(inv -> {
            try {
                return AgentResult.finished("ok", List.of());
            } finally {
                finishLatch3.countDown();
            }
        });

        scheduler.pollAndDispatch();
        assertTrue(finishLatch3.await(60, TimeUnit.SECONDS), "Job 3 did not execute after exceptions");
        
        verify(agentLoop, times(3)).run(anyString(), anyString(), anyString(), any(), anyInt());
    }
}
