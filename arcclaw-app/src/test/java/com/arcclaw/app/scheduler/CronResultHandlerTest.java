package com.arcclaw.app.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;

import jakarta.enterprise.event.Event;

@ExtendWith(MockitoExtension.class)
class CronResultHandlerTest {

    @Mock
    AgentCronRepository cronRepository;

    @Mock
    AuditService auditService;

    @Mock
    WorkspaceManager workspaceManager;

    @Mock
    Event<AgentJobCompleted> jobCompletedEvent;

    @InjectMocks
    CronResultHandler handler;

    private AgentCronEntity job;
    private final String sessionId = "session-1";
    private final String execId = "exec-1";

    @BeforeEach
    void setup() {
        job = new AgentCronEntity();
        job.id = "cron-1";
        job.userId = "user-1";
        job.chatId = "chat-1";
        job.goal = "test goal";
        job.channelType = "TELEGRAM";
    }

    @Test
    void testFinishedRunOnce_marksCompleted() {
        job.runOnce = true;
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronRepository).markCompleted(eq(job.id), any(Instant.class));
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.userId), anyString(),
                eq(com.arcclaw.core.audit.SecurityEventType.CRONFIRED),
                org.mockito.ArgumentMatchers.contains("runOnce=true"));
    }

    @Test
    void testFinishedRecurring_marksFiredWithNextTime() {
        job.runOnce = false;
        job.cronExpression = "0 0 * * *";
        job.timezone = "UTC";
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronRepository).markFired(eq(job.id), any(Instant.class), any(Instant.class));
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.userId), anyString(),
                eq(com.arcclaw.core.audit.SecurityEventType.CRONFIRED),
                org.mockito.ArgumentMatchers.contains("runOnce=false"));
    }

    @Test
    void testAwaitingApproval_marksAwaitingApproval_skipsTermination() {
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", sessionId, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronRepository).markAwaitingApproval(job.id);
        verify(workspaceManager, never()).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));

        verify(auditService).record(anyString(), eq(job.userId), anyString(),
            eq(SecurityEventType.LOOP_AWAITING_APPROVAL),
            org.mockito.ArgumentMatchers.contains("pendingTool=tool"));
    }

    @Test
    void testLoopExceeded_marksFailed() {
        AgentResult result = AgentResult.loopExceeded(5, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronRepository).markFailed(eq(job.id), anyString());
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.userId), anyString(),
                eq(com.arcclaw.core.audit.SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testFailed_marksFailed() {
        AgentResult result = AgentResult.failed("some error", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronRepository).markFailed(eq(job.id), anyString());
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.userId), anyString(),
                eq(com.arcclaw.core.audit.SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testHandleFailure_marksFailed_terminatesSession() {
        Throwable t = new RuntimeException("crash");

        handler.handleFailure(job, sessionId, execId, t);

        verify(cronRepository).markFailed(job.id, "crash");
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.userId), anyString(),
                eq(com.arcclaw.core.audit.SecurityEventType.CRONFAILED), anyString());
    }
}
