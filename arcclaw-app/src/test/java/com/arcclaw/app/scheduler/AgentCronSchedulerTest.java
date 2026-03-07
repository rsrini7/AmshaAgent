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

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests for {@link AgentCronScheduler}.
 * Focused on polling and dispatching.
 */
@QuarkusTest
class AgentCronSchedulerTest {

    @Inject
    AgentCronScheduler scheduler;

    @InjectMock
    AgentCronRepository cronRepository;

    @InjectMock
    CronJobRunner jobRunner;

    @InjectMock
    ManagedExecutor vThreadsMock;

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
        e.status = CronStatus.ACTIVE;
        e.createdAt = Instant.now();
        return e;
    }

    @BeforeEach
    void setup() {
        reset(cronRepository, jobRunner, vThreadsMock);

        // Force synchronous execution for deterministic tests
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(vThreadsMock).execute(any(Runnable.class));
    }

    @Test
    void testNoDueJobsNoDispatch() {
        when(cronRepository.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(vThreadsMock, never()).execute(any());
    }

    @Test
    void testDueJobClaimedAndDispatched() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);

        scheduler.pollAndDispatch();

        verify(cronRepository).claimForFire(job.id);
        verify(jobRunner).execute(job);
    }

    @Test
    void testDoubleFirePrevented() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job, job));
        when(cronRepository.claimForFire(job.id))
                .thenReturn(true)
                .thenReturn(false);

        scheduler.pollAndDispatch();

        verify(jobRunner, atMost(1)).execute(any());
    }

    @Test
    void testStaleRunningJobReclaimed() {
        when(cronRepository.findDueCrons()).thenReturn(List.of());

        scheduler.pollAndDispatch();

        verify(cronRepository).reclaimStaleCrons(org.mockito.ArgumentMatchers.argThat(t ->
                t.isAfter(Instant.now().minus(6, ChronoUnit.MINUTES)) &&
                t.isBefore(Instant.now().minus(4, ChronoUnit.MINUTES))));
    }

    @Test
    void testSchedulerHasNoProducerTemplateField() {
        boolean hasProducerTemplate = Arrays.stream(AgentCronScheduler.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().equals("ProducerTemplate"));
        assertFalse(hasProducerTemplate, "AgentCronScheduler must not depend on ProducerTemplate");
    }
}
