package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.CodeGenerationPolicy;
import com.amsha.core.agent.loop.LoopStuckDetector;
import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.routing.ModelRoute;
import com.amsha.core.skill.SkillAdaptationManager;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaDownloadPort;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;

class ReasoningMessageAssemblerTest {

    @Test
    void assemble_simpleChitchatRetainsPreviousExchangeForPronounResolution() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble("sess-1", "Did Edison cheat him?", Collections.emptyList()))
                .thenReturn(List.of(
                        UserMessage.userMessage("Who invented AC current?"),
                        AiMessage.aiMessage("Nikola Tesla developed the practical polyphase AC system.")));
        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, "Did Edison cheat him?", true);

        assertTrue(containsText(result.messages(), "Who invented AC current?"));
        assertTrue(containsText(result.messages(), "Nikola Tesla developed"));
        assertTrue(containsText(result.messages(), "Did Edison cheat him?"));
    }

    @Test
    void assemble_injectsLoopStuckHintForRepeatedIdenticalToolCalls() throws Exception {
        ReasoningMessageAssembler assembler = createAssembler();

        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")));

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, history);

        assertTrue(containsHint(result, "STOP repeating"), "Expected a loop-stuck hint user message to be injected");
    }

    @Test
    void assemble_injectsLoopStuckHintForReadOnlyLoop() throws Exception {
        ReasoningMessageAssembler assembler = createAssembler();

        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "ls /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo/a")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "grep x /foo/b")));

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, history);

        assertTrue(
                containsHint(result, "STOP inspecting"), "Expected a read-only-loop hint user message to be injected");
    }

    @Test
    void assemble_noHintWhenHistoryIsProgressing() throws Exception {
        ReasoningMessageAssembler assembler = createAssembler();

        List<ReasoningStep> history = List.of(
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "cat /foo")),
                ReasoningStep.fastPath("thought", "host_exec", Map.of("code", "echo done > /foo/out")));

        ReasoningMessageAssembler.AssembledMessages result = assemble(assembler, history);

        assertFalse(
                containsHint(result, "STOP repeating") || containsHint(result, "STOP inspecting"),
                "No hint should be injected when history shows progress");
    }

    @Test
    void assemble_convertsToolMessagesToTextWhenForceFinishRetryActive() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble("sess-1", "goal", Collections.emptyList()))
                .thenReturn(List.of(
                        AiMessage.aiMessage(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("sandbox_exec")
                                .arguments("{\"code\":\"foo\"}")
                                .build())),
                        ToolExecutionResultMessage.toolExecutionResultMessage("call-1", "sandbox_exec", "9973")));
        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of());
        context.setForceFinishRetry(true);

        ReasoningMessageAssembler.AssembledMessages result = assembler.assemble(
                context,
                "coder",
                "goal",
                Collections.emptyList(),
                "system prompt",
                Set.of(ToolCapability.SANDBOX_EXEC.toolName()),
                false,
                false,
                false);

        // Tool specifications must be empty
        assertTrue(result.tools().isEmpty(), "Tools must be empty during forceFinishRetry");

        // There should be NO raw ToolExecutionResultMessage or AiMessage with toolExecutionRequests
        assertFalse(
                result.messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage),
                "Raw ToolExecutionResultMessage must not be present");
        assertFalse(
                result.messages().stream().anyMatch(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests()),
                "AiMessage with toolExecutionRequests must not be present");

        // Tool output must be preserved as user message text
        assertTrue(
                result.messages().stream()
                        .anyMatch(m -> m instanceof UserMessage u
                                && u.singleText().contains("[Tool Output from sandbox_exec]: 9973")),
                "Tool output must be preserved as plain text user message");
    }

    @Test
    void assemble_greetingWithPriorCodeHistory_excludesPriorCodeFromModelContext() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble("sess-1", "Hi", Collections.emptyList()))
                .thenReturn(List.of(
                        UserMessage.userMessage(
                                "write java program prime number finder largest below 10K and execute it"),
                        AiMessage.aiMessage(
                                "```java\npublic class PrimeFinder { public static void main(String[] args) {} }\n```"),
                        UserMessage.userMessage("[Tool Output from sandbox_exec]: 9973")));
        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "Hi",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of());

        ReasoningMessageAssembler.AssembledMessages result = assembler.assemble(
                context, "coordinator", "Hi", Collections.emptyList(), "system prompt", Set.of(), false, true, false);

        // Tools must be empty for simple chitchat
        assertTrue(result.tools().isEmpty(), "Tools must be empty for greeting");

        // Prior prime finder and code execution must NOT be in the greeting context
        assertFalse(
                result.messages().stream()
                        .anyMatch(m ->
                                m instanceof UserMessage u && u.singleText().contains("prime number finder")),
                "Prior code generation task must not leak into greeting message context");
        assertFalse(
                result.messages().stream()
                        .anyMatch(m -> m instanceof AiMessage ai && ai.text().contains("PrimeFinder")),
                "Prior generated Java code must not leak into greeting message context");
        assertFalse(
                result.messages().stream()
                        .anyMatch(m ->
                                m instanceof UserMessage u && u.singleText().contains("[Tool Output")),
                "Prior tool output must not leak into greeting message context");

        // The only user message should be the current greeting "Hi"
        List<UserMessage> userMessages = result.messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .toList();
        assertEquals(1, userMessages.size(), "Should only have the current user greeting");
        assertEquals("Hi", userMessages.getFirst().singleText());
    }

    @Test
    void testAssemble_guaranteesUserMessageWhenProjectedHistoryHasOnlyToolMessages() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        // History from previous execution: AiMessage + ToolExecutionResultMessage without UserMessage
        when(historyAssembler.assemble(any(), any(), any()))
                .thenReturn(List.of(
                        AiMessage.aiMessage("thinking"),
                        ToolExecutionResultMessage.toolExecutionResultMessage("call_0", "web_fetch", "content")));

        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);
        AgentExecutionContext context = new AgentExecutionContext(
                "sess-resume",
                "u1",
                "c1",
                "coordinator",
                "what is latest quarkus lts version",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of());

        ReasoningMessageAssembler.AssembledMessages result = assembler.assemble(
                context,
                "coordinator",
                "what is latest quarkus lts version",
                Collections.emptyList(),
                "system prompt",
                Set.of(),
                false,
                false,
                false);

        List<UserMessage> userMessages = result.messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .toList();
        assertFalse(
                userMessages.isEmpty(),
                "Messages list MUST contain at least one UserMessage for chat template compliance");
        assertEquals(
                "what is latest quarkus lts version", userMessages.getFirst().singleText());
        // Verify UserMessage is inserted before AiMessage
        int userIdx = -1;
        int aiIdx = -1;
        for (int i = 0; i < result.messages().size(); i++) {
            if (result.messages().get(i) instanceof UserMessage && userIdx == -1) userIdx = i;
            if (result.messages().get(i) instanceof AiMessage && aiIdx == -1) aiIdx = i;
        }
        assertTrue(userIdx < aiIdx, "UserMessage must precede AiMessage in conversation sequence");
    }

    private static ReasoningMessageAssembler createAssembler() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble("sess-1", "goal", Collections.emptyList()))
                .thenReturn(Collections.emptyList());

        return createAssembler(historyAssembler);
    }

    private static ReasoningMessageAssembler createAssembler(ChatHistoryAssembler historyAssembler) throws Exception {

        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn(ToolCapability.SANDBOX_EXEC.toolName());
        when(mockTool.description()).thenReturn("sandbox exec tool");
        when(mockTool.parametersSchema()).thenReturn(JsonObjectSchema.builder().build());
        when(mockTool.isEnabled()).thenReturn(true);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.findTool(ToolCapability.SANDBOX_EXEC.toolName())).thenReturn(Optional.of(mockTool));

        ReasoningMessageAssembler assembler =
                new ReasoningMessageAssembler(historyAssembler, toolRegistry, new LoopStuckDetector());

        BeanManager bm = mock(BeanManager.class);
        when(bm.getBeans(Object.class)).thenReturn(Collections.emptySet());
        injectField(assembler, "beanManager", bm);
        injectField(assembler, "skillRegistry", mock(SkillRegistry.class));
        injectField(assembler, "adaptationManager", mock(SkillAdaptationManager.class));
        injectField(assembler, "textPatternUtils", new TextPatternUtils());
        injectField(assembler, "codePolicy", new CodeGenerationPolicy());
        return assembler;
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = ReasoningMessageAssembler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ReasoningMessageAssembler.AssembledMessages assemble(
            ReasoningMessageAssembler assembler, List<ReasoningStep> history) {
        return assemble(assembler, "goal", false, history);
    }

    private static ReasoningMessageAssembler.AssembledMessages assemble(
            ReasoningMessageAssembler assembler, String goal, boolean simpleChitchat) {
        return assemble(assembler, goal, simpleChitchat, Collections.emptyList());
    }

    private static ReasoningMessageAssembler.AssembledMessages assemble(
            ReasoningMessageAssembler assembler, String goal, boolean simpleChitchat, List<ReasoningStep> history) {
        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "skill:test",
                goal,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of());

        return assembler.assemble(
                context,
                "skill:test",
                goal,
                history,
                "system prompt",
                Set.of(ToolCapability.SANDBOX_EXEC.toolName()),
                false,
                simpleChitchat,
                false);
    }

    private static boolean containsText(List<ChatMessage> messages, String expected) {
        return messages.stream().anyMatch(message -> message.toString().contains(expected));
    }

    private static boolean containsHint(ReasoningMessageAssembler.AssembledMessages result, String marker) {
        boolean inSystem = result.messages().stream()
                .filter(m -> m instanceof SystemMessage _)
                .map(m -> ((SystemMessage) m).text())
                .anyMatch(text -> text.contains(marker));
        boolean inUser = result.messages().stream()
                .filter(m -> m instanceof UserMessage _)
                .map(m -> {
                    UserMessage um = (UserMessage) m;
                    if (um.hasSingleText()) return um.singleText();
                    return um.contents().stream()
                            .filter(c -> c instanceof TextContent)
                            .map(c -> ((TextContent) c).text())
                            .collect(Collectors.joining(" "));
                })
                .anyMatch(text -> text.contains(marker));
        return inSystem || inUser;
    }

    @Test
    void testAssembleWithMediaAttachments_assemblesMultimodalUserMessage() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble(eq("sess-1"), eq("what is this"), anyList()))
                .thenReturn(List.of(UserMessage.userMessage("what is this")));

        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);

        LlmProviderPort mockLlmPort = mock(LlmProviderPort.class);
        when(mockLlmPort.multimodalCapability(any()))
                .thenReturn(MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o"));

        Instance<LlmProviderPort> instanceMock = mock(Instance.class);
        when(instanceMock.isUnsatisfied()).thenReturn(false);
        when(instanceMock.get()).thenReturn(mockLlmPort);
        injectField(assembler, "llmProviderPortInstance", instanceMock);

        AtomicBoolean preApproved = new AtomicBoolean(true);
        MediaDownloadPort downloadPort = (attachment, _, approved) -> {
            preApproved.set(approved);
            return DownloadedMedia.ofInline(
                    attachment.attachmentId(),
                    attachment.type(),
                    attachment.mimeType(),
                    attachment.fileName(),
                    attachment.inlineData());
        };
        Instance<MediaDownloadPort> mediaInstance = mock(Instance.class);
        when(mediaInstance.isResolvable()).thenReturn(true);
        when(mediaInstance.isUnsatisfied()).thenReturn(false);
        when(mediaInstance.get()).thenReturn(downloadPort);
        injectField(assembler, "mediaDownloadPortInstance", mediaInstance);

        MediaAttachment att = new MediaAttachment(
                "att-1", AttachmentType.IMAGE, "image/png", "img.png", 3L, null, new byte[] {1, 2, 3}, Map.of());

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "skill:test",
                "what is this",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of(),
                List.of(att));

        ReasoningMessageAssembler.AssembledMessages result = assembler.assemble(
                context,
                "skill:test",
                "what is this",
                List.of(),
                "system prompt",
                Set.of(ToolCapability.SANDBOX_EXEC.toolName()),
                false,
                false,
                false);

        assertNotNull(result);
        List<UserMessage> userMessages = result.messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .toList();
        assertEquals(1, userMessages.size());
        assertEquals(2, userMessages.get(0).contents().size());
        assertInstanceOf(ImageContent.class, userMessages.get(0).contents().get(1));
        assertFalse(preApproved.get(), "Autonomy level must not masquerade as HITL approval");
    }

    @Test
    void telegramUserAttachmentCarriesScopedMediaAuthorization() throws Exception {
        AtomicBoolean preApproved = new AtomicBoolean(false);
        ReasoningMessageAssembler assembler = createMediaAssembler(preApproved);
        AgentExecutionContext context = mediaContext(MediaAttachment.ofUri(
                "tg-1",
                AttachmentType.IMAGE,
                "image/png",
                "image.png",
                3L,
                URI.create("telegram://file/file-1"),
                Map.of()));

        assembler.assemble(
                context, "skill:test", "what is this", List.of(), "system prompt", Set.of(), false, false, false);

        assertTrue(preApproved.get(), "Verified Telegram media should carry channel-origin authorization");
    }

    @Test
    void nonTelegramMediaDoesNotInheritTelegramAuthorization() throws Exception {
        AtomicBoolean preApproved = new AtomicBoolean(true);
        ReasoningMessageAssembler assembler = createMediaAssembler(preApproved);
        AgentExecutionContext context = mediaContext(MediaAttachment.ofUri(
                "remote-1",
                AttachmentType.IMAGE,
                "image/png",
                "image.png",
                3L,
                URI.create("https://example.com/image.png"),
                Map.of()));

        assembler.assemble(
                context, "skill:test", "what is this", List.of(), "system prompt", Set.of(), false, false, false);

        assertFalse(preApproved.get(), "Generic media must remain behind outbound HITL");
    }

    @Test
    void nonTelegramChannelDoesNotInheritTelegramAuthorization() throws Exception {
        AtomicBoolean preApproved = new AtomicBoolean(true);
        ReasoningMessageAssembler assembler = createMediaAssembler(preApproved);
        AgentExecutionContext context = mediaContext(
                "cli",
                List.of(MediaAttachment.ofUri(
                        "tg-1",
                        AttachmentType.IMAGE,
                        "image/png",
                        "image.png",
                        3L,
                        URI.create("telegram://file/file-1"),
                        Map.of())));

        assembleMedia(assembler, context);

        assertFalse(preApproved.get(), "Telegram-shaped media on another channel must remain governed");
    }

    @Test
    void mixedTelegramAndGenericMediaFailsClosedForAuthorization() throws Exception {
        AtomicBoolean preApproved = new AtomicBoolean(true);
        ReasoningMessageAssembler assembler = createMediaAssembler(preApproved);
        AgentExecutionContext context = mediaContext(
                "TELEGRAM",
                List.of(
                        MediaAttachment.ofUri(
                                "tg-1",
                                AttachmentType.IMAGE,
                                "image/png",
                                "telegram.png",
                                3L,
                                URI.create("telegram://file/file-1"),
                                Map.of()),
                        MediaAttachment.ofUri(
                                "remote-1",
                                AttachmentType.IMAGE,
                                "image/png",
                                "remote.png",
                                3L,
                                URI.create("https://example.com/image.png"),
                                Map.of())));

        assembleMedia(assembler, context);

        assertFalse(preApproved.get(), "Mixed-origin media must not inherit Telegram authorization");
    }

    @Test
    void malformedTelegramReferenceFailsClosedForAuthorization() throws Exception {
        AtomicBoolean preApproved = new AtomicBoolean(true);
        ReasoningMessageAssembler assembler = createMediaAssembler(preApproved);
        AgentExecutionContext context = mediaContext(MediaAttachment.ofUri(
                "tg-1",
                AttachmentType.IMAGE,
                "image/png",
                "image.png",
                3L,
                URI.create("telegram://not-file/file-1"),
                Map.of()));

        assembleMedia(assembler, context);

        assertFalse(preApproved.get(), "Non-canonical Telegram references must remain governed");
    }

    private static void assembleMedia(ReasoningMessageAssembler assembler, AgentExecutionContext context) {
        assembler.assemble(
                context, "skill:test", "what is this", List.of(), "system prompt", Set.of(), false, false, false);
    }

    private static ReasoningMessageAssembler createMediaAssembler(AtomicBoolean preApproved) throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        when(historyAssembler.assemble(eq("sess-1"), eq("what is this"), anyList()))
                .thenReturn(List.of(UserMessage.userMessage("what is this")));
        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);

        LlmProviderPort mockLlmPort = mock(LlmProviderPort.class);
        when(mockLlmPort.multimodalCapability(any()))
                .thenReturn(MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o"));
        Instance<LlmProviderPort> llmInstance = mock(Instance.class);
        when(llmInstance.isUnsatisfied()).thenReturn(false);
        when(llmInstance.get()).thenReturn(mockLlmPort);
        injectField(assembler, "llmProviderPortInstance", llmInstance);

        MediaDownloadPort downloadPort = (attachment, _, approved) -> {
            preApproved.set(approved);
            return DownloadedMedia.ofInline(
                    attachment.attachmentId(),
                    attachment.type(),
                    attachment.mimeType(),
                    attachment.fileName(),
                    new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        };
        Instance<MediaDownloadPort> mediaInstance = mock(Instance.class);
        when(mediaInstance.isResolvable()).thenReturn(true);
        when(mediaInstance.isUnsatisfied()).thenReturn(false);
        when(mediaInstance.get()).thenReturn(downloadPort);
        injectField(assembler, "mediaDownloadPortInstance", mediaInstance);
        return assembler;
    }

    private static AgentExecutionContext mediaContext(MediaAttachment attachment) {
        return mediaContext("TELEGRAM", List.of(attachment));
    }

    private static AgentExecutionContext mediaContext(String channelType, List<MediaAttachment> attachments) {
        return new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "skill:test",
                "what is this",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                channelType,
                "123",
                null,
                Map.of(),
                attachments);
    }

    @Test
    void assembleUsesRouteCapabilityOverRoleStaticCapability() throws Exception {
        AtomicBoolean preApproved = new AtomicBoolean(false);
        ReasoningMessageAssembler assembler = createMediaAssembler(preApproved);

        MediaAttachment imageAtt = MediaAttachment.ofInline(
                "img-1", AttachmentType.IMAGE, "image/png", "test.png", new byte[] {1, 2, 3}, null);
        AgentExecutionContext context = mediaContext("WEB", List.of(imageAtt));

        // Route explicitly provides TEXT_ONLY capability
        ModelRoute textRoute = new ModelRoute(
                "route-text",
                "prov",
                "text-model",
                null,
                mock(ChatModel.class),
                MultimodalCapability.TEXT_ONLY,
                List.of(),
                1);

        ReasoningMessageAssembler.AssembledMessages result = assembler.assemble(
                context,
                "researcher",
                "what is this",
                List.of(),
                "system prompt",
                Set.of(),
                false,
                false,
                false,
                textRoute);

        List<UserMessage> userMessages = result.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .toList();

        assertEquals(1, userMessages.size());
        UserMessage userMsg = userMessages.getFirst();
        // Since route is TEXT_ONLY, the active attachment should degrade to text placeholder
        assertTrue(userMsg.singleText().contains("Model text-model does not support IMAGE modality"));
        assertFalse(userMsg.contents().stream().anyMatch(ImageContent.class::isInstance));
    }

    @Test
    void assembleProjectsHistoricalMultimodalMessages() throws Exception {
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        UserMessage priorTurnWithImage = UserMessage.from(
                TextContent.from("Prior turn goal"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .base64Data(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                        .build()));
        UserMessage currentTurn = UserMessage.userMessage("Current question");

        when(historyAssembler.assemble(eq("sess-1"), eq("Current question"), anyList()))
                .thenReturn(List.of(priorTurnWithImage, currentTurn));

        ReasoningMessageAssembler assembler = createAssembler(historyAssembler);

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "researcher",
                "Current question",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                null,
                null,
                null,
                Map.of(),
                List.of());

        // 1. When current turn routes to a TEXT_ONLY model: prior turn ImageContent should be projected to placeholder
        ModelRoute textRoute = new ModelRoute(
                "text-route",
                "prov",
                "text-model",
                null,
                mock(ChatModel.class),
                MultimodalCapability.TEXT_ONLY,
                List.of(),
                1);
        ReasoningMessageAssembler.AssembledMessages textResult = assembler.assemble(
                context,
                "researcher",
                "Current question",
                List.of(),
                "system prompt",
                Set.of(),
                false,
                false,
                false,
                textRoute);

        List<UserMessage> textUserMessages = textResult.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .toList();
        assertEquals(2, textUserMessages.size());
        assertTrue(textUserMessages
                .get(0)
                .singleText()
                .contains("[Attached Media: Image (image/png) - Model text-model does not support vision modality]"));

        // 2. When current turn routes to a VISION model: prior turn ImageContent is preserved
        MultimodalCapability visionCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 5, true, true);
        ModelRoute visionRoute =
                new ModelRoute("vision-route", "prov", "qwen-vl", null, mock(ChatModel.class), visionCap, List.of(), 1);
        ReasoningMessageAssembler.AssembledMessages visionResult = assembler.assemble(
                context,
                "researcher",
                "Current question",
                List.of(),
                "system prompt",
                Set.of(),
                false,
                false,
                false,
                visionRoute);

        List<UserMessage> visionUserMessages = visionResult.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .toList();
        assertEquals(2, visionUserMessages.size());
        assertTrue(visionUserMessages.get(0).contents().stream().anyMatch(ImageContent.class::isInstance));
    }
}
