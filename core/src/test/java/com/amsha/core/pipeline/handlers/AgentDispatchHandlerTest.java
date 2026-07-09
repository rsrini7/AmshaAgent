package com.amsha.core.pipeline.handlers;

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

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.SessionContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.TimeContextInjector;

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
        lenient().when(intentClassifier.isRetryCommand(anyString())).thenReturn(false);
        lenient().when(intentClassifier.isContinuationCommand(anyString())).thenReturn(false);
        lenient().when(intentClassifier.isResumeCommand(anyString())).thenReturn(false);
        lenient().when(channelPluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.empty());
        lenient().when(agentStore.markResuming(anyString())).thenReturn(true);
        handler.defaultTimezone = "Asia/Kolkata";
    }

    @AfterEach
    void tearDown() {
        mockedContext.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFullAutonomySessionIsNotDowngradedToSupervised() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.FULL);

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(AutonomyLevel.FULL),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                anyString(),
                anyString(),
                anyString(),
                any(AutonomyLevel.class),
                anyString(),
                anyString(),
                anyString(),
                any(Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadonlySessionIsNotUpgraded() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.READONLY);

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(AutonomyLevel.READONLY),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                anyString(),
                anyString(),
                anyString(),
                any(AutonomyLevel.class),
                anyString(),
                anyString(),
                anyString(),
                any(Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNewSessionDefaultsToSupervised() throws Exception {
        MessageContext context = createTestContext(null); // No autonomy in context

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        handler.handle(context);

        mockedContext.verify(() -> WorkspaceContext.run(
                anyString(),
                anyString(),
                anyString(),
                any(AutonomyLevel.class),
                anyString(),
                anyString(),
                anyString(),
                any(Callable.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testBudgetExceededPropagatesToCaller() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.FULL);

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
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

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(7)).call());

        handler.handle(context);

        verify(agentLoop)
                .run(
                        eq("sess123"),
                        eq("coordinator"),
                        eq("browse https://spring.io/blog top 3 titles"),
                        eq(reasoningEngine),
                        eq(5),
                        eq(context));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTimeContextInjectorCalledOnGoalRestoration() throws Exception {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, "proceed", "m1", Instant.now());
        MessageContext context = new MessageContext("chat123", user, msg);
        context.setSessionId("sess123");
        context.setAutonomyLevel(AutonomyLevel.FULL);
        context.setAgentRole("coordinator");
        context.setGoal("proceed");

        SessionContext sessionContext = new SessionContext("sess123", user, AutonomyLevel.FULL, false);
        context.setSessionContext(sessionContext);

        lenient().when(intentClassifier.isContinuationCommand("proceed")).thenReturn(true);
        lenient().when(intentClassifier.isResumeCommand("proceed")).thenReturn(true);
        lenient()
                .when(intentClassifier.isResumeCommand("schedule a search every minute"))
                .thenReturn(false);

        AgentCheckpoint mockCheckpoint = Mockito.mock(AgentCheckpoint.class);
        when(mockCheckpoint.getOriginalGoal()).thenReturn("schedule a search every minute");
        when(agentStore.findLatestNonArchivedCheckpoint("sess123")).thenReturn(Optional.of(mockCheckpoint));

        ChannelSender sender = Mockito.mock(ChannelSender.class);
        ChannelPlugin plugin = Mockito.mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt(), any(MessageContext.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(7)).call());

        try (MockedStatic<TimeContextInjector> mockedTimeInjector = mockStatic(TimeContextInjector.class)) {
            handler.handle(context);

            mockedTimeInjector.verify(() ->
                    TimeContextInjector.inject(eq(context), eq("schedule a search every minute"), eq("Asia/Kolkata")));
        }
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
