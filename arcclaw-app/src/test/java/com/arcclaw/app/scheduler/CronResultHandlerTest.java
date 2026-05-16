package com.arcclaw.app.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.event.Event;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.domain.model.AgentCronRecord;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.persistence.api.CronStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
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

    CronResultHandler handler;

    private AgentCronRecord job;
    private final String sessionId = "session-1";
    private final String execId = "exec-1";

    @BeforeEach
    void setup() {
        handler = new CronResultHandler(cronStore, auditService, workspaceManager, jobCompletedEvent, statefulAgentStore);
        job = new AgentCronRecord();
        job.setId("cron-1");
        job.setUserId("user-1");
        job.setChatId("chat-1");
        job.setGoal("test goal");
        job.setChannelType("TELEGRAM");
    }

    @Test
    void testFinishedRunOnce_marksCompleted() {
        job.setRunOnce(true);
        AgentResult result = AgentResult.finished("Done", List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markCompleted(eq(job.getId()), any(Instant.class));
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.getUserId()), anyString(),
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

        verify(cronStore).markFired(eq(job.getId()), any(Instant.class), any(Instant.class));
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.getUserId()), anyString(),
                eq(SecurityEventType.CRONFIRED),
                ArgumentMatchers.contains("runOnce=false"));
    }

    @Test
    void testAwaitingApproval_marksAwaitingApproval_skipsTermination() {
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", sessionId, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markAwaitingApproval(job.getId());
        verify(workspaceManager, never()).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));

        verify(auditService).record(anyString(), eq(job.getUserId()), anyString(),
            eq(SecurityEventType.LOOP_AWAITING_APPROVAL),
            ArgumentMatchers.contains("pendingTool=tool"));
    }

    @Test
    void testLoopExceeded_marksFailed() {
        AgentResult result = AgentResult.loopExceeded(5, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markFailed(eq(job.getId()), anyString());
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.getUserId()), anyString(),
                eq(SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testFailed_marksFailed() {
        AgentResult result = AgentResult.failed("some error", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());

        handler.handleSuccess(job, sessionId, execId, result);

        verify(cronStore).markFailed(eq(job.getId()), anyString());
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.getUserId()), anyString(),
                eq(SecurityEventType.CRONFAILED), anyString());
    }

    @Test
    void testHandleFailure_marksFailed_terminatesSession() {
        Throwable t = new RuntimeException("crash");

        handler.handleFailure(job, sessionId, execId, t);

        verify(cronStore).markFailed(job.getId(), "crash");
        verify(workspaceManager).terminateSession(sessionId);
        verify(jobCompletedEvent).fire(any(AgentJobCompleted.class));
        verify(auditService).record(anyString(), eq(job.getUserId()), anyString(),
                eq(SecurityEventType.CRONFAILED), anyString());
    }
}
