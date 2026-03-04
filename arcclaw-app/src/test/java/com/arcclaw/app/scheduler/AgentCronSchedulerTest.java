package com.arcclaw.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.app.channel.ChannelSenderRegistry;
import com.arcclaw.app.channel.telegram.TelegramChannelSender;
import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Tests for {@link AgentCronScheduler}.
 */
@QuarkusTest
class AgentCronSchedulerTest {

    @ApplicationScoped
    public static class EventCollector {
        private final List<AgentJobCompleted> events = new CopyOnWriteArrayList<>();
        void onJobCompleted(@Observes AgentJobCompleted event) {
            events.add(event);
        }
        public List<AgentJobCompleted> getEvents() { return events; }
        public void clear() { events.clear(); }
    }

    @Inject
    AgentCronScheduler scheduler;

    @InjectMock
    AgentCronRepository cronRepository;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    AuditService auditService;

    @InjectMock
    ManagedExecutor vThreadsMock;

    @Inject
    EventCollector collector;

    @InjectMock
    ChannelSenderRegistry channelSenderRegistry;  // suppress real Telegram delivery

    @InjectMock
    TelegramChannelSender telegramChannelSender;

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
        reset(cronRepository, agentLoop, auditService, vThreadsMock);
        collector.clear();

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
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Hello!", List.of()));

        scheduler.pollAndDispatch();

        verify(cronRepository).claimForFire(job.id);
        verify(agentLoop).run(
                startsWith("cron-" + job.id + "-"),
                eq(AgentRoleEnum.COORDINATOR.toScopedName()),
                contains(job.goal),
                any(),
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

        assertFalse(collector.getEvents().isEmpty(), "Event was not collected");
        assertTrue(collector.getEvents().get(0).cronId().equals(job.id));
    }

    @Test
    void testCronHitlTransitionsToAwaitingApprovalStatus() {
        AgentCronEntity job = createDueJob(true);
        when(cronRepository.findDueCrons()).thenReturn(List.of(job));
        when(cronRepository.claimForFire(job.id)).thenReturn(true);
        
        AgentResult approvalResult = AgentResult.awaitingApproval("github_tool", "{}", "session-1", List.of());
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(approvalResult);

        scheduler.pollAndDispatch();

        verify(cronRepository).markAwaitingApproval(eq(job.id));
        verify(cronRepository, never()).markCompleted(anyString(), any());
        verify(cronRepository, never()).markFired(anyString(), any(), any());

        assertFalse(collector.getEvents().isEmpty());
        String ans = collector.getEvents().get(0).renderedAnswer();
        assertTrue(ans.contains("Approval Required"));
        assertTrue(ans.contains("github_tool"));
    }
}
