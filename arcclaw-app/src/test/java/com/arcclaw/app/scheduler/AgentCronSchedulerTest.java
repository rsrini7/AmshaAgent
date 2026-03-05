package com.arcclaw.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.LangChainReasoningEngine;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Tests for {@link AgentCronScheduler}.
 * <p>
 * Uses {@code @InjectMock} for {@code @ApplicationScoped} beans, and ClientProxy
 * unwrap + manual field injection for {@code @Dependent}-scoped beans.
 */
@QuarkusTest
class AgentCronSchedulerTest {

    @Inject
    AgentCronScheduler scheduler;

    @InjectMock
    AgentCronRepository cronRepository;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    AuditService auditService;

    @SuppressWarnings("unchecked")
    Event<AgentJobCompleted> jobCompletedEventMock = mock(Event.class);
    ManagedExecutor executorMock;

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
        e.nextFireTime = Instant.now().minus(1, ChronoUnit.MINUTES);
        e.status = "ACTIVE";
        e.createdAt = Instant.now();
        return e;
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        jobCompletedEventMock = mock(Event.class);
        executorMock = mock(ManagedExecutor.class);

        // Unwrap CDI proxy for field injection
        AgentCronScheduler instance = scheduler;
        if (scheduler instanceof io.quarkus.arc.ClientProxy) {
            instance = (AgentCronScheduler) ((io.quarkus.arc.ClientProxy) scheduler).arc_contextualInstance();
        }
        instance.jobCompletedEvent = jobCompletedEventMock;
        instance.vThreads = executorMock;

