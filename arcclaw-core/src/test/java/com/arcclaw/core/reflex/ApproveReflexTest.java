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

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCheckpointRecord;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ApproveReflexTest {

    @Inject
    ApproveReflex approveReflex;

    private MockedStatic<WorkspaceScopeHolder> mockedScope;

    @InjectMock
    StatefulAgentStore statefulAgentStore;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    CheckpointApprovalService checkpointApprovalService;

    @InjectMock
    CronStorePort cronStore;

    @InjectMock
    ReasoningEngine reasoningEngine;

    @InjectMock
    CheckpointResumeHandler checkpointResumeHandler;

    @InjectMock
    WorkspaceManager workspaceManager;

    @InjectMock
    DelegateApprovalGuard delegateGuard;

    ApprovalNotificationService notificationServiceMock = mock(ApprovalNotificationService.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ApproveReflex instance = approveReflex;
        if (approveReflex instanceof ClientProxy) {
            instance = (ApproveReflex) ((ClientProxy) approveReflex).arc_contextualInstance();
        }
        instance.notificationService = notificationServiceMock;
        lenient().when(notificationServiceMock.createJobNotifier()).thenReturn((cron, result) -> {});

        // §7.29 guard: allow tests to proceed by default
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(true);

        when(checkpointResumeHandler.resolveResumeGoal(anyString(), anyString()))
                .thenReturn("Resume Goal");

        when(delegateGuard.isDelegate(anyString())).thenReturn(true);

        mockedScope = mockStatic(WorkspaceScopeHolder.class);
        mockedScope
                .when(() -> WorkspaceScopeHolder.run(anyString(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return null;
                });
        mockedScope
                .when(() -> WorkspaceScopeHolder.run(anyString(), anyString(), anyString(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(3)).run();
                    return null;
                });
        mockedScope
                .when(() -> WorkspaceScopeHolder.call(anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        mockedScope
                .when(() -> WorkspaceScopeHolder.call(anyString(), anyString(), anyString(), any(Callable.class)))
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
    void testCronIdApprovalResumesAndCompletes() {
        String cronId = "cron-123";
        String userId = "user-1";

        var cron = new AgentCronDto(
                cronId,
                null,
                userId,
                "chat-1",
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                "dlv-1",
                null,
                null);

        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("cron-session-123");
        cp.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());

        when(checkpointApprovalService.approveAll(eq(cronId), anyString())).thenReturn(cp);
        when(cronStore.findById(cronId)).thenReturn(Optional.of(cron));
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Cron Success", List.of()));

        MessageContext ctx = createContext("/approve " + cronId);
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve " + cronId, ctx);

        assertTrue(result.commandResponse().isPresent());
        assertEquals("", result.commandResponse().get());
        assertTrue(ctx.isResumeSignal());
        verify(cronStore).markCompleted(eq(cronId), any(Instant.class));
        verify(notificationServiceMock).createJobNotifier();
    }

    @Test
    void testPlainApproveWithSinglePendingCronGuidesUser() {
        String userId = "user-1";
        var cron = new AgentCronDto(
                "cron-456",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "Backup databases",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);

        when(statefulAgentStore.findLatestAwaitingApproval(anyString())).thenReturn(Optional.empty());
        when(cronStore.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(cron));

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
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("session-1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());

        // Session CP exists
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve", ctx);

        assertEquals("", result.commandResponse().get());
        verify(checkpointApprovalService).approveAll(eq("session-1"), anyString());
    }

    @Test
    void testResumeGuardBlocksDuplicateCall() {
        String userId = "user-1";
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("session-1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());

        // Fix: Use specific IDs to avoid misidentifying cron ID as session ID
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));
        when(statefulAgentStore.findLatestAwaitingApproval("cron-123")).thenReturn(Optional.empty());

        // MOCK THE GUARD TO FAIL (Someone else is already resuming)
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(false);

        // test for cron approval
        MessageContext ctx = createContext("/approve cron-123");

        // Mock a cron pending
        var cron = new AgentCronDto(
                "cron-123",
                null,
                userId,
                "chat-1",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        AgentCheckpoint cp2 = new AgentCheckpointRecord();
        cp2.setSessionId("cron-session-1");
        cp2.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        when(checkpointApprovalService.approveAll(eq("cron-123"), anyString())).thenReturn(cp2);
        when(cronStore.findById("cron-123")).thenReturn(Optional.of(cron));

        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve cron-123", ctx);

        assertEquals(
                "⏳ Already processing your approval. Please wait.",
                result.commandResponse().get());
        // Verify signal is NOT set because the loop was never entered
        assertFalse(ctx.isResumeSignal(), "Resume signal should NOT be set when guard blocks call");
    }

    @Test
    void testPlainApproveWithMultiplePendingCronsListsAll() {
        // No live session + 2 CRONs → CRON-only disambiguation prompt
        String userId = "user-1";
        var c1 = new AgentCronDto(
                "cron-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "Goal 1",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        var c2 = new AgentCronDto(
                "cron-2",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "Goal 2",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);

        when(statefulAgentStore.findLatestAwaitingApproval(anyString())).thenReturn(Optional.empty());
        when(cronStore.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(c1, c2));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve", ctx);
        String resultText = result.commandResponse().get();

        assertTrue(resultText.contains("Ambiguous approval"));
        assertTrue(resultText.contains("cron-1"));
        assertTrue(resultText.contains("Goal 1"));
        assertTrue(resultText.contains("cron-2"));
        assertTrue(resultText.contains("Goal 2"));
    }

    @Test
    void testAmbiguityWarningWhenBothSessionAndCronPending() {
        // With a live session pending, bare /approve always resolves to the live session.
        // CRONs require explicit targeting — no ambiguity prompt shown.
        String userId = "user-1";
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setOriginalGoal("Store my API key");
        cp.setAgentRole("coordinator");
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));

        var cron = new AgentCronDto(
                "cron-123",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "Periodic backup",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        when(cronStore.findAwaitingApprovalByUserId(userId)).thenReturn(List.of(cron));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", userId, "chat-1", "/approve", ctx);
        String resultText = result.commandResponse().get();

        // Live session is approved directly — no ambiguity prompt
        assertEquals("", resultText);
        verify(checkpointApprovalService).approveAll(eq("session-1"), anyString());
    }

    @Test
    void testCronUuidResolutionWithPrefix() {
        String jobId = UUID.randomUUID().toString();
        String fullId = "cron-" + jobId + "-" + UUID.randomUUID().toString();

        var cron = new AgentCronDto(
                jobId,
                null,
                "user-1",
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);

        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId(fullId);
        cp.setAgentRole("coordinator");

        when(checkpointApprovalService.approveAll(eq(fullId), anyString())).thenReturn(cp);
        when(cronStore.findById(jobId)).thenReturn(Optional.of(cron));
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Success", List.of()));

        MessageContext ctx = createContext("/approve " + fullId);
        approveReflex.execute("session-1", "user-1", "chat-1", "/approve " + fullId, ctx);

        verify(cronStore).findById(jobId);
    }

    @Test
    void testFailedCheckpointGuidance() {
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.empty());
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of());

        AgentCheckpoint failedCp = new AgentCheckpointRecord();
        failedCp.setStatus(CheckpointStatus.FAILED);
        when(statefulAgentStore.findLatestCheckpoint("session-1")).thenReturn(Optional.of(failedCp));

        MessageContext ctx = createContext("/approve");
        ReflexResult result = approveReflex.execute("session-1", "user-1", "chat-1", "/approve", ctx);

        assertTrue(result.commandResponse().get().contains("last attempt failed"));
        assertTrue(result.commandResponse().get().contains("rate limit"));
    }
}
