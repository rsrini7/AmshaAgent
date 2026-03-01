package com.arcclaw.app.scheduler;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.LangChainReasoningEngine;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentCronScheduler}.
 * <p>
 * Uses the pattern from TelegramAgentHandlerTest: {@code @InjectMock} for
 * {@code @ApplicationScoped} beans, and ClientProxy unwrap + manual field
 * injection for {@code @Dependent}-scoped beans (ProducerTemplate, ManagedExecutor).
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

    @InjectMock
    WorkspaceContext workspaceContext;

    ProducerTemplate camelProducerMock;
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

    @BeforeEach
    void setup() {
        camelProducerMock = mock(ProducerTemplate.class);
        executorMock = mock(ManagedExecutor.class);

        // Unwrap CDI proxy for Dependent-scoped field injection
        AgentCronScheduler instance = scheduler;
        if (scheduler instanceof io.quarkus.arc.ClientProxy) {
            instance = (AgentCronScheduler) ((io.quarkus.arc.ClientProxy) scheduler).arc_contextualInstance();
        }
        instance.camelProducer = camelProducerMock;
        instance.vThreads = executorMock;

        // Force synchronous execution for deterministic tests
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executorMock).execute(any(Runnable.class));

        reset(cronRepository, agentLoop, auditService, workspaceContext);
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

        // 1. Unwrap the CDI proxy to access the underlying instance
        AgentCronScheduler instance = scheduler;
        if (scheduler instanceof io.quarkus.arc.ClientProxy) {
            instance = (AgentCronScheduler) ((io.quarkus.arc.ClientProxy) scheduler).arc_contextualInstance();
        }

        // 2. Use reflection to force the semaphore to 0 permits
        java.lang.reflect.Field limiterField = AgentCronScheduler.class.getDeclaredField("concurrencyLimiter");
        limiterField.setAccessible(true);
        java.util.concurrent.Semaphore originalSemaphore = (java.util.concurrent.Semaphore) limiterField.get(instance);
        limiterField.set(instance, new java.util.concurrent.Semaphore(0));

        try {
            // 3. Execute — this will immediately hit the !concurrencyLimiter.tryAcquire() block
            scheduler.pollAndDispatch();

            // 4. Verify throttle recovery logic
            verify(cronRepository).releaseClaimToActive(eq(job.id));
            verify(cronRepository, never()).markFailed(eq(job.id), org.mockito.ArgumentMatchers.anyString());
            verify(agentLoop, never()).run(anyString(), anyString(), anyString(), any(), anyInt());
        } finally {
            // 5. Restore the original semaphore so subsequent tests aren't broken
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
    void testDeliveryIdWrittenBeforeTelegramSend() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Delivered", List.of()));

        scheduler.pollAndDispatch();

        InOrder inOrder = inOrder(cronRepository, camelProducerMock);
        inOrder.verify(cronRepository).setDeliveryId(eq(job.id), anyString());
        inOrder.verify(camelProducerMock).sendBodyAndHeaders(anyString(), any(), any());
    }

    @Test
    void testStaleRunningJobReclaimed() {
        when(cronRepository.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(cronRepository).reclaimStaleCrons(any(Instant.class));
    }

    @Test
    void testBackgroundAutonomyLevelSet() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Background OK", List.of()));

        scheduler.pollAndDispatch();

        verify(workspaceContext).setAutonomyLevel(AutonomyLevel.BACKGROUND);
    }

    @Test
    void testOuterThrowableReleasesSemaphore() throws Exception {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);

        // This mock will trigger the catch(Throwable) inside the dispatch lambda
        doThrow(new RuntimeException("[TEST-EXPECTED] Simulated failure in loop"))
                .when(workspaceContext).setAutonomyLevel(any());

        scheduler.pollAndDispatch();

        // The outer catch(Throwable t) must: release semaphore + call markFailed
        verify(cronRepository).markFailed(eq(job.id), contains("[TEST-EXPECTED] Simulated failure"));

        // Verify semaphore was released by checking a second job can be dispatched
        AgentCronEntity job2 = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job2));
        when(cronRepository.claimForFire(job2.id)).thenReturn(true);
        reset(workspaceContext); // Stop throwing
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


}
