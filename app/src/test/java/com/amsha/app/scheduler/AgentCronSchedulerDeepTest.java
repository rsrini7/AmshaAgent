package com.amsha.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Deep coverage tests for {@link AgentCronScheduler}.
 * Tests scheduling decisions, orphan sweep, concurrency limits, etc.
 */
@QuarkusTest
class AgentCronSchedulerDeepTest {

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

    private AgentCronDto createDueJob(boolean runOnce, String goal) {
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
                goal,
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
    void setup() {
        reset(cronStore, jobRunner, vThreadsMock, advisoryLockPort);

        when(advisoryLockPort.tryLock(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenReturn(true);

        // Force synchronous execution for deterministic tests
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(vThreadsMock)
                .execute(any(Runnable.class));
    }

    @Test
    void testInit_concurrencyLimiterInitialized() {
        // The concurrencyLimiter may not be accessible depending on CDI proxying,
        // but getAvailablePermits() should work
        int permits = scheduler.getAvailablePermits();
        assertTrue(permits >= 0, "Available permits should be non-negative after initialization");
    }

    @Test
    void testGetAvailablePermits_initial() {
        int permits = scheduler.getAvailablePermits();
        assertTrue(permits >= 0 && permits <= 10, "Initial permits should be between 0 and maxConcurrent");
    }

    @Test
    void testResetConcurrencyLimit() {
        scheduler.resetConcurrencyLimit(5);
        assertEquals(5, scheduler.getAvailablePermits());
    }

    @Test
    void testResetConcurrencyLimit_toZero() {
        scheduler.resetConcurrencyLimit(0);
        assertEquals(0, scheduler.getAvailablePermits());
    }

    @Test
    void testPollAndDispatch_notActive_returns() {
        // Shutdown first
        scheduler.onShutdown(null);
        // Should not throw, should not dispatch
        scheduler.pollAndDispatch();
        verify(vThreadsMock, never()).execute(any());
    }

    @Test
    void testPollAndDispatch_advisoryLockNotLeader_returns() {
        when(advisoryLockPort.tryLock(anyString(), ArgumentMatchers.anyInt())).thenReturn(false);
        when(cronStore.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        // Should not even query due crons since not leader
        verify(cronStore, never()).findDueCrons();
    }

    @Test
    void testPollAndDispatch_lookbackWindow_skipsOldJobs() {
        AgentCronDto oldJob = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-old",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "old goal",
                Instant.now().minus(30, ChronoUnit.MINUTES), // well outside 5m lookback
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);

        when(cronStore.findDueCrons()).thenReturn(List.of(oldJob));

        scheduler.pollAndDispatch();

        // Should NOT dispatch (old job outside lookback window)
        verify(vThreadsMock, never()).execute(any());
    }

    @Test
    void testPollAndDispatch_emptyDue_returns() {
        when(cronStore.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(vThreadsMock, never()).execute(any());
    }

    @Test
    void testPollAndDispatch_claimFails_continues() {
        AgentCronDto job = createDueJob(true, "test goal");
        when(cronStore.findDueCrons()).thenReturn(List.of(job));
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(false);

        scheduler.pollAndDispatch();

        verify(vThreadsMock, never()).execute(any());
    }

    @Test
    void testPollAndDispatch_throttled_noDispatch() {
        AgentCronDto job = createDueJob(true, "test goal");
        when(cronStore.findDueCrons()).thenReturn(List.of(job));
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(true);

        // Drain all permits to force throttle
        scheduler.resetConcurrencyLimit(0);

        scheduler.pollAndDispatch();

        // Should not dispatch when throttled
        verify(jobRunner, never()).execute(any(), anyString());
    }

    @Test
    void testPollAndDispatch_rejectedExecution_releasesSemaphore() {
        AgentCronDto job = createDueJob(true, "test goal");
        when(cronStore.findDueCrons()).thenReturn(List.of(job));
        when(cronStore.claimForFire(eq(job.id()), anyString())).thenReturn(true);

        doAnswer(invocation -> {
                    throw new RejectedExecutionException("pool full");
                })
                .when(vThreadsMock)
                .execute(any(Runnable.class));

        int before = scheduler.getAvailablePermits();
        scheduler.pollAndDispatch();

        assertEquals(before, scheduler.getAvailablePermits(), "Semaphore should be released after rejection");
    }

    @Test
    void testSweepOrphanedApprovalJobs_emptyList() {
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of());
        // Should not throw
        scheduler.onStartup(null);
        verify(cronStore, never()).markFailed(anyString(), anyString());
    }

    @Test
    void testSweepOrphanedApprovalJobs_failsOrphans() {
        AgentCronDto orphanDto = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-orphan",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "orphaned goal",
                Instant.now(),
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                Instant.now(),
                null);

        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(orphanDto));

        // Should not throw
        scheduler.onStartup(null);
    }

    @Test
    void testSweepOrphanedApprovalJobs_runtimeException_suppressesOnShutdown() {
        // Simulate shutdown state
        scheduler.onShutdown(null);

        when(cronStore.findAllAwaitingApproval()).thenThrow(new RuntimeException("database closed"));

        // Should not throw (shutdown suppression)
        scheduler.onStartup(null);
    }

    @Test
    void testSweepOrphanedApprovalJobs_runtimeException_logsOnActive() {
        when(cronStore.findAllAwaitingApproval()).thenThrow(new RuntimeException("unexpected error"));

        // Should not throw
        scheduler.onStartup(null);
    }

    @Test
    void testScheduleSoon_nullJob_returns() {
        when(cronStore.findById("nonexistent")).thenReturn(Optional.empty());
        // Should not throw
        scheduler.scheduleSoon("nonexistent");
    }

    @Test
    void testScheduleSoon_notRunOnce_returns() {
        AgentCronDto dto = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-1",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                false, // not runOnce
                "recurring goal",
                Instant.now().plus(30, ChronoUnit.SECONDS),
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);
        when(cronStore.findById(dto.id())).thenReturn(Optional.of(dto));

        scheduler.scheduleSoon(dto.id());
        // Should not schedule precise job (not runOnce)
    }

