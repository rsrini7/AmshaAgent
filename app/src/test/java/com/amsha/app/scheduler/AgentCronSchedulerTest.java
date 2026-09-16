package com.amsha.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.amsha.core.cron.CronTaskScheduled;
import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
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
        return createJob(runOnce, Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    private AgentCronDto createJob(boolean runOnce, Instant nextFireTime) {
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
                nextFireTime,
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
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(true);

        scheduler.pollAndDispatch();

        verify(cronStore).claimForFire(eq(job.id()), anyString());
        verify(jobRunner).execute(any(), anyString());
    }

    @Test
    void testDoubleFirePrevented() {
        AgentCronDto job = createDueJob(true);
        when(cronStore.findDueCrons()).thenReturn(List.of(job, job));
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(true).thenReturn(false);

        scheduler.pollAndDispatch();

        verify(jobRunner, atMost(1)).execute(any(), anyString());
    }

    @Test
    void staleOneShotIsFailedInsteadOfRescheduled() {
        AgentCronDto job = createJob(true, Instant.now().minus(10, ChronoUnit.MINUTES));
        when(cronStore.findDueCrons()).thenReturn(List.of(job));

        scheduler.pollAndDispatch();

        verify(cronStore).markFailed(eq(job.id()), ArgumentMatchers.contains("One-shot"));
        verify(cronStore, never()).markSkipped(anyString(), any(), any());
        verify(cronStore, never()).claimForFire(anyString(), anyString());
        verify(jobRunner, never()).execute(any(), anyString());
    }

    @Test
    void staleRecurringJobIsRescheduledAndRemainsActive() {
        AgentCronDto job = createJob(false, Instant.now().minus(10, ChronoUnit.MINUTES));
        when(cronStore.findDueCrons()).thenReturn(List.of(job));

        scheduler.pollAndDispatch();

        verify(cronStore).markFired(eq(job.id()), any(Instant.class), any(Instant.class));
        verify(cronStore, never()).markSkipped(anyString(), any(), any());
        verify(cronStore, never()).claimForFire(anyString(), anyString());
        verify(jobRunner, never()).execute(any(), anyString());
    }

    @Test
    void startupSchedulesRecoveryPollForPersistedJobs() throws Exception {
        ScheduledExecutorService startupExecutor = Executors.newSingleThreadScheduledExecutor();
        try {
            setField("preciseScheduler", startupExecutor);
            setField("startupDelayMs", 0L);
            when(cronStore.findAllAwaitingApproval()).thenReturn(List.of());

            AgentCronScheduler proxy = mock(AgentCronScheduler.class);
            when(selfProxy.get()).thenReturn(proxy);

            scheduler.onStartup(null);

            verify(proxy, timeout(1000)).pollAndDispatch();
        } finally {
            startupExecutor.shutdownNow();
        }
    }

    @Test
    void preciseDispatchRejectionReleasesClaim() {
        AgentCronDto job = createDueJob(true);
        when(cronStore.findById(job.id())).thenReturn(Optional.of(job));
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(true);
        doAnswer(invocation -> {
                    throw new RejectedExecutionException("precise executor stopped");
                })
                .when(vThreadsMock)
                .execute(any(Runnable.class));

        scheduler.executeSoonInternal(job.id());

        verify(cronStore).releaseClaimToActive(job.id());
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
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(true);

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

    @Test
    void testOnShutdownSetsInactive() {
        scheduler.onShutdown(null);
        scheduler.pollAndDispatch();
        verify(cronStore, never()).findDueCrons();
    }

    @Test
    void testOrphanSweepFailsJobs() {
        AgentCronDto orphan = createDueJob(true);
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(orphan));

        scheduler.onStartup(null);

        verify(cronStore).markFailed(eq(orphan.id()), ArgumentMatchers.contains("Orphaned"));
    }

    @Test
    void testOrphanSweepHandlesException() {
        when(cronStore.findAllAwaitingApproval()).thenThrow(new RuntimeException("DB error"));
        scheduler.onStartup(null);
    }

    @Test
    void testScheduleSoonBranches() throws Exception {
        ScheduledExecutorService mockExec = mock(ScheduledExecutorService.class);
        setField("preciseScheduler", mockExec);

        scheduler.scheduleSoon("nonexistent");

        AgentCronDto notRunOnce = createJob(false, Instant.now().plusSeconds(60));
        when(cronStore.findById("not-run-once")).thenReturn(Optional.of(notRunOnce));
        scheduler.scheduleSoon("not-run-once");

        AgentCronDto farFuture = createJob(true, Instant.now().plusSeconds(300));
        when(cronStore.findById("far-future")).thenReturn(Optional.of(farFuture));
        scheduler.scheduleSoon("far-future");

        AgentCronDto soon = createJob(true, Instant.now().plusSeconds(30));
        when(cronStore.findById("soon")).thenReturn(Optional.of(soon));
        scheduler.scheduleSoon("soon");
        verify(mockExec).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void testOnTaskScheduled() {
        scheduler.onTaskScheduled(new CronTaskScheduled("job-123"));
    }

    @Test
    void testResetConcurrencyLimit() {
        scheduler.resetConcurrencyLimit(5);
        Assertions.assertEquals(5, scheduler.getAvailablePermits());
    }

    @Test
    void testPollAndDispatchNotLeader() {
        when(advisoryLockPort.tryLock(anyString(), anyInt())).thenReturn(false);
        scheduler.pollAndDispatch();
        verify(cronStore, never()).findDueCrons();
    }

    @Test
    void testPollAndDispatchReclaimStaleFails() {
        Mockito.doThrow(new RuntimeException("reclaim fail")).when(cronStore).reclaimStaleCrons(any());
        scheduler.pollAndDispatch();
        verify(cronStore, never()).findDueCrons();
    }

    @Test
    void testPollAndDispatchThrottled() {
        AgentCronDto dueJob = createDueJob(true);
        when(cronStore.findDueCrons()).thenReturn(List.of(dueJob));
        when(cronStore.claimForFire(eq(dueJob.id()), anyString())).thenReturn(true);

        scheduler.resetConcurrencyLimit(0);
        scheduler.pollAndDispatch();

        verify(cronStore).releaseClaimToActive(dueJob.id());
    }

    @Test
    void testShutdown() {
        scheduler.shutdown();
    }
}
