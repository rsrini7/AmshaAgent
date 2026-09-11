package com.amsha.core.reflex;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
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
    CronStorePort cronStore;

    @InjectMock
    DelegateApprovalGuard delegateGuard;

    @InjectMock
    AuditHashUtil auditHashUtil;

    ApprovalNotificationService notificationServiceMock = mock(ApprovalNotificationService.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        DenyReflex instance = denyReflex;
        if (denyReflex instanceof ClientProxy) {
            instance = (DenyReflex) ((ClientProxy) denyReflex).arc_contextualInstance();
        }
        instance.notificationService = notificationServiceMock;
        lenient().when(notificationServiceMock.createJobNotifier()).thenReturn((cron, result) -> {});

        // Mock AuditHashUtil to avoid HMAC key missing in test environment
        lenient().when(auditHashUtil.sha256hex(anyString())).thenCallRealMethod();
        lenient().when(auditHashUtil.hmacHex(anyString())).thenAnswer(inv -> {
            String data = inv.getArgument(0);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        });
        lenient().doCallRealMethod().when(auditHashUtil).calculateChainFields(any(), any());

        // §7.29 guard: allow tests to proceed by default
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(true);
        when(delegateGuard.isDelegate(anyString())).thenReturn(true);

        lenient()
                .when(checkpointApprovalService.denyAll(anyString(), anyString()))
                .thenReturn(new AgentCheckpointRecord());
        lenient()
                .when(checkpointApprovalService.denyTool(anyString(), anyString(), anyString()))
                .thenReturn(new AgentCheckpointRecord());

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
    void testCronIdDenialResumesAndMarksFailed() throws Exception {
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
                false,
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
        cp.setSessionId("cron-session-1");
        cp.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        cp.setBatchToolDecisions("{\"sandbox_exec\":\"PENDING\"}");

        AgentCheckpoint resolved = new AgentCheckpointRecord();
        resolved.setSessionId("cron-session-1");
        resolved.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        resolved.setBatchToolDecisions("{\"sandbox_exec\":\"DENIED\"}");
        when(checkpointApprovalService.denyAll(anyString(), anyString())).thenReturn(resolved);
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(anyString()))
                .thenReturn(Optional.of(cp));
        when(statefulAgentStore.findLatestAwaitingApproval("cron-123")).thenReturn(Optional.of(cp));
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(true);
        when(cronStore.findById("123")).thenReturn(Optional.of(cron));
        when(agentLoop.run(
                        anyString(), anyString(), eq(AgentSentinels.GOAL_DENY_APPROVED), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Denial Ack", List.of()));

        MessageContext ctx = createContext("/deny " + cronId);

        ReflexResult result = WorkspaceScopeHolder.call(
                "session-1",
                userId,
                "chat-1",
                () -> denyReflex.execute("session-1", userId, "chat-1", "/deny " + cronId, ctx));

        String resultText = result.commandResponse().get();

        assertEquals("Resuming task...", resultText);
    }

    @Test
    void testPlainDenyWithSinglePendingCronGuidesUser() {
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

        MessageContext ctx = createContext("/deny");
        ReflexResult result = denyReflex.execute("session-1", userId, "chat-1", "/deny", ctx);
        String resultText = result.commandResponse().get();

        String expected = "⚠️ No active denial in this chat session.\n\n" + "A background task is waiting:\n"
                + "• Backup databases\n\n"
                + "Use: `/deny cron-456` to cancel it.";
        assertEquals(expected, resultText);
    }

    @Test
    void testDenySessionCheckpointFindsArchitectRole() {
        String userId = "user-1";
        String sessionId = "session-1";
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setAgentRole("architect");

        when(statefulAgentStore.findLatestAwaitingApproval(eq(sessionId))).thenReturn(Optional.of(cp));

        MessageContext ctx = createContext("/deny");
        ReflexResult result = denyReflex.execute(sessionId, userId, "chat-1", "/deny", ctx);
        String resultText = result.commandResponse().get();

        assertEquals("", resultText);
        verify(checkpointApprovalService).denyAll(eq(sessionId), eq("CLI deny"));
    }

    @Test
    void testResumeGuardBlocksDuplicateCall() {
        String userId = "user-1";
        var cron = new AgentCronDto(
                "cron-123",
                null,
                userId,
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

        when(cronStore.findById("123")).thenReturn(Optional.of(cron));

        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("session-1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        when(statefulAgentStore.findLatestAwaitingApproval("cron-123")).thenReturn(Optional.empty());
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining("session-1"))
                .thenReturn(Optional.of(cp));
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining("cron-123"))
                .thenReturn(Optional.empty());

        when(checkpointApprovalService.denyAll(anyString(), anyString())).thenReturn(cp);

        // MOCK THE GUARD TO FAIL
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(false);

        MessageContext ctx = createContext("/deny cron-123");
        ReflexResult result = denyReflex.execute("session-1", userId, "chat-1", "/deny cron-123", ctx);

        assertEquals(
                "⏳ Already processing your denial. Please wait.",
                result.commandResponse().get());
        assertFalse(ctx.isResumeSignal());
    }

    @Test
    void testAmbiguityWarningWhenBothSessionAndCronPending() {
        // With a live session pending, bare /deny always resolves to the live session.
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

        MessageContext ctx = createContext("/deny");
        ReflexResult result = denyReflex.execute("session-1", userId, "chat-1", "/deny", ctx);
        String resultText = result.commandResponse().get();

        // Live session is denied directly — no ambiguity prompt
        assertEquals("", resultText);
        verify(checkpointApprovalService).denyAll(eq("session-1"), eq("CLI deny"));
    }

    @Test
    void testCronUuidResolutionWithPrefix() throws Exception {
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

        when(checkpointApprovalService.denyAll(eq(fullId), anyString())).thenReturn(cp);
        when(cronStore.findById(jobId)).thenReturn(Optional.of(cron));
        when(agentLoop.run(
                        anyString(), anyString(), eq(AgentSentinels.GOAL_DENY_APPROVED), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("Denied", List.of()));

        MessageContext ctx = createContext("/deny " + fullId);
        denyReflex.execute("session-1", "user-1", "chat-1", "/deny " + fullId, ctx);

        verify(cronStore).findById(jobId);
    }

    @Test
    void testPerToolDenialFeedback() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"" + ToolCapability.GITHUB.toolName() + "\":\"PENDING\"}");
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));

        MessageContext ctx = createContext("/deny github");
        ReflexResult result = denyReflex.execute("session-1", "user-1", "chat-1", "/deny github", ctx);

        assertTrue(result.commandResponse().get().contains(ToolCapability.GITHUB.toolName()));
        assertTrue(result.commandResponse().get().contains("denied"));
    }
}
