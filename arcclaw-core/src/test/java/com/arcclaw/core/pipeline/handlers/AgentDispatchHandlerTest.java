package com.arcclaw.core.pipeline.handlers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.SkillRegistry;

@ExtendWith(MockitoExtension.class)
class AgentDispatchHandlerTest {

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    SkillRegistry skillRegistry;

    @InjectMocks
    AgentDispatchHandler handler;

    private MockedStatic<WorkspaceContext> mockedContext;

    @BeforeEach
    void setup() {
        mockedContext = mockStatic(WorkspaceContext.class);
    }

    @AfterEach
    void tearDown() {
        mockedContext.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFullAutonomySessionIsNotDowngradedToSupervised() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.FULL);
        
        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), eq(AutonomyLevel.FULL), anyString(), any(java.util.concurrent.Callable.class)))
                .thenReturn(AgentResult.finished("ok", java.util.List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                eq("sess123"), anyString(), anyString(), eq(AutonomyLevel.FULL), anyString(), any(java.util.concurrent.Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadonlySessionIsNotUpgraded() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.READONLY);
        
        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), eq(AutonomyLevel.READONLY), anyString(), any(java.util.concurrent.Callable.class)))
                .thenReturn(AgentResult.finished("ok", java.util.List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                eq("sess123"), anyString(), anyString(), eq(AutonomyLevel.READONLY), anyString(), any(java.util.concurrent.Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNewSessionDefaultsToSupervised() throws Exception {
        MessageContext context = createTestContext(null); // No autonomy in context
        
        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), eq(AutonomyLevel.SUPERVISED), anyString(), any(java.util.concurrent.Callable.class)))
                .thenReturn(AgentResult.finished("ok", java.util.List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                eq("sess123"), anyString(), anyString(), eq(AutonomyLevel.SUPERVISED), anyString(), any(java.util.concurrent.Callable.class)));
    }

    private MessageContext createTestContext(AutonomyLevel level) {
        ChannelUser user = new ChannelUser("u1", "User 1", ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, "test", "m1", Instant.now());
        ChannelSender sender = mock(ChannelSender.class);
        
        MessageContext context = new MessageContext("chat123", user, msg, sender);
        context.setSessionId("sess123");
        context.setAutonomyLevel(level);
        context.setAgentRole("coordinator");
        return context;
    }
}
