package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.StatefulAgentStore;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

@QuarkusTest
class DenyReflexTest {

    @Inject
    DenyReflex denyReflex;

    @InjectMock
    StatefulAgentStore statefulAgentStore;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    AgentCronRepository cronRepository;

    @SuppressWarnings("unchecked")
    Event<AgentJobCompleted> jobCompletedEventMock = mock(Event.class);

    @BeforeEach
    void setup() {
        DenyReflex instance = denyReflex;
        if (denyReflex instanceof io.quarkus.arc.ClientProxy) {
            instance = (DenyReflex) ((io.quarkus.arc.ClientProxy) denyReflex).arc_contextualInstance();
        }
        instance.jobCompletedEvent = jobCompletedEventMock;
    }

    @Test
    void testCronIdDenialResumesAndMarksFailed() {
        String cronId = "cron-123";
        String userId = "user-1";
        
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = cronId;
        cron.userId = userId;
        cron.status = CronStatus.AWAITING_APPROVAL;
        cron.lastDeliveryId = "dlv-1";
        cron.chatId = "chat-1";

        when(cronRepository.findByUserId(userId)).thenReturn(List.of(cron));
        when(agentLoop.run(anyString(), anyString(), eq("Resuming task after denial."), any(), eq(1)))
                .thenReturn(AgentResult.finished("Denial Ack", List.of()));

        String resultText = denyReflex.execute("session-1", userId, "chat-1", "/deny " + cronId);

        assertTrue(resultText.contains("denied and cancelled"));
        verify(cronRepository).markFailed(eq(cronId), anyString());
        verify(jobCompletedEventMock).fire(any(AgentJobCompleted.class));
    }

    @Test
    void testPlainDenyWithSinglePendingCronGuidesUser() {
        String userId = "user-1";
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = "cron-456";
        cron.goal = "Backup databases";
        cron.status = CronStatus.AWAITING_APPROVAL;

        when(statefulAgentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        when(cronRepository.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(cron));

        String resultText = denyReflex.execute("session-1", userId, "chat-1", "/deny");

        assertTrue(resultText.contains("No active denial in this chat session"));
        assertTrue(resultText.contains("Backup databases"));
        assertTrue(resultText.contains("/deny cron-456"));
    }

    @Test
    void testDenySessionCheckpointFindsArchitectRole() {
        String userId = "user-1";
        String sessionId = "session-1";
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        // Architect role was not in the hardcoded list before
        when(statefulAgentStore.load(eq(sessionId), eq("architect")))
                .thenReturn(Optional.of(cp));

        String resultText = denyReflex.execute(sessionId, userId, "chat-1", "/deny");

        assertTrue(resultText.contains("Execution denied"));
        verify(statefulAgentStore).save(argThat(checkpoint -> 
            checkpoint.getStatus() == CheckpointStatus.FAILED &&
            "Human denied the tool execution.".equals(checkpoint.getFailReason())
        ));
    }
}