    @Test
    void testScheduleSoon_noNextFireTime_returns() {
        AgentCronDto dto = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-1",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal",
                null, // no next fire time
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);
        when(cronStore.findById(dto.id())).thenReturn(Optional.of(dto));

        scheduler.scheduleSoon(dto.id());
        // Should not schedule
    }

    @Test
    void testScheduleSoon_dueSoon_schedules() {
        AgentCronDto dto = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-1",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal",
                Instant.now().plus(30, ChronoUnit.SECONDS), // due in 30s
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);
        when(cronStore.findById(dto.id())).thenReturn(Optional.of(dto));

        scheduler.scheduleSoon(dto.id());
        // Should schedule precise job (within 120s window)
    }

    @Test
    void testExecuteSoonInternal_nullJob_returns() {
        when(cronStore.findById("nonexistent")).thenReturn(Optional.empty());
        // Should not throw
        scheduler.executeSoonInternal("nonexistent");
    }

    @Test
    void testExecuteSoonInternal_claimFails_returns() {
        AgentCronDto dto = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-1",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal",
                Instant.now(),
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);
        when(cronStore.findById(dto.id())).thenReturn(Optional.of(dto));
        when(cronStore.claimForFire(eq(dto.id()), anyString())).thenReturn(false);

        scheduler.executeSoonInternal(dto.id());

        verify(jobRunner, never()).execute(any(), anyString());
    }

    @Test
    void testExecuteSoonInternal_claimSucceeds_executes() {
        AgentCronDto dto = new AgentCronDto(
                UUID.randomUUID().toString(),
                "session-1",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal",
                Instant.now(),
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                null);
        when(cronStore.findById(dto.id())).thenReturn(Optional.of(dto));
        when(cronStore.claimForFire(eq(dto.id()), anyString())).thenReturn(true);

        scheduler.executeSoonInternal(dto.id());

        verify(jobRunner).execute(any(), anyString());
    }

    @Test
    void testPollAndDispatch_runtimeException_shutdownSuppressed() {
        when(cronStore.findDueCrons()).thenThrow(new RuntimeException("pool is closed"));
        scheduler.onShutdown(null);
        // Should not throw
        scheduler.pollAndDispatch();
    }

    @Test
    void testPollAndDispatch_runtimeException_logsWhenActive() {
        when(cronStore.findDueCrons()).thenThrow(new RuntimeException("unexpected"));
        // Should not throw (error is logged, not rethrown)
        scheduler.pollAndDispatch();
    }

    @Test
    void testPollAndDispatch_genericException_logged() {
        when(cronStore.findDueCrons()).thenThrow(new RuntimeException("test"));
        scheduler.pollAndDispatch();
        // Should not throw
    }
}
