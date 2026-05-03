package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@QuarkusTest
class DenyReflexTest {

    @Inject
    DenyReflex denyReflex;
    
    private MockedStatic<WorkspaceScopeHolder> mockedScope;

    @InjectMock
    StatefulAgentStore statefulAgentStore;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    CheckpointApprovalService checkpointApprovalService;

    @InjectMock
    AgentCronRepository cronRepository;

    @SuppressWarnings("unchecked")
    Event<AgentJobCompleted> jobCompletedEventMock = mock(Event.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        DenyReflex instance = denyReflex;
        if (denyReflex instanceof ClientProxy) {
            instance = (DenyReflex) ((ClientProxy) denyReflex).arc_contextualInstance();
        }
        instance.jobCompletedEvent = jobCompletedEventMock;
        
        // §7.29 guard: allow tests to proceed by default
        when(statefulAgentStore.claimForResume(anyString(), anyString()))
                .thenReturn(true);

        lenient().when(checkpointApprovalService.denyAll(anyString(), anyString()))
                .thenReturn(new AgentCheckpoint());
        lenient().when(checkpointApprovalService.denyTool(anyString(), anyString(), anyString()))
                .thenReturn(new AgentCheckpoint());
                
        mockedScope = mockStatic(WorkspaceScopeHolder.class);
        mockedScope.when(() -> WorkspaceScopeHolder.run(anyString(), any(Runnable.class)))
                .thenAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; });
        mockedScope.when(() -> WorkspaceScopeHolder.run(anyString(), anyString(), anyString(), any(Runnable.class)))
                .thenAnswer(inv -> { ((Runnable) inv.getArgument(3)).run(); return null; });
        mockedScope.when(() -> WorkspaceScopeHolder.call(anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        mockedScope.when(() -> WorkspaceScopeHolder.call(anyString(), anyString(), anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(3)).call());
    }

    @AfterEach
    void tearDown() {
        if (mockedScope != null) {
            mockedScope.close();
        }
    }

    private MessageContext createContext(String input) {
        ChannelUser user = new ChannelUser("user-1", "user-1", "user-1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "chat-1", Instant.now());
        return new MessageContext("chat-1", user, msg);
    }

    @Test
    void testCronIdDenialResumesAndMarksFailed() throws Exception {
        String cronId = "cron-123";
        String userId = "user-1";
        
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = cronId;
        cron.userId = userId;
        cron.status = CronStatus.AWAITING_APPROVAL;
        cron.lastDeliveryId = "dlv-1";
        cron.chatId = "chat-1";

        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("cron-session-1");
        cp.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        
        when(checkpointApprovalService.denyAll(eq(cronId), anyString())).thenReturn(cp);
        when(cronRepository.findById(cronId)).thenReturn(cron);
        when(agentLoop.run(anyString(), anyString(), eq(AgentSentinels.GOAL_DENY_APPROVED), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Denial Ack", List.of()));

        MessageContext ctx = createContext("/deny " + cronId);
        
        ReflexResult result = WorkspaceScopeHolder.call("session-1", userId, "chat-1", () -> 
                denyReflex.execute("session-1", userId, "chat-1", "/deny " + cronId, ctx));
        
        String resultText = result.commandResponse().get();

        assertEquals("Denial Ack", resultText);
        assertTrue(ctx.isResumeSignal());
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

        when(statefulAgentStore.findLatestAwaitingApproval(anyString())).thenReturn(Optional.empty());
        when(cronRepository.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(cron));

        MessageContext ctx = createContext("/deny");
        ReflexResult result = denyReflex.execute("session-1", userId, "chat-1", "/deny", ctx);
        String resultText = result.commandResponse().get();

        String expected = "⚠️ No active denial in this chat session.\n\n" +
                        "A background task is waiting:\n" +
                        "• Backup databases\n\n" +
                        "Use: `/deny cron-456` to cancel it.";
        assertEquals(expected, resultText);
    }

    @Test
    void testDenySessionCheckpointFindsArchitectRole() {
        String userId = "user-1";
        String sessionId = "session-1";
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setAgentRole("architect");

        when(statefulAgentStore.findLatestAwaitingApproval(eq(sessionId)))
                .thenReturn(Optional.of(cp));

        MessageContext ctx = createContext("/deny");
        ReflexResult result = denyReflex.execute(sessionId, userId, "chat-1", "/deny", ctx);
        String resultText = result.commandResponse().get();

        assertEquals("", resultText);
        verify(checkpointApprovalService).denyAll(eq(sessionId), eq("CLI deny"));
    }

    @Test
    void testResumeGuardBlocksDuplicateCall() {
        String userId = "user-1";
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = "cron-123";
        cron.userId = userId;
        cron.status = CronStatus.AWAITING_APPROVAL;
        
        when(cronRepository.findById("cron-123")).thenReturn(cron);

        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("session-1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(checkpointApprovalService.denyAll(eq("cron-123"), anyString())).thenReturn(cp);
        
        // MOCK THE GUARD TO FAIL
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(false);

        MessageContext ctx = createContext("/deny cron-123");
        ReflexResult result = denyReflex.execute("session-1", userId, "chat-1", "/deny cron-123", ctx);

        assertEquals("⏳ Already processing your denial. Please wait.", result.commandResponse().get());
        assertFalse(ctx.isResumeSignal());
    }

    @Test
    void testAmbiguityWarningWhenBothSessionAndCronPending() {
        String userId = "user-1";
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setOriginalGoal("Store my API key");
        cp.setAgentRole("coordinator");
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));

        AgentCronEntity cron = new AgentCronEntity();
        cron.id = "cron-123";
        cron.goal = "Periodic backup";
        when(cronRepository.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(cron));

        MessageContext ctx = createContext("/deny");
        ReflexResult result = denyReflex.execute("session-1", userId, "chat-1", "/deny", ctx);
        String resultText = result.commandResponse().get();

        assertTrue(resultText.contains("Ambiguous denial"));
        assertTrue(resultText.contains("This Chat"));
        assertTrue(resultText.contains("Store my API key"));
        assertTrue(resultText.contains("Background"));
        assertTrue(resultText.contains("Periodic backup"));
    }

    @Test
    void testCronUuidResolutionWithPrefix() throws Exception {
        String jobId = UUID.randomUUID().toString();
        String fullId = "cron-" + jobId + "-" + UUID.randomUUID().toString();
        
        AgentCronEntity cron = new AgentCronEntity();
        cron.id = jobId;
        cron.userId = "user-1";
        cron.status = CronStatus.AWAITING_APPROVAL;

        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId(fullId);
        cp.setAgentRole("coordinator");

        when(checkpointApprovalService.denyAll(eq(fullId), anyString())).thenReturn(cp);
        when(cronRepository.findById(jobId)).thenReturn(cron);
        when(agentLoop.run(anyString(), anyString(), eq(AgentSentinels.GOAL_DENY_APPROVED), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Denied", List.of()));

        MessageContext ctx = createContext("/deny " + fullId);
        denyReflex.execute("session-1", "user-1", "chat-1", "/deny " + fullId, ctx);

        verify(cronRepository).findById(jobId);
    }

    @Test
    void testPerToolDenialFeedback() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));

        MessageContext ctx = createContext("/deny github");
        ReflexResult result = denyReflex.execute("session-1", "user-1", "chat-1", "/deny github", ctx);
        
        assertTrue(result.commandResponse().get().contains("github"));
        assertTrue(result.commandResponse().get().contains("denied"));
    }
}
