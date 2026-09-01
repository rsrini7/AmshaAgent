package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CheckpointResumeHandlerTest {

    @Inject
    CheckpointResumeHandler handler;

    @InjectMock
    StatefulAgentStore agentStore;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    ReasoningEngine reasoningEngine;

    @InjectMock
    ChannelPluginRegistry channelPluginRegistry;

    @InjectMock
    CliResultBridge cliResultBridge;

    @InjectMock
    ManagedExecutor executor;

    @InjectMock
    AgentStateMachineRunner stateMachineRunner;

    @Test
    void testReturnsNullOnNonResumptionGoal() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(false);

        AgentResult result =
                handler.handle(mock(AgentExecutionContext.class), new ArrayList<>(), ctx, stateMachineRunner);
        assertNull(result);
    }

    @Test
    void testHandleReturnsNullWhenNoCheckpoint() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(true);
        when(agentStore.load(any(), any())).thenReturn(Optional.empty());

        AgentExecutionContext execCtx = mock(AgentExecutionContext.class);
        when(execCtx.sessionId()).thenReturn("s1");
        when(execCtx.agentRole()).thenReturn("role");

        AgentResult result = handler.handle(execCtx, new ArrayList<>(), ctx, stateMachineRunner);
        assertNull(result);
    }

    @Test
    void testResumeAfterBatchDecisionClaimFails() {
        when(agentStore.claimForResume("s1", "role")).thenReturn(false);

        handler.resumeAfterBatchDecision("s1", "role", "u1", "c1", "telegram");
        Mockito.verify(agentStore, Mockito.never()).clearBatchResuming("s1");
    }

    @Test
    void testResumeAfterBatchDecisionCheckpointNull() {
        when(agentStore.claimForResume("s1", "role")).thenReturn(true);
        when(agentStore.load("s1", "role")).thenReturn(Optional.empty());

        handler.resumeAfterBatchDecision("s1", "role", "u1", "c1", "telegram");
        Mockito.verify(agentStore).releaseResumeClaim("s1", "role");
        Mockito.verify(agentStore).clearBatchResuming("s1");
    }

    @Test
    void testPrepareResumeMessageContext() throws Exception {
        var method = CheckpointResumeHandler.class.getDeclaredMethod(
                "prepareResumeMessageContext", String.class, ChannelUser.class, ChannelType.class, String.class);
        method.setAccessible(true);

        for (String cTypeId : List.of("telegram", "discord", "rest_api", "cli", "websocket", "slack")) {
            ChannelType type = ChannelType.of(cTypeId);
            ChannelUser user = new ChannelUser("u1", "c1", "Op", type);
            MessageContext ctx = (MessageContext) method.invoke(handler, "chat-1", user, type, "resume goal");
            Assertions.assertNotNull(ctx);
            Assertions.assertTrue(ctx.isResumeSignal());
            Assertions.assertEquals("resume goal", ctx.getGoal());
        }
    }
}
