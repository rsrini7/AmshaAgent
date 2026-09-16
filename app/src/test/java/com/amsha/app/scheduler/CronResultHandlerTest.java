package com.amsha.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.event.Event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.event.FlushQueuedNotifications;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CronResultHandlerTest {

    @Mock
    CronStorePort cronStore;

    @Mock
    AuditService auditService;

    @Mock
    WorkspaceManager workspaceManager;

    @Mock
    Event<AgentJobCompleted> jobCompletedEvent;

    @Mock
    StatefulAgentStore statefulAgentStore;

    @Mock
    CronRunStorePort cronRunStore;

    CronResultHandler handler;

    private AgentCronRecord job;
    private final String sessionId = "session-1";
    private final String execId = "exec-1";

    @BeforeEach
    void setup() {
        handler = new CronResultHandler(
                cronStore, cronRunStore, auditService, workspaceManager, jobCompletedEvent, statefulAgentStore);
        lenient().when(cronStore.markCompletedIfOwner(any(), any(), any())).thenReturn(true);
        lenient().when(cronStore.markFiredIfOwner(any(), any(), any(), any())).thenReturn(true);
        lenient().when(cronStore.markAwaitingApprovalIfOwner(any(), any())).thenReturn(true);
        lenient().when(cronStore.markFailedIfOwner(any(), any(), any())).thenReturn(true);

        job = new AgentCronRecord();
        job.setId("cron-1");
        job.setUserId("user-1");
        job.setChatId("chat-1");
        job.setGoal("test goal");
        job.setChannelType("TELEGRAM");
    }

    @Test
    void websocketCronApprovalIsNotQueuedBehindInteractiveApproval() {
        job.setChannelType("WEBSOCKET");

        handler.handleSuccess(
                job,
                "cron-cron-1-exec-1",
                execId,
                AgentResult.awaitingApproval("sandbox_exec", "{}", sessionId, List.of()));

        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(statefulAgentStore, never()).hasPendingApprovalForChat(job.getChatId());
        assertTrue(CronResultHandler.queuedNotifications.isEmpty());
    }

    @Test
    void testFinishedRunOnce_marksCompleted() {
        job.setRunOnce(true);
        job.setExecutionRole("researcher");
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markCompletedIfOwner(eq(job.getId()), eq(execId), any(Instant.class));
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(argThat(event -> sessionId.equals(event.cronSessionId())));
        verify(auditService)
                .record(
                        anyString(),
                        eq(job.getUserId()),
                        eq("researcher"),
                        eq(SecurityEventType.CRONFIRED),
                        ArgumentMatchers.contains("runOnce=true"));
    }

    @Test
    void testFinishedRecurring_marksFiredWithNextTime() {
        job.setRunOnce(false);
        job.setCronExpression("0 0 * * *");
        job.setTimezone("UTC");
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markFiredIfOwner(eq(job.getId()), eq(execId), any(Instant.class), any(Instant.class));
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService)
                .record(
                        anyString(),
                        eq(job.getUserId()),
                        anyString(),
                        eq(SecurityEventType.CRONFIRED),
                        ArgumentMatchers.contains("runOnce=false"));
    }

    @Test
    void testAwaitingApproval_marksAwaitingApproval_skipsTermination() {
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", sessionId, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markAwaitingApprovalIfOwner(job.getId(), execId);
        verify(workspaceManager, never()).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));

        verify(auditService)
                .record(
                        anyString(),
                        eq(job.getUserId()),
                        anyString(),
                        eq(SecurityEventType.LOOP_AWAITING_APPROVAL),
                        ArgumentMatchers.contains("pendingTool=tool"));
    }

    @Test
    void testAwaitingRetry_skipsTermination() {
        AgentResult result = AgentResult.awaitingRetry("rate limited", 5000, List.of());
        when(cronStore.markAwaitingRetryIfOwner(job.getId(), execId)).thenReturn(true);

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markAwaitingRetryIfOwner(job.getId(), execId);
        verify(workspaceManager, never()).terminateSession(sessionId);
    }

    @Test
    void testFlushNotificationsEvent() {
        handler.onFlushNotifications(new FlushQueuedNotifications("chat-1"));
    }

    @Test
    void testLoopExceeded_marksFailed() {
        AgentResult result = AgentResult.loopExceeded(5, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markFailedIfOwner(eq(job.getId()), eq(execId), anyString());
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService)
                .record(anyString(), eq(job.getUserId()), anyString(), eq(SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testFailed_marksFailed() {
        AgentResult result = AgentResult.failed("some error", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markFailedIfOwner(eq(job.getId()), eq(execId), anyString());
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService)
                .record(anyString(), eq(job.getUserId()), anyString(), eq(SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testHandleFailure_marksFailed_terminatesSession() {
        Throwable t = new RuntimeException("crash");

        handler.handleFailure(job, sessionId, execId, t);

        verify(cronStore).markFailedIfOwner(job.getId(), execId, "crash");
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService)
                .record(anyString(), eq(job.getUserId()), anyString(), eq(SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testLeaseLost_skipsNotificationAndTerminatesSession() {
        when(cronStore.markCompletedIfOwner(any(), any(), any())).thenReturn(false);
        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(jobCompletedEvent, never()).fire(any(AgentJobCompleted.class));
        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testUpdateRunStatusSaved() {
        CronRunDto existing = new CronRunDto(
                execId, "cron-1", sessionId, "RUNNING", Instant.now(), null, null, 1, 0, 0, null, Instant.now());
        when(cronRunStore.findById(execId)).thenReturn(Optional.of(existing));

        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronRunStore).save(argThat(dto -> "COMPLETED".equals(dto.status()) && execId.equals(dto.id())));
    }

    @Test
    void testAwaitingRetryLeaseLost() {
        when(cronStore.markAwaitingRetryIfOwner(job.getId(), execId)).thenReturn(false);
        AgentResult result = AgentResult.awaitingRetry("rate limited", 5000, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testApprovalPauseLeaseLost() {
        when(cronStore.markAwaitingApprovalIfOwner(job.getId(), execId)).thenReturn(false);
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", sessionId, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent, never()).fire(any());
    }

    @Test
    void testApprovalPauseQueuedForInteractiveChatAndFlushed() {
        job.setChannelType("TELEGRAM");
        when(statefulAgentStore.hasPendingApprovalForChat("chat-1")).thenReturn(true);
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", sessionId, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        assertTrue(CronResultHandler.queuedNotifications.containsKey("chat-1"));
        verify(jobCompletedEvent, never()).fire(any());

        CronResultHandler.flushQueuedNotifications("chat-1");
        verify(jobCompletedEvent).fire(any());
    }

    @Test
    void testFlushQueuedNotificationsExceptionHandled() {
        CronResultHandler.queuedNotifications
                .computeIfAbsent("chat-err", k -> new CopyOnWriteArrayList<>())
                .add(() -> {
                    throw new RuntimeException("error in task");
                });
        CronResultHandler.flushQueuedNotifications("chat-err");
        CronResultHandler.flushQueuedNotifications(null);
    }

    @Test
    void testHandleFailureLeaseLost() {
        when(cronStore.markFailedIfOwner(eq(job.getId()), eq(execId), anyString()))
                .thenReturn(false);

        handler.handleFailure(job, sessionId, execId, new RuntimeException("fail"));

        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testHandleFailureExpectedTestPattern() {
        handler.handleFailure(job, sessionId, execId, new RuntimeException("[TEST-EXPECTED] intentional test error"));

        verify(cronStore).markFailedIfOwner(eq(job.getId()), eq(execId), anyString());
        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testRecurringJobFailedRescheduled() {
        job.setRunOnce(false);
        job.setCronExpression("0 0 * * *");
        job.setTimezone("UTC");
        AgentResult result =
                AgentResult.failed("transient LLM error", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markFiredIfOwner(eq(job.getId()), eq(execId), any(), any());
        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testEmptyAnswerFallback() {
        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(jobCompletedEvent).fire(argThat(event -> event.renderedAnswer().contains("Done")));
    }

    @Test
    void testUnknownChannelTypeRecordsFallback() {
        job.setChannelType("NONEXISTENT_CHANNEL");
        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(auditService)
                .record(
                        anyString(),
                        eq(job.getUserId()),
                        anyString(),
                        eq(SecurityEventType.LEGACY_CHANNEL_FALLBACK),
                        anyString());
        verify(jobCompletedEvent, never()).fire(any());
    }

    @Test
    void testMissingPlatformAndChatIdSkipsNotification() {
        job.setChannelPlatformId(null);
        job.setChatId(null);
        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(jobCompletedEvent, never()).fire(any());
    }

    @Test
    void testJobCompletedEventFireExceptionCaught() {
        Mockito.doThrow(new RuntimeException("fire fail"))
                .when(jobCompletedEvent)
                .fire(any());
        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testDefaultConstructor() {
        CronResultHandler emptyHandler = new CronResultHandler();
        Assertions.assertNotNull(emptyHandler);
    }
}
