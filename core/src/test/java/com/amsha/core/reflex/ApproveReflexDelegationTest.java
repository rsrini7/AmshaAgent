package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.audit.AuditService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

public class ApproveReflexDelegationTest {

    @Mock
    CheckpointApprovalService checkpointApprovalService;

    @Mock
    StatefulAgentStore statefulAgentStore;

    @Mock
    CronStorePort cronStore;

    @Mock
    CheckpointResumeHandler checkpointResumeHandler;

    @Mock
    ResumeResultHelper resumeResultHelper;

    @Mock
    DelegateApprovalGuard delegateGuard;

    @Mock
    ApprovalNotificationService notificationService;

    @Mock
    AuditService auditService;

    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    ApproveReflex approveReflex;

    private MockedStatic<WorkspaceScopeHolder> mockedScope;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MockitoAnnotations.openMocks(this);
        approveReflex.log = Logger.getLogger("test");
        approveReflex.objectMapper = objectMapper;
        lenient().when(notificationService.createJobNotifier()).thenReturn((cron, result) -> {});

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

    @Test
    void test_bulkApprove_delegatesToCheckpointApprovalService() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\", \"serper\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        AgentCheckpoint resolved = new AgentCheckpointRecord();
        resolved.setSessionId("s1");
        resolved.setAgentRole("coordinator");
        resolved.setBatchToolDecisions("{\"github\":\"APPROVED\", \"serper\":\"APPROVED\"}");
        when(checkpointApprovalService.approveAll("s1", "CLI approve")).thenReturn(resolved);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "u1", "u1", ChannelType.TELEGRAM);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve", context);

        verify(checkpointApprovalService).approveAll("s1", "CLI approve");
        assertNotNull(result);
        assertEquals("", result.commandResponse().orElse(null));
    }

    @Test
    void test_delegatedApprove_doesNotResumeWhenDecisionIsNotTerminal() {
        AgentCheckpoint pending = new AgentCheckpointRecord();
        pending.setSessionId("remote-session");
        pending.setAgentRole("coordinator");
        pending.setBatchToolDecisions("{\"github\":\"PENDING\", \"serper\":\"PENDING\"}");

        AgentCheckpoint partiallyResolved = new AgentCheckpointRecord();
        partiallyResolved.setSessionId("remote-session");
        partiallyResolved.setAgentRole("coordinator");
        partiallyResolved.setBatchToolDecisions("{\"github\":\"APPROVED\", \"serper\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("remote-session")).thenReturn(Optional.of(pending));
        when(delegateGuard.isDelegate("u1")).thenReturn(true);
        when(checkpointApprovalService.approveAll("remote-session", "CLI approve"))
                .thenReturn(partiallyResolved);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "u1", "u1", ChannelType.TELEGRAM);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve remote-session", context);

        verify(checkpointApprovalService).approveAll("remote-session", "CLI approve");
        verify(checkpointResumeHandler, never())
                .resumeAfterBatchDecision(anyString(), anyString(), anyString(), anyString(), anyString());
        assertNotNull(result);
    }

    @Test
    void test_delegatedApprove_doesNotResumeWhenDecisionJsonIsMalformed() {
        AgentCheckpoint pending = new AgentCheckpointRecord();
        pending.setSessionId("remote-session");
        pending.setAgentRole("coordinator");
        pending.setBatchToolDecisions("{\"github\":\"PENDING\", \"serper\":\"PENDING\"}");

        AgentCheckpoint malformed = new AgentCheckpointRecord();
        malformed.setSessionId("remote-session");
        malformed.setAgentRole("coordinator");
        malformed.setBatchToolDecisions("{\"github\":\"APPROVED\"");

        when(statefulAgentStore.findLatestAwaitingApproval("remote-session")).thenReturn(Optional.of(pending));
        when(delegateGuard.isDelegate("u1")).thenReturn(true);
        when(checkpointApprovalService.approveAll("remote-session", "CLI approve"))
                .thenReturn(malformed);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "u1", "u1", ChannelType.TELEGRAM);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve remote-session", context);

        verify(checkpointApprovalService).approveAll("remote-session", "CLI approve");
        verify(checkpointResumeHandler, never())
                .resumeAfterBatchDecision(anyString(), anyString(), anyString(), anyString(), anyString());
        assertNotNull(result);
    }

    @Test
    void test_perToolApprove_delegatesToCheckpointApprovalService() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "u1", "u1", ChannelType.TELEGRAM);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve github", context);

        verify(checkpointApprovalService).approveTool("s1", ToolCapability.GITHUB.toolName(), "CLI approve");
        assertNotNull(result);
    }

    @Test
    void test_bareApprove_resolvesChildDelegatedSession() {
        String childSessionId = "s1:delegate:brahma:1787031717243";
        AgentCheckpoint childCp = new AgentCheckpointRecord();
        childCp.setSessionId(childSessionId);
        childCp.setAgentRole("coder");
        childCp.setPendingToolName("sandbox_exec");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining("s1"))
                .thenReturn(Optional.of(childCp));
        AgentCheckpoint resolved = new AgentCheckpointRecord();
        resolved.setSessionId(childSessionId);
        resolved.setAgentRole("coder");
        resolved.setBatchToolDecisions("{\"sandbox_exec\":\"APPROVED\"}");
        when(checkpointApprovalService.approveAll(childSessionId, "CLI approve"))
                .thenReturn(resolved);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "u1", "u1", ChannelType.CLI);
        when(context.getUser()).thenReturn(user);
        when(resumeResultHelper.runSync(childSessionId, "coder", "u1", "c1")).thenReturn("Executed star pattern");

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve", context);

        verify(checkpointApprovalService).approveAll(childSessionId, "CLI approve");
        verify(resumeResultHelper).runSync(childSessionId, "coder", "u1", "c1");
        assertNotNull(result);
        assertEquals("Executed star pattern", result.commandResponse().orElse(null));
    }

    @Test
    void test_explicitApprove_resolvesChildDelegatedSessionWithoutDelegateRole() {
        String childSessionId = "s1:delegate:brahma:1787031717243";
        AgentCheckpoint childCp = new AgentCheckpointRecord();
        childCp.setSessionId(childSessionId);
        childCp.setAgentRole("coder");
        childCp.setPendingToolName("sandbox_exec");

        when(statefulAgentStore.findLatestAwaitingApproval(childSessionId)).thenReturn(Optional.of(childCp));
        AgentCheckpoint resolved = new AgentCheckpointRecord();
        resolved.setSessionId(childSessionId);
        resolved.setAgentRole("coder");
        resolved.setBatchToolDecisions("{\"sandbox_exec\":\"APPROVED\"}");
        when(checkpointApprovalService.approveAll(childSessionId, "CLI approve"))
                .thenReturn(resolved);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "u1", "u1", ChannelType.CLI);
        when(context.getUser()).thenReturn(user);
        when(resumeResultHelper.runSync(childSessionId, "coder", "u1", "c1")).thenReturn("Executed star pattern");

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve " + childSessionId, context);

        verify(checkpointApprovalService).approveAll(childSessionId, "CLI approve");
        verify(resumeResultHelper).runSync(childSessionId, "coder", "u1", "c1");
        assertNotNull(result);
        assertEquals("Executed star pattern", result.commandResponse().orElse(null));
    }

    @Test
    void test_approve_delegatedCronSession_fromParentSessionId_resumesChildSession() {
        String parentCronSid = "cron-dddb8755-308c-46a5-961c-955f47a04b24-78d267c1-66ff-4e09-a65d-cd8af57e7ae6";
        String childSessionId = parentCronSid + ":delegate:brahma:1789104655323";
        String cronJobId = "dddb8755-308c-46a5-961c-955f47a04b24";

        AgentCheckpoint childCp = new AgentCheckpointRecord();
        childCp.setSessionId(childSessionId);
        childCp.setAgentRole("coder");
        childCp.setPendingToolName("sandbox_exec");

        when(statefulAgentStore.findLatestAwaitingApproval(parentCronSid)).thenReturn(Optional.empty());
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(parentCronSid))
                .thenReturn(Optional.of(childCp));
        when(statefulAgentStore.findLatestAwaitingApproval(childSessionId)).thenReturn(Optional.of(childCp));

        AgentCronDto cronDto = new AgentCronDto(
                cronJobId,
                parentCronSid,
                "u1",
                "c1",
                "CLI",
                "u1",
                "* * * * *",
                "UTC",
                false,
                "Compute factorial",
                null,
                null,
                "AWAITING_APPROVAL",
                "researcher",
                null,
                "78d267c1-66ff-4e09-a65d-cd8af57e7ae6",
                Instant.now(),
                Instant.now());
        when(cronStore.findById(cronJobId)).thenReturn(Optional.of(cronDto));

        AgentCheckpoint resolved = new AgentCheckpointRecord();
        resolved.setSessionId(childSessionId);
        resolved.setAgentRole("coder");
        resolved.setBatchToolDecisions("{\"sandbox_exec\":\"APPROVED\"}");
        when(checkpointApprovalService.approveTool(childSessionId, "sandbox_exec", "CLI approve"))
                .thenReturn(resolved);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "c1", "u1", ChannelType.CLI);
        when(context.getUser()).thenReturn(user);

        ReflexResult result =
                approveReflex.execute("cli-chat-session", "u1", "c1", "/approve " + parentCronSid, context);

        verify(checkpointApprovalService).approveTool(childSessionId, "sandbox_exec", "CLI approve");
        verify(checkpointResumeHandler).resumeAfterBatchDecision(childSessionId, "coder", "u1", "c1", "CLI");
        assertNotNull(result);
        assertEquals(
                AbstractDecisionReflex.MSG_RESUMING_TASK,
                result.commandResponse().orElse(null));
    }

    @Test
    void test_approve_delegatedCronSession_fromJobId_resumesChildSession() {
        String cronJobId = "dddb8755-308c-46a5-961c-955f47a04b24";
        String parentCronSid = "cron-" + cronJobId + "-78d267c1-66ff-4e09-a65d-cd8af57e7ae6";
        String childSessionId = parentCronSid + ":delegate:brahma:1789104655323";

        AgentCheckpoint childCp = new AgentCheckpointRecord();
        childCp.setSessionId(childSessionId);
        childCp.setAgentRole("coder");
        childCp.setPendingToolName("sandbox_exec");

        when(statefulAgentStore.findLatestAwaitingApproval(cronJobId)).thenReturn(Optional.empty());
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(cronJobId))
                .thenReturn(Optional.of(childCp));
        when(statefulAgentStore.findLatestAwaitingApproval(childSessionId)).thenReturn(Optional.of(childCp));

        AgentCronDto cronDto = new AgentCronDto(
                cronJobId,
                parentCronSid,
                "u1",
                "c1",
                "CLI",
                "u1",
                "* * * * *",
                "UTC",
                false,
                "Compute factorial",
                null,
                null,
                "AWAITING_APPROVAL",
                "researcher",
                null,
                "78d267c1-66ff-4e09-a65d-cd8af57e7ae6",
                Instant.now(),
                Instant.now());
        when(cronStore.findById(cronJobId)).thenReturn(Optional.of(cronDto));

        AgentCheckpoint resolved = new AgentCheckpointRecord();
        resolved.setSessionId(childSessionId);
        resolved.setAgentRole("coder");
        resolved.setBatchToolDecisions("{\"sandbox_exec\":\"APPROVED\"}");
        when(checkpointApprovalService.approveTool(childSessionId, "sandbox_exec", "CLI approve"))
                .thenReturn(resolved);

        MessageContext context = mock(MessageContext.class);
        ChannelUser user = new ChannelUser("u1", "c1", "u1", ChannelType.CLI);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("cli-chat-session", "u1", "c1", "/approve " + cronJobId, context);

        verify(checkpointApprovalService).approveTool(childSessionId, "sandbox_exec", "CLI approve");
        verify(checkpointResumeHandler).resumeAfterBatchDecision(childSessionId, "coder", "u1", "c1", "CLI");
        assertNotNull(result);
        assertEquals(
                AbstractDecisionReflex.MSG_RESUMING_TASK,
                result.commandResponse().orElse(null));
    }
}
