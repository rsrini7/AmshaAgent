package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.channel.api.ChannelType;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.governance.ApprovalFrame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoopExitPublisherTest {

    @Mock
    DomainEventBus eventBus;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    AgentExecutionContext context;

    private LoopExitPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new LoopExitPublisher();
        publisher.eventBus = eventBus;
        publisher.cliResultBridge = cliResultBridge;
    }

    @Test
    void testToWireStatusMapping() {
        assertEquals("idle", LoopExitPublisher.toWireStatus(new AgentResult.Finished("ans", List.of(), 1, 1)));
        assertEquals(
                "waiting-approval",
                LoopExitPublisher.toWireStatus(new AgentResult.AwaitingApproval(
                        new ApprovalFrame("tool", Map.of(), ApprovalFrame.RiskLevel.HIGH), "sess", List.of(), 1, 1)));
        assertEquals(
                "waiting-retry",
                LoopExitPublisher.toWireStatus(new AgentResult.AwaitingRetry("sess", 1000, List.of(), 1, 1)));
        assertEquals(
                "error",
                LoopExitPublisher.toWireStatus(
                        new AgentResult.Failed("err", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 1, 1)));
    }

    @Test
    void testPublishStartSkipsDelegateSession() {
        when(context.sessionId()).thenReturn("sess" + AgentSentinels.DELEGATE_SESSION_SENTINEL + "1");

        publisher.publishStart(context);
        verifyNoInteractions(eventBus);
    }

    @Test
    void testPublishStartFiresForNormalSession() {
        when(context.sessionId()).thenReturn("sess-1");
        when(context.agentRole()).thenReturn("coder");
        when(context.goal()).thenReturn("fix bug");

        publisher.publishStart(context);
        verify(eventBus).publish(any(), any());
    }

    @Test
    void testPublishForCliChannelCompletesBridge() {
        when(context.sessionId()).thenReturn("sess-cli");
        when(context.agentRole()).thenReturn("coder");
        when(context.goal()).thenReturn("fix bug");
        when(context.channelType()).thenReturn(ChannelType.CLI.id());

        AgentResult result = new AgentResult.Finished("done", List.of(), 1, 1);
        publisher.publish(context, result);

        verify(cliResultBridge).complete("sess-cli", result);
    }
}
