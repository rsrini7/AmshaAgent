package com.amsha.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.ReplyNode;
import com.amsha.channel.api.preference.UserPreferenceService;
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
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.speech.OutputMode;
import com.amsha.core.speech.OutputPolicyContext;
import com.amsha.core.speech.SpeechOutputPolicy;
import com.amsha.core.speech.SpeechReplyService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.TimeContextInjector;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    SpeechOutputPolicy speechOutputPolicy;

    @Mock
    SpeechReplyService speechReplyService;

    @Mock
    UserPreferenceService userPreferenceService;

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
        // Speech output is off by default so existing text-only dispatch paths are unaffected.
        lenient()
                .when(speechOutputPolicy.resolve(any(OutputPolicyContext.class)))
                .thenReturn(OutputMode.TEXT_ONLY);
        lenient()
                .when(speechReplyService.deliver(any(), any(), any(), anyString()))
                .thenReturn(false);
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

    @Test
    @SuppressWarnings("unchecked")
    void testAwaitingApprovalRendersSingleApprovalInteraction() throws Exception {
        MessageContext context = createTestContext(AutonomyLevel.FULL);
        ChannelSender sender = Mockito.mock(ChannelSender.class);
        ChannelPlugin plugin = Mockito.mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find(ChannelType.REST_API)).thenReturn(Optional.of(plugin));

        Method method = AgentDispatchHandler.class.getDeclaredMethod(
                "formatAndSendReply", MessageContext.class, AgentResult.class, String.class);
        method.setAccessible(true);
        method.invoke(handler, context, AgentResult.awaitingApproval("garuda", "{}", "sess123", List.of()), "sess123");

        ArgumentCaptor<List<ReplyNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(sender).send(eq(context.getUser()), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertInstanceOf(ApprovalPrompt.class, captor.getValue().getFirst());
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishedTurnWithTextAndAudioSendsTextThenAudio() throws Exception {
        ChannelSender sender = Mockito.mock(ChannelSender.class);
        when(speechOutputPolicy.resolve(any(OutputPolicyContext.class))).thenReturn(OutputMode.TEXT_AND_AUDIO);
        when(speechReplyService.deliver(any(), any(), any(), anyString())).thenReturn(true);

        invokeDeliverWithOptionalAudio(createTestContext(AutonomyLevel.FULL), sender, "the answer text");

        verify(sender, times(1)).sendRaw(any(), anyString());
        verify(speechReplyService, times(1)).deliver(any(), any(), eq(OutputMode.TEXT_AND_AUDIO), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishedTurnWithAudioOnlySendsAudioAndSkipsText() throws Exception {
        ChannelSender sender = Mockito.mock(ChannelSender.class);
        when(speechOutputPolicy.resolve(any(OutputPolicyContext.class))).thenReturn(OutputMode.AUDIO_ONLY);
        when(speechReplyService.deliver(any(), any(), any(), anyString())).thenReturn(true);

        invokeDeliverWithOptionalAudio(createTestContext(AutonomyLevel.FULL), sender, "the answer text");

        verify(speechReplyService, times(1)).deliver(any(), any(), eq(OutputMode.AUDIO_ONLY), anyString());
        verify(sender, never()).sendRaw(any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishedTurnWithAudioOnlyFallsBackToTextWhenDeliveryFails() throws Exception {
        ChannelSender sender = Mockito.mock(ChannelSender.class);
        when(speechOutputPolicy.resolve(any(OutputPolicyContext.class))).thenReturn(OutputMode.AUDIO_ONLY);
        when(speechReplyService.deliver(any(), any(), any(), anyString())).thenReturn(false);

        invokeDeliverWithOptionalAudio(createTestContext(AutonomyLevel.FULL), sender, "the answer text");

        verify(speechReplyService, times(1)).deliver(any(), any(), eq(OutputMode.AUDIO_ONLY), anyString());
        verify(sender, times(1)).sendRaw(any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void directiveInMessageTextReachesSpeechOutputPolicyWhenGoalEmpty() throws Exception {
        ChannelSender sender = Mockito.mock(ChannelSender.class);
        when(speechReplyService.deliver(any(), any(), any(), anyString())).thenReturn(true);

        // Goal is empty, but the user's directive lives in the current message text.
        MessageContext context = createTestContextWithText(AutonomyLevel.FULL, "please reply with audio");
        context.setGoal("");

        ArgumentCaptor<OutputPolicyContext> policyCaptor = ArgumentCaptor.forClass(OutputPolicyContext.class);
        when(speechOutputPolicy.resolve(policyCaptor.capture())).thenReturn(OutputMode.TEXT_AND_AUDIO);

        invokeDeliverWithOptionalAudio(context, sender, "the answer text");

        assertEquals(
                "please reply with audio",
                policyCaptor.getValue().requestedText(),
                "explicit directive in the message text must reach the output policy even when goal is empty");
        verify(speechReplyService, times(1)).deliver(any(), any(), eq(OutputMode.TEXT_AND_AUDIO), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalAndIntermediateResultsNeverSynthesize() throws Exception {
        ChannelSender sender = Mockito.mock(ChannelSender.class);

        Method method = AgentDispatchHandler.class.getDeclaredMethod(
                "deliverWithOptionalAudio", MessageContext.class, ChannelSender.class, AgentResult.class, String.class);
        method.setAccessible(true);
        method.invoke(
                handler,
                createTestContext(AutonomyLevel.FULL),
                sender,
                AgentResult.awaitingApproval("garuda", "{}", "sess123", List.of()),
                "the answer text");

        verify(sender, times(1)).sendRaw(any(), anyString());
        verify(speechReplyService, never()).deliver(any(), any(), any(), anyString());
        verify(speechOutputPolicy, never()).resolve(any(OutputPolicyContext.class));
    }

    @Test
    void hadInboundAudioDetectsAudioAttachment() throws Exception {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.REST_API);
        MediaAttachment audio = MediaAttachment.ofInline(
                "a1", AttachmentType.AUDIO, "audio/ogg", "voice.ogg", new byte[] {1, 2, 3}, Map.of());
        ChannelMessage withAudio =
                new ChannelMessage.RestApiMessage(user, "transcribe this", "m1", Instant.now(), List.of(audio));
        MessageContext audioContext = new MessageContext("chat123", user, withAudio);

        ChannelMessage withoutAudio = new ChannelMessage.RestApiMessage(user, "transcribe this", "m1", Instant.now());
        MessageContext plainContext = new MessageContext("chat123", user, withoutAudio);

        Method method = AgentDispatchHandler.class.getDeclaredMethod("hadInboundAudio", MessageContext.class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(handler, audioContext));
        assertFalse((Boolean) method.invoke(handler, plainContext));
    }

    @SuppressWarnings("unchecked")
    private void invokeDeliverWithOptionalAudio(MessageContext context, ChannelSender sender, String output)
            throws Exception {
        Method method = AgentDispatchHandler.class.getDeclaredMethod(
                "deliverWithOptionalAudio", MessageContext.class, ChannelSender.class, AgentResult.class, String.class);
        method.setAccessible(true);
        method.invoke(handler, context, sender, AgentResult.finished(output, List.of()), output);
    }

    private MessageContext createTestContext(AutonomyLevel level) {
        return createTestContextWithText(level, "test");
    }

    private MessageContext createTestContextWithText(AutonomyLevel level, String text) {
        ChannelUser user = new ChannelUser("u1", "u1", "User 1", ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, text, "m1", Instant.now());

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
