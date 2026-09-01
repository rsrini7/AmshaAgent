package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;
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
                cliResultBridge,
                recoveryService,
                escalationService);
        handler.executor = executor;
        handler.speechDeliveryService = speechDeliveryService;
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
        syncCronStatus.invoke(handler, cronSessionId);
        verify(cronStore).markRunning("12345678-1234-1234-1234-123456789012");

        Method markCronTerminal =
                CheckpointResumeHandler.class.getDeclaredMethod("markCronTerminal", String.class, AgentResult.class);
        markCronTerminal.setAccessible(true);

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
                CronStatus.ACTIVE.name(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
        when(cronStore.findById("12345678-1234-1234-1234-123456789012")).thenReturn(Optional.of(cronDto));

        markCronTerminal.invoke(
                handler, "12345678-1234-1234-1234-123456789012", AgentResult.finished("done", List.of()));
        verify(cronStore).markCompleted(eq("12345678-1234-1234-1234-123456789012"), any());
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
}
