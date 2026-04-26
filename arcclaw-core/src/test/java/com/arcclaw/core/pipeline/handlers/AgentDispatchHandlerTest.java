package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.SessionContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.StatefulAgentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentDispatchHandlerTest {

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    SkillRegistry skillRegistry;

    @Mock
    ChannelPluginRegistry channelPluginRegistry;

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    IntentClassificationService intentClassifier;

    @InjectMocks
    AgentDispatchHandler handler;

    private MockedStatic<WorkspaceContext> mockedContext;

    @BeforeEach
    void setup() {
        mockedContext = mockStatic(WorkspaceContext.class);
        when(intentClassifier.isContinuityCommand(anyString())).thenReturn(false);
        lenient().when(channelPluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.empty());
        lenient().when(agentStore.markResuming(anyString())).thenReturn(true);
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
                anyString(), anyString(), anyString(), eq(AutonomyLevel.FULL), anyString(), anyString(), any(Callable.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadonlySessionIsNotUpgraded() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.READONLY);
        
        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), eq(AutonomyLevel.READONLY), anyString(), anyString(), any(Callable.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNewSessionDefaultsToSupervised() throws Exception {
        MessageContext context = createTestContext(null); // No autonomy in context
        
        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testBudgetExceededPropagatesToCaller() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.FULL);
        
        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)))
                .thenThrow(new BudgetExceededException("Budget exceeded for sess123"));

        assertThrows(BudgetExceededException.class, () -> handler.handle(context));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMarkdownLinksAreNormalizedBeforeDispatch() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.FULL);
        context.setGoal("browse https://spring.io/blog top 3 titles");

        ChannelSender sender = Mockito.mock(ChannelSender.class);
        ChannelPlugin plugin = Mockito.mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt(), any(MessageContext.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        mockedContext.when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(6)).call());

        handler.handle(context);

        verify(agentLoop).run(
                eq("sess123"),
                eq("coordinator"),
                eq("browse https://spring.io/blog top 3 titles"),
                eq(reasoningEngine),
                eq(5),
                eq(context));
    }

    private MessageContext createTestContext(AutonomyLevel level) {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, "test", "m1", Instant.now());
        
        MessageContext context = new MessageContext("chat123", user, msg);
        context.setSessionId("sess123");
        context.setAutonomyLevel(level);
        context.setAgentRole("coordinator");
        
        // Populate SessionContext as required by AgentDispatchHandler
        AutonomyLevel finalLevel = level != null ? level : AutonomyLevel.SUPERVISED;
        SessionContext sessionContext = new SessionContext("sess123", user, finalLevel, false);
        context.setSessionContext(sessionContext);
        
        return context;
    }
}
