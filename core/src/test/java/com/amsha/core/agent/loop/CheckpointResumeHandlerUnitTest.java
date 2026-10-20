package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.speech.SpeechDeliveryService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckpointResumeHandlerUnitTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ChannelPluginRegistry channelPluginRegistry;

    @Mock
    CronStorePort cronStore;

    @Mock
    CronRunStorePort cronRunStore;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    @Mock
    AgentStateMachineRunner stateMachineRunner;

    @Mock
    ManagedExecutor executor;

    @Mock
    SpeechDeliveryService speechDeliveryService;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter resumeFailureCounter;

    CheckpointResumeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CheckpointResumeHandler(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                reasoningEngine,
                channelPluginRegistry,
                cronStore,
                cronRunStore,
                cliResultBridge,
                recoveryService,
                escalationService);
        handler.executor = executor;
        handler.speechDeliveryService = speechDeliveryService;
        handler.meterRegistry = meterRegistry;
        when(meterRegistry.counter(anyString())).thenReturn(resumeFailureCounter);
    }

    /** A well-formed cron session id: "cron-" + 36-char job id + "-" + execution id. */
    private static final String CRON_SESSION = "cron-12345678-1234-1234-1234-123456789012-session";

    private static ChannelUser user(ChannelType type) {
        return new ChannelUser("u1", "c1", "Operator", type);
    }

    private <T> T invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = CheckpointResumeHandler.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        T result = (T) method.invoke(handler, args);
        return result;
    }

    @Test
    void testHandleNoResumeSignal() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(false);

        AgentExecutionContext execCtx = mock(AgentExecutionContext.class);
        AgentResult result = handler.handle(execCtx, List.of(), ctx, stateMachineRunner);
        assertNull(result);
    }

    @Test
    void testHandleNoCheckpoint() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(true);
        doReturn(Optional.empty()).when(agentStore).load(any(), any());

        AgentExecutionContext execCtx = mock(AgentExecutionContext.class);
        when(execCtx.sessionId()).thenReturn("sess-1");
        when(execCtx.agentRole()).thenReturn("role-1");

        AgentResult result = handler.handle(execCtx, List.of(), ctx, stateMachineRunner);
        assertNull(result);
    }

    @Test
    void testHandleWrongStatus() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(true);

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        doReturn(Optional.of(cp)).when(agentStore).load("sess-1", "role-1");

        AgentExecutionContext execCtx = mock(AgentExecutionContext.class);
        when(execCtx.sessionId()).thenReturn("sess-1");
        when(execCtx.agentRole()).thenReturn("role-1");

        AgentResult result = handler.handle(execCtx, List.of(), ctx, stateMachineRunner);
        assertNull(result);
    }

    @Test
    void testResumeAfterBatchDecisionClaimFails() {
        when(agentStore.claimForResume("s-1", "r-1")).thenReturn(false);
        assertDoesNotThrow(() -> handler.resumeAfterBatchDecision("s-1", "r-1", "u-1", "c-1", "cli"));
    }

    @Test
    void testResumeAfterBatchDecisionNullCheckpoint() {
        when(agentStore.claimForResume("s-2", "r-1")).thenReturn(true);
        doReturn(Optional.empty()).when(agentStore).load("s-2", "r-1");
        assertDoesNotThrow(() -> handler.resumeAfterBatchDecision("s-2", "r-1", "u-1", "c-1", "cli"));
        verify(agentStore).releaseResumeClaim("s-2", "r-1");
    }

    @Test
    void testResumeAfterBatchDecisionExecutorRejectedReleasesClaim() {
        when(agentStore.claimForResume("s-rejected", "r-1")).thenReturn(true);
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        doReturn(Optional.of(checkpoint)).when(agentStore).load("s-rejected", "r-1");
        doThrow(new RejectedExecutionException("executor is closed"))
                .when(executor)
                .execute(any(Runnable.class));

        assertFalse(handler.resumeAfterBatchDecision("s-rejected", "r-1", "u-1", "c-1", "cli"));

        verify(agentStore).releaseResumeClaim("s-rejected", "r-1");
        verify(agentStore).clearBatchResuming("s-rejected");
    }

    @Test
    void testResumeAfterBatchDecisionSyncGuards() throws Exception {
        when(agentStore.claimForResume("s-claimed", "r1")).thenReturn(false);
        assertNull(handler.resumeAfterBatchDecisionSync("s-claimed", "r1", "u1", "c1", "cli"));

        when(agentStore.claimForResume("s-nocp", "r1")).thenReturn(true);
        doReturn(Optional.empty()).when(agentStore).load("s-nocp", "r1");
        assertNull(handler.resumeAfterBatchDecisionSync("s-nocp", "r1", "u1", "c1", "cli"));
        verify(agentStore).clearBatchResuming("s-nocp");
    }

    @Test
    void testResumeAfterBatchDecisionSyncSuccess() throws Exception {
        when(agentStore.claimForResume("s-valid", "r1")).thenReturn(true);
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn("s-valid");
        when(cp.getOriginalGoal()).thenReturn("resumed goal");
        when(cp.getMaxIterations()).thenReturn(10);
        when(cp.getIterationsUsed()).thenReturn(2);
        doReturn(Optional.of(cp)).when(agentStore).load(any(), any());

        AgentResult expected = AgentResult.finished("done", List.of());
        when(agentLoop.run(eq("s-valid"), eq("r1"), any(), any(), eq(reasoningEngine), eq(8), any()))
                .thenReturn(expected);

        AgentResult actual = handler.resumeAfterBatchDecisionSync("s-valid", "r1", "u1", "c1", "cli");
        assertEquals(expected, actual);
        verify(agentStore).clearBatchResuming("s-valid");
    }

    @Test
    void testResolveResumeGoal() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getOriginalGoal()).thenReturn("original-goal");
        doReturn(Optional.of(cp)).when(agentStore).load("s-1", "r-1");

        String goal = handler.resolveResumeGoal("s-1", "r-1");
        assertEquals("original-goal", goal);
    }

    @Test
    void testDispatchResumeResultWebsocket() throws Exception {
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        ChannelSender sender = mock(ChannelSender.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("websocket")).thenReturn(Optional.of(plugin));

        ChannelUser user = new ChannelUser("u1", "c1", "Operator", ChannelType.WEBSOCKET);
        Method dispatch = CheckpointResumeHandler.class.getDeclaredMethod(
                "dispatchResumeResult",
                MessageContext.class,
                AgentResult.class,
                ChannelType.class,
                ChannelUser.class,
                String.class,
                String.class);
        dispatch.setAccessible(true);

        AgentResult res = AgentResult.finished("resumed answer", List.of());
        dispatch.invoke(handler, null, res, ChannelType.WEBSOCKET, user, "sess-ws", "tool_x");

        verify(speechDeliveryService).deliver(isNull(), eq(sender), eq(user), eq(res), contains("Approved"));
    }

    @Test
    void testBuildToolInfoPart() throws Exception {
        Method buildToolInfoPart =
                CheckpointResumeHandler.class.getDeclaredMethod("buildToolInfoPart", AgentCheckpoint.class);
        buildToolInfoPart.setAccessible(true);

        assertNull(buildToolInfoPart.invoke(handler, (Object) null));

        AgentCheckpoint cpSingle = mock(AgentCheckpoint.class);
        when(cpSingle.getPendingToolName()).thenReturn("web_search");
        when(cpSingle.getPendingToolParametersJson()).thenReturn("{\"query\":\"test\"}");

        String infoSingle = (String) buildToolInfoPart.invoke(handler, cpSingle);
        assertTrue(infoSingle.contains("web_search"));
        assertTrue(infoSingle.contains("query: test"));

        AgentCheckpoint cpParallel = mock(AgentCheckpoint.class);
        when(cpParallel.isParallelBatch()).thenReturn(true);
        when(cpParallel.getPendingToolName())
                .thenReturn(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "tool1,tool1,tool2");

        String infoParallel = (String) buildToolInfoPart.invoke(handler, cpParallel);
        assertTrue(infoParallel.contains("tool1 (2x)"));
        assertTrue(infoParallel.contains("tool2"));
    }

    @Test
    void testSyncCronStatusAndTerminal() throws Exception {
        Method syncCronStatus = CheckpointResumeHandler.class.getDeclaredMethod("syncCronStatus", String.class);
        syncCronStatus.setAccessible(true);

        String cronSessionId = "cron-12345678-1234-1234-1234-123456789012-session";
        when(cronStore.claimForResume("12345678-1234-1234-1234-123456789012", "session"))
                .thenReturn(true);
        syncCronStatus.invoke(handler, cronSessionId);
        verify(cronStore).claimForResume("12345678-1234-1234-1234-123456789012", "session");

        Method syncCronTerminalStatus = CheckpointResumeHandler.class.getDeclaredMethod(
                "syncCronTerminalStatus", String.class, AgentResult.class);
        syncCronTerminalStatus.setAccessible(true);

        AgentCronDto cronDto = new AgentCronDto(
                "12345678-1234-1234-1234-123456789012",
                "sess-1",
                "usr-1",
                "chat-1",
                "cli",
                "cli",
                "* * * * *",
                "UTC",
                true,
                "prompt",
                Instant.now(),
                null,
                CronStatus.RUNNING.name(),
                null,
                null,
                "session",
                Instant.now(),
                Instant.now());
        when(cronStore.findById("12345678-1234-1234-1234-123456789012")).thenReturn(Optional.of(cronDto));
        when(cronStore.markCompletedIfOwner(eq("12345678-1234-1234-1234-123456789012"), eq("session"), any()))
                .thenReturn(true);

        var pendingRun = new CronRunDto(
                "session",
                "12345678-1234-1234-1234-123456789012",
                cronSessionId,
                "AWAITING_APPROVAL",
                Instant.now(),
                null,
                null,
                1,
                0,
                0,
                BigDecimal.ZERO,
                Instant.now());
        when(cronRunStore.findById("session")).thenReturn(Optional.of(pendingRun));

        syncCronTerminalStatus.invoke(handler, cronSessionId, AgentResult.finished("done", List.of()));
        verify(cronStore).markCompletedIfOwner(eq("12345678-1234-1234-1234-123456789012"), eq("session"), any());
        verify(cronRunStore).save(argThat(run -> "COMPLETED".equals(run.status())));
    }

    @Test
    void testDispatchResumeResultAudioOnlyDeliversAudio() {
        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser user = new ChannelUser("u1", "c1", "User 1", ChannelType.TELEGRAM);
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getGoal()).thenReturn("what is latest quarkus lts version");
        when(ctx.getMessage()).thenReturn(null);

        AgentResult result = AgentResult.finished("The latest Quarkus LTS version is 3.15", List.of());
        handler.dispatchResumeResult(ctx, result, ChannelType.TELEGRAM, user, "s-1", "garuda");

        verify(speechDeliveryService).deliver(eq(ctx), eq(sender), eq(user), eq(result), contains("3.15"));
    }

    @Test
    void testDispatchResumeResultCronSessionDispatchesToCronNotifier() {
        ResumeCronNotificationDispatcher mockCronDispatcher = mock(ResumeCronNotificationDispatcher.class);
        handler.cronNotificationDispatcher = mockCronDispatcher;

        ChannelUser user = new ChannelUser("u1", "c1", "User 1", ChannelType.CLI);
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getGoal()).thenReturn("write python");

        String cronSessionId = "cron-12345678-1234-1234-1234-123456789012-session";
        AgentResult result = AgentResult.awaitingApproval("sandbox_exec", "{}", cronSessionId, List.of());

        handler.dispatchResumeResult(ctx, result, ChannelType.CLI, user, cronSessionId, "brahma");

        verify(mockCronDispatcher).dispatch(cronSessionId, result);
        verifyNoInteractions(speechDeliveryService);
    }

    // ================= cron-lease guards on both resume paths =================

    @Test
    void asyncResumeReleasesTheClaimWhenTheCronLeaseIsLost() {
        when(agentStore.claimForResume(CRON_SESSION, "r1")).thenReturn(true);
        doReturn(Optional.of(mock(AgentCheckpoint.class))).when(agentStore).load(CRON_SESSION, "r1");
        // claimForResume and renewLease both default to false -> syncRunning refuses the resume.
        when(cronStore.claimForResume("12345678-1234-1234-1234-123456789012", "session"))
                .thenReturn(false);

        assertFalse(handler.resumeAfterBatchDecision(CRON_SESSION, "r1", "u1", "c1", "cli"));

        verify(agentStore).releaseResumeClaim(CRON_SESSION, "r1");
        verify(agentStore).clearBatchResuming(CRON_SESSION);
        verifyNoInteractions(executor);
    }

    @Test
    void syncResumeReleasesTheClaimWhenTheCronLeaseIsLost() throws Exception {
        when(agentStore.claimForResume(CRON_SESSION, "r1")).thenReturn(true);
        when(cronStore.claimForResume("12345678-1234-1234-1234-123456789012", "session"))
                .thenReturn(false);

        assertNull(handler.resumeAfterBatchDecisionSync(CRON_SESSION, "r1", "u1", "c1", "cli"));

        verify(agentStore).releaseResumeClaim(CRON_SESSION, "r1");
    }

    @Test
    void asyncResumeReleasesTheClaimWhenDispatchThrows() {
        when(agentStore.claimForResume("s-throw", "r1")).thenReturn(true);
        doReturn(Optional.of(mock(AgentCheckpoint.class))).when(agentStore).load("s-throw", "r1");
        // A non-RejectedExecutionException must still release the claim.
        doThrow(new IllegalStateException("dispatch blew up")).when(executor).execute(any(Runnable.class));

        assertFalse(handler.resumeAfterBatchDecision("s-throw", "r1", "u1", "c1", "cli"));

        verify(agentStore).releaseResumeClaim("s-throw", "r1");
    }

    @Test
    void aFailingClaimReleaseIsSwallowed() {
        when(agentStore.claimForResume("s-rel", "r1")).thenReturn(true);
        doReturn(Optional.of(mock(AgentCheckpoint.class))).when(agentStore).load("s-rel", "r1");
        doThrow(new IllegalStateException("dispatch blew up")).when(executor).execute(any(Runnable.class));
        doThrow(new IllegalStateException("release failed")).when(agentStore).releaseResumeClaim("s-rel", "r1");

        // The cleanup itself must not mask the original dispatch failure.
        assertFalse(handler.resumeAfterBatchDecision("s-rel", "r1", "u1", "c1", "cli"));
    }

    // ================= resume budget =================

    @Test
    void resumeBudgetIsClampedBetweenThreeAndTheOriginalCap() throws Exception {
        AgentCheckpoint unset = mock(AgentCheckpoint.class);
        when(unset.getMaxIterations()).thenReturn(null);
        assertEquals(5, resumeBudget(unset));

        AgentCheckpoint partial = mock(AgentCheckpoint.class);
        when(partial.getMaxIterations()).thenReturn(10);
        when(partial.getIterationsUsed()).thenReturn(2);
        assertEquals(8, resumeBudget(partial));

        // Exhausted: remaining clamps to 0, so the floor of 3 applies (not 5).
        AgentCheckpoint exhausted = mock(AgentCheckpoint.class);
        when(exhausted.getMaxIterations()).thenReturn(3);
        when(exhausted.getIterationsUsed()).thenReturn(3);
        assertEquals(3, resumeBudget(exhausted));

        AgentCheckpoint nearly = mock(AgentCheckpoint.class);
        when(nearly.getMaxIterations()).thenReturn(20);
        when(nearly.getIterationsUsed()).thenReturn(19);
        assertEquals(3, resumeBudget(nearly));
    }

    /** Typed wrapper so {@code assertEquals(int, …)} is not ambiguous against the generic helper. */
    private int resumeBudget(AgentCheckpoint cp) throws Exception {
        Method method = CheckpointResumeHandler.class.getDeclaredMethod("calculateResumeBudget", AgentCheckpoint.class);
        method.setAccessible(true);
        return (int) method.invoke(handler, cp);
    }

    // ================= handleResumeFailure =================

    @Test
    void resumeFailureWithoutAContextOnlyCountsTheMetric() throws Exception {
        invokePrivate(
                "handleResumeFailure",
                new Class<?>[] {String.class, MessageContext.class, Exception.class},
                "s-1",
                null,
                new IllegalStateException("boom"));

        verify(resumeFailureCounter).increment();
        verifyNoInteractions(channelPluginRegistry);
    }

    @Test
    void resumeFailureNotifiesTheChannelWhenASenderExists() throws Exception {
        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser u = user(ChannelType.TELEGRAM);
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getUser()).thenReturn(u);

        invokePrivate(
                "handleResumeFailure",
                new Class<?>[] {String.class, MessageContext.class, Exception.class},
                "s-1",
                ctx,
                new IllegalStateException("boom"));

        verify(resumeFailureCounter).increment();
        verify(sender).sendRaw(eq(u), contains("Resume failed"));
    }

    @Test
    void resumeFailureWithoutASenderIsSilent() throws Exception {
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.empty());

        ChannelUser u = user(ChannelType.TELEGRAM);
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getUser()).thenReturn(u);

        invokePrivate(
                "handleResumeFailure",
                new Class<?>[] {String.class, MessageContext.class, Exception.class},
                "s-1",
                ctx,
                new IllegalStateException("boom"));

        verify(resumeFailureCounter).increment();
    }

    // ================= prepareResumeMessageContext =================

    @Test
    void resumeContextIsBuiltForEveryChannelType() throws Exception {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        List<ChannelType> types = List.of(
                ChannelType.TELEGRAM,
                ChannelType.DISCORD,
                ChannelType.REST_API,
                ChannelType.CLI,
                ChannelType.WEBSOCKET,
                ChannelType.of("matrix"));

        for (ChannelType type : types) {
            MessageContext ctx = invokePrivate(
                    "prepareResumeMessageContext",
                    new Class<?>[] {
                        String.class, ChannelUser.class, ChannelType.class, String.class, AgentCheckpoint.class
                    },
                    "c1",
                    user(type),
                    type,
                    "resume goal",
                    cp);

            assertTrue(ctx.isResumeSignal(), "resume flag must be set for " + type.id());
            assertEquals("resume goal", ctx.getGoal());
            assertEquals("c1", ctx.getChatId());
        }
    }

    @Test
    void resumeContextRestoresStoredContextData() throws Exception {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getContextDataJson()).thenReturn("{\"k\":\"v\",\"n\":7}");

        MessageContext ctx = invokePrivate(
                "prepareResumeMessageContext",
                new Class<?>[] {String.class, ChannelUser.class, ChannelType.class, String.class, AgentCheckpoint.class
                },
                "c1",
                user(ChannelType.CLI),
                ChannelType.CLI,
                "goal",
                cp);

        assertEquals("v", ctx.getData("k"));
        assertEquals(7, ctx.getData("n"));
    }

    @Test
    void resumeContextToleratesUnparseableContextData() throws Exception {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getContextDataJson()).thenReturn("{not valid json");

        MessageContext ctx = invokePrivate(
                "prepareResumeMessageContext",
                new Class<?>[] {String.class, ChannelUser.class, ChannelType.class, String.class, AgentCheckpoint.class
                },
                "c1",
                user(ChannelType.CLI),
                ChannelType.CLI,
                "goal",
                cp);

        // Corrupt stored context is non-fatal: the resume still proceeds with an empty map.
        assertTrue(ctx.isResumeSignal());
        assertTrue(ctx.getData().isEmpty());
    }

    // ================= runResumeLoop =================

    private MessageContext resumeContextFor(ChannelType type) {
        ChannelUser u = user(type);
        return new MessageContext("c1", u, new ChannelMessage.TelegramMessage(u, "goal", "c1", Instant.now()));
    }

    @Test
    void resumeLoopRunsTheAgentWithTheComputedBudget() throws Exception {
        MessageContext ctx = resumeContextFor(ChannelType.TELEGRAM);
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn("s-1");
        when(cp.getAgentRole()).thenReturn("r1");
        when(cp.getSystemNote()).thenReturn("note");
        when(cp.getMaxIterations()).thenReturn(10);
        when(cp.getIterationsUsed()).thenReturn(2);
        when(cp.getAutonomyLevel()).thenReturn(AutonomyLevel.SUPERVISED);

        AgentResult expected = AgentResult.finished("ok", List.of());
        when(agentLoop.run(eq("s-1"), eq("r1"), eq("goal"), eq("note"), eq(reasoningEngine), eq(8), eq(ctx)))
                .thenReturn(expected);

        AgentResult actual = invokePrivate(
                "runResumeLoop",
                new Class<?>[] {MessageContext.class, AgentCheckpoint.class, String.class},
                ctx,
                cp,
                "goal");

        assertSame(expected, actual);
    }

    @Test
    void resumeLoopFailureIsReportedAndRethrown() throws Exception {
        MessageContext ctx = resumeContextFor(ChannelType.TELEGRAM);
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn("s-1");
        when(cp.getAgentRole()).thenReturn("r1");
        when(cp.getSystemNote()).thenReturn("note");
        when(cp.getMaxIterations()).thenReturn(10);
        when(cp.getIterationsUsed()).thenReturn(2);
        when(cp.getAutonomyLevel()).thenReturn(AutonomyLevel.SUPERVISED);
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("loop blew up"));

        try {
            invokePrivate(
                    "runResumeLoop",
                    new Class<?>[] {MessageContext.class, AgentCheckpoint.class, String.class},
                    ctx,
                    cp,
                    "goal");
            fail("a failed resume loop must propagate");
        } catch (InvocationTargetException e) {
            assertInstanceOf(RuntimeException.class, e.getCause());
        }

        verify(resumeFailureCounter).increment();
    }

    // ================= dispatchResumeResult: remaining branches =================

    @Test
    void dispatchIsSkippedWhenCronOwnershipIsLost() {
        ChannelUser u = user(ChannelType.CLI);
        AgentResult res = AgentResult.finished("done", List.of());

        // A malformed cron session id makes syncTerminal refuse the transition.
        handler.dispatchResumeResult(null, res, ChannelType.CLI, u, "cron-short", null);

        verifyNoInteractions(channelPluginRegistry);
        verifyNoInteractions(cliResultBridge);
    }

    @Test
    void cliDispatchDeliversAnApprovalFrameThroughTheBridge() {
        ChannelUser u = user(ChannelType.CLI);
        AgentResult approval = AgentResult.awaitingApproval("sandbox_exec", "{}", "cli-abc", List.of());

        handler.dispatchResumeResult(null, approval, ChannelType.CLI, u, "cli-abc", "sandbox_exec");

        verify(cliResultBridge).complete(anyString(), eq(approval));
    }

    @Test
    void cliDispatchDeliversAResultThroughTheBridge() {
        ChannelUser u = user(ChannelType.CLI);
        AgentResult res = AgentResult.finished("done", List.of());

        handler.dispatchResumeResult(null, res, ChannelType.CLI, u, "cli-abc", null);

        verify(cliResultBridge).complete(anyString(), eq(res));
    }

    @Test
    void dispatchPrependsApprovedToolInfoToTheOutput() {
        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser u = user(ChannelType.TELEGRAM);
        AgentResult res = AgentResult.finished("all good", List.of());

        handler.dispatchResumeResult(null, res, ChannelType.TELEGRAM, u, "s-1", "web_search");

        verify(speechDeliveryService).deliver(isNull(), eq(sender), eq(u), eq(res), contains("✅ Approved"));
    }

    @Test
    void dispatchMarksDeniedToolInfoAsDenied() {
        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser u = user(ChannelType.TELEGRAM);
        AgentResult res = AgentResult.finished("Request denied by policy", List.of());

        handler.dispatchResumeResult(null, res, ChannelType.TELEGRAM, u, "s-1", "web_search");

        verify(speechDeliveryService).deliver(isNull(), eq(sender), eq(u), eq(res), contains("❌ Denied"));
    }

    @Test
    void dispatchFallsBackWhenNoSenderIsRegisteredForTheChannel() {
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.empty());

        ChannelUser u = user(ChannelType.TELEGRAM);
        AgentResult res = AgentResult.finished("done", List.of());

        assertDoesNotThrow(() -> handler.dispatchResumeResult(null, res, ChannelType.TELEGRAM, u, "s-1", null));

        verifyNoInteractions(speechDeliveryService);
        verifyNoInteractions(cliResultBridge);
    }

    @Test
    void approvalResultIsDeliveredAsAnApprovalPrompt() {
        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser u = user(ChannelType.TELEGRAM);
        AgentResult approval = AgentResult.awaitingApproval("sandbox_exec", "{}", "s-1", List.of());

        handler.dispatchResumeResult(null, approval, ChannelType.TELEGRAM, u, "s-1", null);

        // An approval frame goes out as a prompt, not through the speech pipeline.
        verify(sender).send(eq(u), anyList());
        verifyNoInteractions(speechDeliveryService);
    }

    @Test
    void rawOutputIsSentWhenSpeechDeliveryIsUnavailable() {
        handler.speechDeliveryService = null;

        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.of(plugin));

        ChannelUser u = user(ChannelType.TELEGRAM);
        AgentResult res = AgentResult.finished("plain output", List.of());

        handler.dispatchResumeResult(null, res, ChannelType.TELEGRAM, u, "s-1", null);

        verify(sender).sendRaw(eq(u), contains("plain output"));
    }
}
