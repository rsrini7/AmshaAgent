package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.domain.model.AgentCronRecord;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for {@link AbstractDecisionReflex}.
 * Uses a concrete test subclass to test real method logic.
 */
class AbstractDecisionReflexDeepTest {

    private TestDecisionReflex reflex;
    private StatefulAgentStore statefulAgentStore;
    private CronStorePort cronStore;
    private AuditService auditService;
    private CheckpointResumeHandler checkpointResumeHandler;
    private CheckpointApprovalService checkpointApprovalService;
    private DelegateApprovalGuard delegateGuard;
    private CronDecisionRouter cronDecisionRouter;
    private ResumeResultHelper resumeResultHelper;
    private ApprovalNotificationService notificationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        statefulAgentStore = mock(StatefulAgentStore.class);
        cronStore = mock(CronStorePort.class);
        auditService = mock(AuditService.class);
        checkpointResumeHandler = mock(CheckpointResumeHandler.class);
        checkpointApprovalService = mock(CheckpointApprovalService.class);
        delegateGuard = mock(DelegateApprovalGuard.class);
        cronDecisionRouter = mock(CronDecisionRouter.class);
        resumeResultHelper = mock(ResumeResultHelper.class);
        notificationService = mock(ApprovalNotificationService.class);
        objectMapper = new ObjectMapper();

        reflex = new TestDecisionReflex();
        reflex.log = mock(Logger.class);
        reflex.statefulAgentStore = statefulAgentStore;
        reflex.cronStore = cronStore;
        reflex.auditService = auditService;
        reflex.checkpointResumeHandler = checkpointResumeHandler;
        reflex.checkpointApprovalService = checkpointApprovalService;
        reflex.delegateGuard = delegateGuard;
        reflex.cronDecisionRouter = cronDecisionRouter;
        reflex.resumeResultHelper = resumeResultHelper;
        reflex.notificationService = notificationService;
        reflex.objectMapper = objectMapper;

