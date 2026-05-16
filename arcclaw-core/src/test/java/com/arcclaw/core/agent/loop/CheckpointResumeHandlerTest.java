package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

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

        AgentResult result = handler.handle(mock(AgentExecutionContext.class), new ArrayList<>(), ctx, stateMachineRunner);
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
}
