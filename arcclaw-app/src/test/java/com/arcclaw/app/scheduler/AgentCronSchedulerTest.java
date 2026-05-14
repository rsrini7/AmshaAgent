package com.arcclaw.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import jakarta.inject.Inject;

import com.arcclaw.persistence.api.AdvisoryLockPort;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Tests for {@link AgentCronScheduler}.
 * Focused on polling and dispatching.
 */
@QuarkusTest
class AgentCronSchedulerTest {

    @Inject
    AgentCronScheduler scheduler;

    @InjectMock
    CronStorePort cronStore;

    @InjectMock
    AdvisoryLockPort advisoryLockPort;

    @InjectMock
    CronJobRunner jobRunner;

    @InjectMock
    ManagedExecutor vThreadsMock;

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
            null
        );
    }

    @BeforeEach
    void setup() {
        reset(cronStore, jobRunner, vThreadsMock, advisoryLockPort);

        when(advisoryLockPort.tryLock(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt())).thenReturn(true);

        // Force synchronous execution for deterministic tests
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(vThreadsMock).execute(any(Runnable.class));
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
        when(cronStore.claimForFire(job.id()))
                .thenReturn(true)
                .thenReturn(false);

        scheduler.pollAndDispatch();

        verify(jobRunner, atMost(1)).execute(any());
    }

    @Test
    void testStaleRunningJobReclaimed() {
        when(cronStore.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(cronStore).reclaimStaleCrons(ArgumentMatchers.argThat(t ->
                t.isAfter(Instant.now().minus(6, ChronoUnit.MINUTES)) &&
                t.isBefore(Instant.now().minus(4, ChronoUnit.MINUTES))));
    }
 
    @Test
    void testSemaphoreReleasedOnRejection() {
        AgentCronDto job = createDueJob(true);
        when(cronStore.findDueCrons()).thenReturn(List.of(job));
        when(cronStore.claimForFire(job.id())).thenReturn(true);
 
        // Mock executor to throw RejectedExecutionException
        doAnswer(invocation -> {
            throw new RejectedExecutionException("Mock rejection");
        }).when(vThreadsMock).execute(any(Runnable.class));
 
        int initialPermits = scheduler.getAvailablePermits();
 
        scheduler.pollAndDispatch();
 
        // Verify permits are same as before (acquired and then released in catch)
        Assertions.assertEquals(initialPermits, scheduler.getAvailablePermits(),
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
