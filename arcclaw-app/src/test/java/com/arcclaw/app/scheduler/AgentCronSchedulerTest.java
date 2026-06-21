package com.arcclaw.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import com.arcclaw.persistence.api.AdvisoryLockPort;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link AgentCronScheduler}.
 * Focused on polling and dispatching.
 */
@ExtendWith(MockitoExtension.class)
class AgentCronSchedulerTest {

    @Mock
    CronStorePort cronStore;

    @Mock
    AdvisoryLockPort advisoryLockPort;

    @Mock
    Instance<AdvisoryLockPort> advisoryLockInstance;

    @Mock
    CronJobRunner jobRunner;

    @Mock
    ManagedExecutor vThreadsMock;

    @Mock
    Instance<AgentCronScheduler> selfProxy;

    @InjectMocks
    AgentCronScheduler scheduler;

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
                Instant.now().minus(1, ChronoUnit.MINUTES),
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);
    }

    @BeforeEach
    void setup() throws Exception {
        // Set active=true via reflection
        var activeField = AgentCronScheduler.class.getDeclaredField("active");
        activeField.setAccessible(true);
        activeField.set(scheduler, true);

        // Set config fields via reflection
        setField("maxConcurrent", 10);
        setField("maxLookbackWindow", Duration.ofMinutes(5));
        setField("startupDelayMs", 5000L);

        // Set the advisory lock Instance
        setField("advisoryLock", advisoryLockInstance);

        // Set selfProxy
        setField("selfProxy", selfProxy);

        // Set vThreads
        setField("vThreads", vThreadsMock);

        // Set concurrencyLimiter
        var semField = AgentCronScheduler.class.getDeclaredField("concurrencyLimiter");
        semField.setAccessible(true);
        semField.set(scheduler, new Semaphore(10));

        // Advisory lock returns the mock port and succeeds (lenient: not all tests use these)
        lenient().when(advisoryLockInstance.isUnsatisfied()).thenReturn(false);
        lenient().when(advisoryLockInstance.get()).thenReturn(advisoryLockPort);
        lenient().when(advisoryLockPort.tryLock(anyString(), anyInt())).thenReturn(true);

        // Force synchronous execution for deterministic tests (lenient: not all tests reach executor)
        lenient()
                .doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(vThreadsMock)
                .execute(any(Runnable.class));
    }

    private void setField(String name, Object value) throws Exception {
        var field = AgentCronScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(scheduler, value);
    }

    @Test
    void testNoDueJobsNoDispatch() {
        when(cronStore.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(vThreadsMock, never()).execute(any());
    }

    @Test
    void testDueJobClaimedAndDispatched() {
        AgentCronDto job = createDueJob(true);
        when(cronStore.findDueCrons()).thenReturn(List.of(job));
        when(cronStore.claimForFire(job.id())).thenReturn(true);

        scheduler.pollAndDispatch();

        verify(cronStore).claimForFire(job.id());
        verify(jobRunner).execute(any());
    }

    @Test
    void testDoubleFirePrevented() {
        AgentCronDto job = createDueJob(true);
        when(cronStore.findDueCrons()).thenReturn(List.of(job, job));
        when(cronStore.claimForFire(job.id())).thenReturn(true).thenReturn(false);

        scheduler.pollAndDispatch();

        verify(jobRunner, atMost(1)).execute(any());
    }

    @Test
    void testStaleRunningJobReclaimed() {
        when(cronStore.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(cronStore)
                .reclaimStaleCrons(ArgumentMatchers.argThat(
                        t -> t.isAfter(Instant.now().minus(6, ChronoUnit.MINUTES))
                                && t.isBefore(Instant.now().minus(4, ChronoUnit.MINUTES))));
    }

    @Test
    void testSemaphoreReleasedOnRejection() {
        AgentCronDto job = createDueJob(true);
        when(cronStore.findDueCrons()).thenReturn(List.of(job));
        when(cronStore.claimForFire(job.id())).thenReturn(true);

        // Mock executor to throw RejectedExecutionException
        doAnswer(invocation -> {
                    throw new RejectedExecutionException("Mock rejection");
                })
                .when(vThreadsMock)
                .execute(any(Runnable.class));

        int initialPermits = scheduler.getAvailablePermits();

        scheduler.pollAndDispatch();

        // Verify permits are same as before (acquired and then released in catch)
        Assertions.assertEquals(
                initialPermits,
                scheduler.getAvailablePermits(),
                "Semaphore permits should be recovered after RejectedExecutionException");

        // Verify DB claim was released
        verify(cronStore).releaseClaimToActive(job.id());
    }

    @Test
    void testSchedulerHasNoProducerTemplateField() {
        boolean hasProducerTemplate = Arrays.stream(AgentCronScheduler.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().equals("ProducerTemplate"));
        assertFalse(hasProducerTemplate, "AgentCronScheduler must not depend on ProducerTemplate");
    }
}