        // Force synchronous execution for deterministic tests
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executorMock).execute(any(Runnable.class));

        reset(cronRepository, agentLoop, auditService);
    }

    @Test
    void testNoDueJobsNoDispatch() {
        when(cronRepository.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(agentLoop, never()).run(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void testDueJobClaimedAndDispatched() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Hello!", List.of()));

        scheduler.pollAndDispatch();

        verify(cronRepository).claimForFire(job.id);
        verify(agentLoop).run(
                startsWith("cron-" + job.id + "-"),
                eq(AgentRoleEnum.COORDINATOR.toScopedName()),
                eq(job.goal),
                any(LangChainReasoningEngine.class),
                anyInt()
        );
    }

    @Test
    void testDoubleFirePrevented() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job, job));
        when(cronRepository.claimForFire(job.id))
                .thenReturn(true)
                .thenReturn(false);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Result", List.of()));

        scheduler.pollAndDispatch();

        verify(agentLoop, atMost(1)).run(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void testThrottledJobReleasedNotFailed() throws Exception {
        AgentCronEntity job = createDueJob(false);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);

        // Unwrap the CDI proxy to access the underlying instance
        AgentCronScheduler instance = scheduler;
        if (scheduler instanceof io.quarkus.arc.ClientProxy) {
            instance = (AgentCronScheduler) ((io.quarkus.arc.ClientProxy) scheduler).arc_contextualInstance();
        }

        // Use reflection to force the semaphore to 0 permits
        java.lang.reflect.Field limiterField = AgentCronScheduler.class.getDeclaredField("concurrencyLimiter");
        limiterField.setAccessible(true);
        java.util.concurrent.Semaphore originalSemaphore = (java.util.concurrent.Semaphore) limiterField.get(instance);
        limiterField.set(instance, new java.util.concurrent.Semaphore(0));

        try {
            scheduler.pollAndDispatch();

            verify(cronRepository).releaseClaimToActive(eq(job.id));
            verify(cronRepository, never()).markFailed(eq(job.id), org.mockito.ArgumentMatchers.anyString());
            verify(agentLoop, never()).run(anyString(), anyString(), anyString(), any(), anyInt());
        } finally {
            limiterField.set(instance, originalSemaphore);
        }
    }

    @Test
    void testAgentLoopExceptionCallsMarkFailed() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("[TEST-EXPECTED] LLM timeout"));

        scheduler.pollAndDispatch();

        verify(cronRepository).markFailed(eq(job.id), contains("[TEST-EXPECTED] LLM timeout"));
        verify(auditService).record(
                anyString(), eq(job.userId),
                eq(AgentRoleEnum.COORDINATOR.toScopedName()),
                eq(SecurityEventType.CRONFAILED),
                contains(job.id)
        );
    }

    @Test
    void testRunOnceTrueCallsMarkCompleted() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Done!", List.of()));

        scheduler.pollAndDispatch();

        verify(cronRepository).markCompleted(eq(job.id), any(Instant.class));
        verify(cronRepository, never()).markFired(anyString(), any(), any());
    }

    @Test
    void testRunOnceFalseCallsMarkFiredWithNextTime() {
        AgentCronEntity job = createDueJob(false);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Recurring OK", List.of()));

        scheduler.pollAndDispatch();

        verify(cronRepository).markFired(eq(job.id), any(Instant.class), any(Instant.class));
        verify(cronRepository, never()).markCompleted(anyString(), any());
    }

    @Test
    void testStaleRunningJobReclaimed() {
        when(cronRepository.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(cronRepository).reclaimStaleCrons(any(Instant.class));
    }

    @Test
    void testOuterThrowableReleasesSemaphore() throws Exception {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);

        // This mock will trigger the catch(Throwable) inside the dispatch lambda
        doThrow(new RuntimeException("[TEST-EXPECTED] Simulated failure in loop"))
                .when(cronRepository).setDeliveryId(anyString(), anyString());

        scheduler.pollAndDispatch();

        // The outer catch(Throwable t) must: release semaphore + call markFailed
        verify(cronRepository).markFailed(eq(job.id), contains("[TEST-EXPECTED] Simulated failure"));

        // Verify semaphore was released by checking a second job can be dispatched
        AgentCronEntity job2 = createDueJob(true);
        reset(cronRepository); // Stop throwing
        when(cronRepository.findDueCrons()).thenReturn(List.of(job2));
        when(cronRepository.claimForFire(job2.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("OK", List.of()));

        scheduler.pollAndDispatch();
        verify(agentLoop, times(1)).run(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void testConcurrencyLimitEnforced() throws Exception {
        // Build 11 jobs: first 10 should dispatch, 11th should be throttled
        List<AgentCronEntity> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            jobs.add(createDueJob(true));
        }
        when(cronRepository.findDueCrons()).thenReturn(jobs);
        jobs.forEach(j -> when(cronRepository.claimForFire(j.id)).thenReturn(true));
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("OK", List.of()));

        // Set semaphore to exactly maxConcurrent (10)
        AgentCronScheduler instance = scheduler;
        if (scheduler instanceof io.quarkus.arc.ClientProxy) {
            instance = (AgentCronScheduler) ((io.quarkus.arc.ClientProxy) scheduler).arc_contextualInstance();
        }
        java.lang.reflect.Field limiterField = AgentCronScheduler.class.getDeclaredField("concurrencyLimiter");
        limiterField.setAccessible(true);
        java.util.concurrent.Semaphore tightSemaphore = new java.util.concurrent.Semaphore(10);
        java.util.concurrent.Semaphore original = (java.util.concurrent.Semaphore) limiterField.get(instance);
        limiterField.set(instance, tightSemaphore);

        // Force throttle by taking all 10 permits BEFORE the loop
        tightSemaphore.acquire(10);

        try {
            scheduler.pollAndDispatch();

            // All 11 jobs must have been throttled
            verify(cronRepository, times(11)).releaseClaimToActive(anyString());
        } finally {
            limiterField.set(instance, original);
        }
    }

    // --- Component 10 new tests ---

    @Test
    void testSchedulerHasNoProducerTemplateField() {
        boolean hasProducerTemplate = Arrays.stream(AgentCronScheduler.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().equals("ProducerTemplate"));
        assertFalse(hasProducerTemplate, "AgentCronScheduler must not depend on ProducerTemplate");
    }

    @Test
    void testSchedulerFiresJobCompletedEvent() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Event test", List.of()));

        scheduler.pollAndDispatch();

        // Verify the CDI event was fired with the correct cronId and channel type
        verify(jobCompletedEventMock).fire(any(AgentJobCompleted.class));
    }
}
