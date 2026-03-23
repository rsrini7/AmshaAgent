package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ApproveReflexTest {

    @Inject
    ApproveReflex approveReflex;

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
        // Mocking Event injection for ApproveReflex
        ApproveReflex instance = approveReflex;
        if (approveReflex instanceof ClientProxy) {
            instance = (ApproveReflex) ((ClientProxy) approveReflex).arc_contextualInstance();
        }
        instance.jobCompletedEvent = jobCompletedEventMock;
    }

    private MessageContext createContext(String input) {
        ChannelUser user = new ChannelUser("user-1", "user-1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "chat-1", Instant.now());
        return new MessageContext("chat-1", user, msg, mock(ChannelSender.class));
    }

    @Test
    void testCronIdApprovalResumesAndCompletes() {
        String cronId = "cron-123";
        String userId = "user-1";
        
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = cronId;
        cron.userId = userId;
        cron.status = CronStatus.AWAITING_APPROVAL;
        cron.runOnce = true;
        cron.lastDeliveryId = "dlv-1";
        cron.chatId = "chat-1";

        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("cron-session-123");

        when(cronRepository.findByUserId(userId)).thenReturn(List.of(cron));
        when(statefulAgentStore.findLatestAwaitingApprovalForCron(cronId)).thenReturn(Optional.of(cp));
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Cron Success", List.of()));

        MessageContext ctx = createContext("/approve " + cronId);
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve " + cronId, ctx);

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().isEmpty());
        assertTrue(ctx.isResumeSignal());
        verify(cronRepository).markCompleted(eq(cronId), any(Instant.class));
        verify(jobCompletedEventMock).fire(any(AgentJobCompleted.class));
    }

    @Test
    void testPlainApproveWithSinglePendingCronGuidesUser() {
        String userId = "user-1";
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = "cron-456";
        cron.goal = "Backup databases";
        cron.status = CronStatus.AWAITING_APPROVAL;

        when(statefulAgentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        when(cronRepository.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(cron));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve", ctx);
        String resultText = result.commandResponse().get();

        assertTrue(resultText.contains("No active approval in this chat session"));
        assertTrue(resultText.contains("Backup databases"));
        assertTrue(resultText.contains("/approve cron-456"));
    }

    @Test
    void testSessionCheckpointWinsOverPendingCron() {
        String userId = "user-1";
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        AgentCronEntity cron = new AgentCronEntity();
        cron.id = "cron-789";
        cron.status = CronStatus.AWAITING_APPROVAL;

        // Session CP exists
        when(statefulAgentStore.load(anyString(), eq(AgentRoleEnum.COORDINATOR.toScopedName())))
                .thenReturn(Optional.of(cp));
        
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Session Result", List.of()));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve", ctx);

        assertEquals("Session Result", result.commandResponse().get());
        assertTrue(ctx.isResumeSignal());
        verify(agentLoop).run(eq("session-1"), eq(AgentRoleEnum.COORDINATOR.toScopedName()), anyString(), any(), anyInt(), eq(ctx));
    }

    @Test
    void testPlainApproveWithMultiplePendingCronsListsAll() {
        String userId = "user-1";
        AgentCronEntity c1 = new AgentCronEntity();
        c1.id = "cron-1";
        c1.goal = "Goal 1";
        c1.status = CronStatus.AWAITING_APPROVAL;

        AgentCronEntity c2 = new AgentCronEntity();
        c2.id = "cron-2";
        c2.goal = "Goal 2";
        c2.status = CronStatus.AWAITING_APPROVAL;

        when(statefulAgentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        when(cronRepository.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(c1, c2));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve", ctx);
        String resultText = result.commandResponse().get();

        assertTrue(resultText.contains("Multiple background tasks awaiting approval"));
        assertTrue(resultText.contains("cron-1"));
        assertTrue(resultText.contains("Goal 1"));
        assertTrue(resultText.contains("cron-2"));
        assertTrue(resultText.contains("Goal 2"));
    }
}