        lenient().doNothing().when(notificationService).flushPendingNotifications(anyString());
    }

    // ── extractDecisionParams ────────────────────────────────────────────

    @Test
    void testExtractDecisionParams_approveCommand() throws Exception {
        ChannelCommand.Approve approve =
                new ChannelCommand.Approve(Optional.of("target-1"), Optional.of("tool1"), Optional.of("because"));
        DecisionParams result = invokeExtractDecisionParams(approve);
        assertNotNull(result);
        assertEquals(Optional.of("target-1"), result.targetId());
        assertEquals(Optional.of("tool1"), result.toolName());
        assertEquals(Optional.of("because"), result.reasonOpt());
    }

    @Test
    void testExtractDecisionParams_denyCommand() throws Exception {
        ChannelCommand.Deny deny = new ChannelCommand.Deny(Optional.of("target-2"), Optional.empty(), Optional.empty());
        DecisionParams result = invokeExtractDecisionParams(deny);
        assertNotNull(result);
        assertEquals(Optional.of("target-2"), result.targetId());
    }

    @Test
    void testExtractDecisionParams_unknownCommand() throws Exception {
        ChannelCommand.Status status = new ChannelCommand.Status();
        DecisionParams result = invokeExtractDecisionParams(status);
        assertNull(result);
    }

    // ── resolveNumericTargetIndex ────────────────────────────────────────

    @Test
    void testResolveNumericTargetIndex_nonNumeric_returnsAsIs() throws Exception {
        String result = invokeResolveNumericTargetIndex("abc-123", "session-1", "user-1");
        assertEquals("abc-123", result);
    }

    @Test
    void testResolveNumericTargetIndex_index1_withPending_returnsSession() throws Exception {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of());

        String result = invokeResolveNumericTargetIndex("1", "session-1", "user-1");
        assertEquals("session-1", result);
    }

    @Test
    void testResolveNumericTargetIndex_index2_withPendingAndCron_returnsCron() throws Exception {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.of(cp));

        AgentCronDto cronDto = new AgentCronDto(
                "cron-id-1",
                "cron-session",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of(cronDto));

        String result = invokeResolveNumericTargetIndex("2", "session-1", "user-1");
        assertEquals("cron-id-1", result);
    }

    @Test
    void testResolveNumericTargetIndex_indexOutOfRange_returnsOriginal() throws Exception {
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.empty());
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of());

        String result = invokeResolveNumericTargetIndex("5", "session-1", "user-1");
        assertEquals("5", result);
    }

    @Test
    void testResolveNumericTargetIndex_noPending_index1_withCron_returnsCron() throws Exception {
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.empty());

        AgentCronDto cronDto = new AgentCronDto(
                "cron-id-2",
                "cron-session-2",
                "user-1",
                "chat-1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of(cronDto));

        String result = invokeResolveNumericTargetIndex("1", "session-1", "user-1");
        assertEquals("cron-id-2", result);
    }

    @Test
    void testResolveNumericTargetIndex_noPending_noCron_returnsOriginal() throws Exception {
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.empty());
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of());

        String result = invokeResolveNumericTargetIndex("3", "session-1", "user-1");
        assertEquals("3", result);
    }

    // ── execute method ───────────────────────────────────────────────────

    private MessageContext createCtx() {
        ChannelUser user = new ChannelUser("user-1", "chat-1", "Test User", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "chat-1", Instant.now());
        return new MessageContext("chat-1", user, msg);
    }

    @Test
    void testExecute_nonApproveDenyCommand_returnsForward() {
        MessageContext ctx = createCtx();
        ReflexResult result = reflex.execute("session-1", "user-1", "chat-1", "status", ctx);
        assertNotNull(result);
        // Should forward (not a decision command)
        assertTrue(result.commandResponse().isEmpty());
    }

    @Test
    void testExecute_approveCommand_noPending_returnsNoPending() {
        when(statefulAgentStore.findLatestAwaitingApproval("session-1")).thenReturn(Optional.empty());
        when(cronStore.findAwaitingApprovalByUserId("user-1")).thenReturn(List.of());
        when(statefulAgentStore.isSessionActive("session-1")).thenReturn(false);
        when(resumeResultHelper.pollBridge("session-1")).thenReturn(null);

        MessageContext ctx = createCtx();
        ReflexResult result = reflex.execute("session-1", "user-1", "chat-1", "/approve", ctx);
        assertNotNull(result);
    }

    // ── AmbiguityResolver ────────────────────────────────────────────────

    @Test
    void testAmbiguityResolver_noPending_noCrons_returnsNull() {
        AmbiguityResolver resolver = new AmbiguityResolver("approve", "Multiple approvals pending");
        String result = resolver.detect(Optional.empty(), List.of());
        assertNull(result);
    }

    @Test
    void testAmbiguityResolver_hasPending_returnsNull() {
        AmbiguityResolver resolver = new AmbiguityResolver("approve", "Multiple approvals pending");
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        String result = resolver.detect(Optional.of(cp), List.of());
        assertNull(result);
    }

    @Test
    void testAmbiguityResolver_multipleCrons_returnsAmbiguityMessage() {
        AmbiguityResolver resolver = new AmbiguityResolver("approve", "Multiple approvals pending");
        AgentCronDto cron1 = new AgentCronDto(
                "cron-1",
                "s1",
                "u1",
                "c1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal1",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        AgentCronDto cron2 = new AgentCronDto(
                "cron-2",
                "s2",
                "u1",
                "c1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal2",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        List<AgentCron> crons = List.of(AgentCronRecord.fromDto(cron1), AgentCronRecord.fromDto(cron2));

        String result = resolver.detect(Optional.empty(), crons);
        assertNotNull(result);
        assertTrue(result.contains("approve"));
        assertTrue(result.contains("goal1"));
        assertTrue(result.contains("goal2"));
    }

    @Test
    void testAmbiguityResolver_singleCron_returnsNull() {
        AmbiguityResolver resolver = new AmbiguityResolver("approve", "Multiple approvals pending");
        AgentCronDto cron1 = new AgentCronDto(
                "cron-1",
                "s1",
                "u1",
                "c1",
                null,
                null,
                "0 9 * * 1",
                "UTC",
                true,
                "goal1",
                null,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                null,
                null,
                null);
        List<AgentCron> crons = List.of(AgentCronRecord.fromDto(cron1));

        String result = resolver.detect(Optional.empty(), crons);
        assertNull(result);
    }

    // ── Helper methods ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private record DecisionParams(Optional<String> targetId, Optional<String> toolName, Optional<String> reasonOpt) {}

    private DecisionParams invokeExtractDecisionParams(ChannelCommand cmd) throws Exception {
        Method m = AbstractDecisionReflex.class.getDeclaredMethod("extractDecisionParams", ChannelCommand.class);
        m.setAccessible(true);
        Object result = m.invoke(reflex, cmd);
        if (result == null) return null;
        // Extract fields from the record via reflection
        Method targetIdM = result.getClass().getMethod("targetId");
        Method toolNameM = result.getClass().getMethod("toolName");
        Method reasonOptM = result.getClass().getMethod("reasonOpt");
        return new DecisionParams(
                (Optional<String>) targetIdM.invoke(result),
                (Optional<String>) toolNameM.invoke(result),
                (Optional<String>) reasonOptM.invoke(result));
    }

    private String invokeResolveNumericTargetIndex(String targetId, String sessionId, String userId) throws Exception {
        Method m = AbstractDecisionReflex.class.getDeclaredMethod(
                "resolveNumericTargetIndex", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(reflex, targetId, sessionId, userId);
    }

    /**
     * Concrete test subclass that implements all abstract methods.
     */
    private static class TestDecisionReflex extends AbstractDecisionReflex {

        @Override
        public boolean matches(String input) {
            return input != null && (input.startsWith("/approve") || input.startsWith("/deny"));
        }

        @Override
        protected String getActionName() {
            return "approve";
        }

        @Override
        protected String getAmbiguityTitle() {
            return "Multiple approvals pending";
        }

        @Override
        protected String getNoPendingMessage() {
            return "No pending approval found.";
        }

        @Override
        protected String getErrorMessage() {
            return "Approval error";
        }

        @Override
        protected String getNotAuthorizedMessage() {
            return "Not authorized";
        }

        @Override
        protected String getTargetNotAwaitingMessage() {
            return "Target not awaiting";
        }

        @Override
        protected AgentCheckpoint applyDecisionToTool(String sessionId, String toolName, String reason) {
            return null;
        }

        @Override
        protected AgentCheckpoint applyDecisionToAll(String sessionIdOrCronId, String reason) {
            return null;
        }

        @Override
        protected void postProcessCronDecision(AgentCron cron, String reason, AgentResult result) {}

        @Override
        protected String getCronResumeGoal(String cronSessionId, String cronRole) {
            return "resume goal";
        }

        @Override
        protected String getAlreadyProcessingMessage() {
            return "Already processing";
        }

        @Override
        protected String getSingleCronFallbackMessage(AgentCron cron) {
            return "Single cron: " + cron.getGoal();
        }

        @Override
        protected String getMultipleCronFallbackMessage(String list) {
            return "Multiple crons:\n" + list;
        }

        @Override
        protected String getCronSuccessResponse(AgentResult result) {
            return "Cron approved";
        }

        @Override
        protected String getFailedRetryMessage() {
            return "Retry failed";
        }

        @Override
        protected String getPerToolSuccessResponse(String toolName) {
            return "Tool approved: " + toolName;
        }
    }
}
