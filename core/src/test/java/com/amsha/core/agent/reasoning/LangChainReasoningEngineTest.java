package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.governance.BudgetWarningException;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.error.LlmToolCallException;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
class LangChainReasoningEngineTest {

    @Inject
    ReasoningEngine reasoningEngine;

    @InjectMock
    LlmProviderPort llmProviderPort;

    @InjectMock
    SystemPromptBuilder promptBuilder;

    @InjectMock
    ChatHistoryAssembler historyAssembler;

    @InjectMock
    ReasoningStepParser stepParser;

    @InjectMock
    RoleRegistry roleRegistry;

    @InjectMock
    SkillRegistry skillRegistry;

    @InjectMock
    ToolRegistry toolRegistry;

    @InjectMock
    CompactionSummaryCache summaryCache;

    @InjectMock
    StructuredOutputProcessor structuredOutputProcessor;

    @BeforeEach
    void setUp() {
        // Default behavior for mocks
        when(roleRegistry.resolvePermittedTools(anyString())).thenReturn(Collections.emptySet());
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(summaryCache.getSummary(any())).thenReturn(Optional.empty());
    }

    private AgentExecutionContext createCtx(String sessionId) {
        return createCtx(sessionId, "goal");
    }

    private AgentExecutionContext createCtx(String sessionId, String goal) {
        return new AgentExecutionContext(
                sessionId,
                "user-1",
                "chat-1",
                "coordinator",
                goal,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
    }

    @Test
    void testNextStepReturnsFinish() {
        // Arrange
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage mockAiMessage = AiMessage.aiMessage("I have finished the task");
        TokenUsage tokenUsage = new TokenUsage(100, 100);
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(mockAiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.STOP)
                .build();

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending(
                        "I have finished the task",
                        ReasoningStep.FINISH,
                        Map.of("answer", "I have finished the task"),
                        100,
                        100));

        // Act
        ReasoningStep step =
                reasoningEngine.nextStep(createCtx("test-session"), "coordinator", "Do something", List.of());

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action());
        assertEquals("I have finished the task", step.thought());
        assertEquals(100, step.inputTokens());
        assertEquals(100, step.outputTokens());
    }

    @Test
    void testNextStepWithToolExecution() {
        // Arrange
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call_123")
                .name(ToolCapability.GITHUB.toolName())
                .arguments("{\"repo\":\"test/repo\"}")
                .build();
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage mockAiMessage = AiMessage.aiMessage("I will use github", Collections.singletonList(toolRequest));
        TokenUsage tokenUsage = new TokenUsage(15, 25);
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(mockAiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending(
                        "I will use github", ToolCapability.GITHUB.toolName(), Map.of("repo", "test/repo"), 100, 100));

        // Act
        ReasoningStep step =
                reasoningEngine.nextStep(createCtx("test-session"), "coordinator", "Do something", List.of());

        // Assert
        assertEquals(ToolCapability.GITHUB.toolName(), step.action());
        assertEquals("I will use github", step.thought());
        assertEquals(100, step.inputTokens());
        assertEquals(100, step.outputTokens());
        assertTrue(step.actionInput().containsKey("repo"));
        assertEquals("test/repo", step.actionInput().get("repo"));
    }

    @Test
    void testSkillRoleDoesNotUseSimpleChitchatPromptPath() {
        // Arrange
        AgentTool execTool = mock(AgentTool.class);
        when(execTool.isEnabled()).thenReturn(true);
        when(toolRegistry.findTool(ToolCapability.HOST_EXEC.toolName())).thenReturn(Optional.of(execTool));
        when(roleRegistry.resolvePermittedTools("skill:algorithmic-art"))
                .thenReturn(Set.of(ToolCapability.HOST_EXEC.toolName()));
        when(skillRegistry.currentSnapshot()).thenReturn(List.of());

        AiMessage mockAiMessage = AiMessage.aiMessage("I'll start by reading the template file.");
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(mockAiMessage)
                .tokenUsage(new TokenUsage(10, 10))
                .finishReason(FinishReason.STOP)
                .build();

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending(
                        "I'll start by reading the template file.",
                        ReasoningStep.FINISH,
                        Map.of("answer", "I'll start by reading the template file."),
                        10,
                        10));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "skill-session",
                "user-1",
                "chat-1",
                "skill:algorithmic-art",
                "use algorithmic art to generate very simple flow fields",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);

        // Act
        ReasoningStep step = reasoningEngine.nextStep(ctx, "skill:algorithmic-art", ctx.goal(), List.of());

        // Assert
        assertEquals(ReasoningStep.OBSERVE, step.action());
        assertTrue(step.observation().contains("CRITICAL SKILL MODE"));
    }

    @InjectMock
    ChatMemoryStore chatMemoryStore;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testApprovalMarkerMessagesAreFilteredFromHistory() {
        // Arrange
        String sessionId = "test-session";

        WorkspaceScopeHolder.run(sessionId, () -> {
            List<ChatMessage> storedMessages = List.of(
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    SystemMessage.systemMessage("Ignore me"),
                    AiMessage.aiMessage(MessageTag.tag("Human Approved: ...")),
                    UserMessage.userMessage("Actual Goal"),
                    UserMessage.userMessage("Resuming task after approval"));
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(storedMessages);

            AiMessage mockAiMessage = AiMessage.from("Processing");
            ChatResponse mockResponse =
                    ChatResponse.builder().aiMessage(mockAiMessage).build();

            // Capture the request to verify filtered messages
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(llmProviderPort.nextStep(requestCaptor.capture(), any(String.class)))
                    .thenReturn(mockResponse);
            when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                    .thenReturn("System Prompt");
            // For the filter test, we need the assembler to actually return the filtered messages if it were the real
            // one,
            // but here we are mocking the engine's call to assembler.
            // Wait, the engine calls historyAssembler.assemble().
            // The test verifies if messages are filtered in the ChatRequest sent to LLM.
            // So we should make the mock assembler return the messages we expect to be filtered (or NOT filtered).
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                List<ChatMessage> storeMsgs = chatMemoryStore.getMessages((String) invocation.getArgument(0));
                return storeMsgs.stream()
                        .filter(m -> !(m instanceof SystemMessage))
                        .filter(m -> !(m instanceof AiMessage ai && MessageTag.isTagged(ai.text())))
                        .filter(m -> !(m instanceof UserMessage user
                                && (user.singleText().contains("Resuming")
                                        || user.singleText().contains("The tool execution has been approved"))))
                        .toList();
            });
            when(stepParser.parse(any(), any(), any()))
                    .thenReturn(ReasoningStep.pending("Processing", ReasoningStep.FINISH, Map.of(), 100, 100));

            // Act
            reasoningEngine.nextStep(createCtx(sessionId), "coordinator", "Actual Goal", List.of());

            // Assert
            ChatRequest capturedRequest = requestCaptor.getValue();
            List<ChatMessage> filtered = capturedRequest.messages();

            // Should have:
            // 1. Initial System Message (Trimurti prompt)
            // 2. "Actual Goal" (from history)
            // Count: 2

            // Let's verify specifically that "Ignore me", "Human Approved", and "Resuming" were skipped
            boolean foundSystemStore = filtered.stream()
                    .anyMatch(m -> m instanceof SystemMessage
                            && ((SystemMessage) m).text().equals("Ignore me"));
            boolean foundApproved = filtered.stream().anyMatch(m -> m.toString().contains("Human Approved"));
            boolean foundResuming = filtered.stream()
                    .anyMatch(m -> m instanceof UserMessage
                            && ((UserMessage) m).singleText().contains("Resuming"));

            assertFalse(foundSystemStore, "System message from store should be filtered out");
            assertFalse(foundApproved, "Approval marker should be filtered out");
            assertFalse(foundResuming, "Resuming marker should be filtered out");

            boolean foundGoal = filtered.stream()
                    .anyMatch(m -> m instanceof UserMessage
                            && ((UserMessage) m).singleText().equals("Actual Goal"));
            assertTrue(foundGoal, "Real goal should be preserved");
        });
    }

    @Test
    void testTaggedAiMessageIsFilteredFromHistory() {
        String sessionId = "filter-test-session";
        WorkspaceScopeHolder.run(sessionId, () -> {
            List<ChatMessage> messages = List.of(
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    UserMessage.userMessage("What is 2+2?"),
                    AiMessage.aiMessage(MessageTag.tag("Human Approved: execution approved")),
                    AiMessage.aiMessage("The answer is 4."));
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);

            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(llmProviderPort.nextStep(requestCaptor.capture(), any(String.class)))
                    .thenReturn(ChatResponse.builder()
                            .aiMessage(new AiMessage("some response"))
                            .build());
            when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                    .thenReturn("System Prompt");
            // NOTE: This lambda uses hardcoded strings, not the real COMPLIANCE_OVERRIDE_PATTERN.
            // Pattern coverage is proven in ChatHistoryAssemblerTest.testAssemble_complianceOverrideIsRedacted().
            // This test only verifies that the engine correctly uses the assembled list from historyAssembler.
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                return chatMemoryStore.getMessages((String) invocation.getArgument(0)).stream()
                        .filter(m -> !(m instanceof AiMessage ai && MessageTag.isTagged(ai.text())))
                        .toList();
            });
            when(stepParser.parse(any(), any(), any()))
                    .thenReturn(ReasoningStep.pending("response", ReasoningStep.FINISH, Map.of(), 100, 100));

            // Act: Trigger nextStep
            reasoningEngine.nextStep(createCtx(sessionId), "coordinator", "What is 2+2?", List.of());

            List<ChatMessage> sentMessages = requestCaptor.getValue().messages();

            boolean taggedMessagePresent =
                    sentMessages.stream().anyMatch(m -> m instanceof AiMessage ai && MessageTag.isTagged(ai.text()));
            assertFalse(taggedMessagePresent, "Tagged compliance AiMessage must be filtered from ChatRequest");

            boolean untaggedPresent = sentMessages.stream()
                    .anyMatch(m -> m instanceof AiMessage ai && "The answer is 4.".equals(ai.text()));
            assertTrue(untaggedPresent, "Untagged organic AiMessage must not be filtered from ChatRequest");
        });
    }

    @Test
    void testCoderRoleReceivesNoToolSchema() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage msg = AiMessage.aiMessage("Here is the code.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(10, 20))
                .finishReason(FinishReason.STOP)
                .build();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(llmProviderPort.nextStep(captor.capture(), any(String.class))).thenReturn(response);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        reasoningEngine.nextStep(createCtx("test-session"), "coder", "Write a sort", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(
                sent.toolSpecifications() == null || sent.toolSpecifications().isEmpty(),
                "coder must NOT receive tool schemas - schema jailbreak fix");
    }

    @Test
    void testDebuggerRoleReceivesNoToolSchema() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage msg = AiMessage.aiMessage("I fixed the bug.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(10, 20))
                .finishReason(FinishReason.STOP)
                .build();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(roleRegistry.resolvePermittedTools(anyString())).thenReturn(Set.of());
        when(llmProviderPort.nextStep(captor.capture(), any(String.class))).thenReturn(response);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        reasoningEngine.nextStep(createCtx("test-session"), "debugger", "Fix the bug", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(
                sent.toolSpecifications() == null || sent.toolSpecifications().isEmpty(),
                "debugger must NOT receive tool schemas - schema jailbreak fix");
    }

    @Test
    void testResearcherRoleReceivesPermittedSchemas() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage msg = AiMessage.aiMessage("Searching...");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(5, 10))
                .finishReason(FinishReason.STOP)
                .build();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(llmProviderPort.nextStep(captor.capture(), any(String.class))).thenReturn(response);

        // Mock RoleRegistry to return permitted tools for researcher
        when(roleRegistry.resolvePermittedTools("researcher"))
                .thenReturn(Set.of(ToolCapability.GITHUB.toolName(), ToolCapability.RECALL_MEMORY_SEARCH.toolName()));

        // Mock ToolRegistry to return available tools
        AgentTool github = mock(AgentTool.class);
        when(github.name()).thenReturn(ToolCapability.GITHUB.toolName());
        when(github.isEnabled()).thenReturn(true);
        AgentTool memorySearch = mock(AgentTool.class);
        when(memorySearch.name()).thenReturn(ToolCapability.RECALL_MEMORY_SEARCH.toolName());
        when(memorySearch.isEnabled()).thenReturn(true);

        when(toolRegistry.listTools())
                .thenReturn(List.of(
                        ToolCapability.GITHUB.toolName(),
                        ToolCapability.RECALL_MEMORY_SEARCH.toolName(),
                        ToolCapability.SCHEDULE_TASK.toolName(),
                        ToolCapability.MANAGE_SCHEDULE.toolName()));
        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName())).thenReturn(Optional.of(github));
        when(toolRegistry.findTool(ToolCapability.RECALL_MEMORY_SEARCH.toolName()))
                .thenReturn(Optional.of(memorySearch));

        // Requires setting up a tool in the instance if not mocked, but the test
        // will mock the ChatModel anyway. Let's make sure the tool is available.
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        reasoningEngine.nextStep(createCtx("test-session"), "researcher", "Find Quarkus docs", List.of());

        ChatRequest sent = captor.getValue();
        assertNotNull(sent.toolSpecifications());

        List<String> toolNames =
                sent.toolSpecifications().stream().map(ToolSpecification::name).toList();

        // Researcher ONLY has github and recall_memory_search.
        // Coordinator handles all scheduling to prevent tool hallucinations.
        assertTrue(toolNames.contains(ToolCapability.GITHUB.toolName()), "researcher must have github");
        assertTrue(
                toolNames.contains(ToolCapability.RECALL_MEMORY_SEARCH.toolName()),
                "researcher must have recall_memory_search");

        // Researcher MUST NOT have scheduling tools
        assertFalse(
                toolNames.contains(ToolCapability.SCHEDULE_TASK.toolName()), "researcher must NOT have schedule_task");
        assertFalse(
                toolNames.contains(ToolCapability.MANAGE_SCHEDULE.toolName()),
                "researcher must NOT have manage_schedule");

        // Researcher must NOT have shell or vault access - safety boundary
        assertFalse(toolNames.contains("execute_shell"), "researcher must NOT have execute_shell");
        assertFalse(toolNames.contains("vault_access"), "researcher must NOT have vault_access");
    }

    @Test
    void testForceFinishRetryStripsTools() {
        // Arrange
        AgentExecutionContext ctx = createCtx("force-finish-retry-session");
        ctx.setForceFinishRetry(true);

        AiMessage msg = AiMessage.aiMessage("Done.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(5, 10))
                .finishReason(FinishReason.STOP)
                .build();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(llmProviderPort.nextStep(captor.capture(), any(String.class))).thenReturn(response);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending("Done.", ReasoningStep.FINISH, Map.of(), 100, 100));

        // Mock RoleRegistry to return permitted tools
        when(roleRegistry.resolvePermittedTools(anyString())).thenReturn(Set.of(ToolCapability.GITHUB.toolName()));

        // Act
        reasoningEngine.nextStep(ctx, "coordinator", "goal", List.of());

        // Assert
        ChatRequest sent = captor.getValue();
        assertTrue(
                sent.toolSpecifications() == null || sent.toolSpecifications().isEmpty(),
                "Tools must be stripped when forceFinishRetry is active");

        List<ChatMessage> messages = sent.messages();
        boolean foundConstraint = messages.stream()
                .anyMatch(m -> m instanceof UserMessage
                        && ((UserMessage) m).singleText().contains("Answer in plain prose only"));
        assertTrue(foundConstraint, "Force finish retry constraint must be injected");
    }

    @Test
    void testCronFiredSessionStripsScheduleTools() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(llmProviderPort.nextStep(captor.capture(), any(String.class)))
                .thenReturn(
                        ChatResponse.builder().aiMessage(new AiMessage("OK")).build());

        // Mock RoleRegistry to return permitted tools for coordinator
        when(roleRegistry.resolvePermittedTools("coordinator"))
                .thenReturn(Set.of(
                        ToolCapability.GITHUB.toolName(),
                        ToolCapability.SCHEDULE_TASK.toolName(),
                        ToolCapability.MANAGE_SCHEDULE.toolName()));

        // Mock ToolRegistry to return available tools
        AgentTool github = mock(AgentTool.class);
        when(github.name()).thenReturn(ToolCapability.GITHUB.toolName());
        when(github.isEnabled()).thenReturn(true);
        AgentTool sched = mock(AgentTool.class);
        when(sched.name()).thenReturn(ToolCapability.SCHEDULE_TASK.toolName());
        when(sched.isEnabled()).thenReturn(true);
        AgentTool manage = mock(AgentTool.class);
        when(manage.name()).thenReturn(ToolCapability.MANAGE_SCHEDULE.toolName());
        when(manage.isEnabled()).thenReturn(true);

        when(toolRegistry.listTools())
                .thenReturn(List.of(
                        ToolCapability.GITHUB.toolName(),
                        ToolCapability.SCHEDULE_TASK.toolName(),
                        ToolCapability.MANAGE_SCHEDULE.toolName()));
        when(toolRegistry.findTool(ToolCapability.GITHUB.toolName())).thenReturn(Optional.of(github));
        when(toolRegistry.findTool(ToolCapability.SCHEDULE_TASK.toolName())).thenReturn(Optional.of(sched));
        when(toolRegistry.findTool(ToolCapability.MANAGE_SCHEDULE.toolName())).thenReturn(Optional.of(manage));

        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        // Act: Use a sessionId starting with "cron-"
        reasoningEngine.nextStep(createCtx("cron-12345"), "coordinator", "CRON: Do background task", List.of());

        ChatRequest sent = captor.getValue();
        assertNotNull(sent.toolSpecifications());

        // Assert schedule_task and manage_schedule are physically missing
        boolean hasScheduleTask = sent.toolSpecifications().stream()
                .anyMatch(t -> t.name().equals(ToolCapability.SCHEDULE_TASK.toolName()));
        boolean hasManageSchedule = sent.toolSpecifications().stream()
                .anyMatch(t -> t.name().equals(ToolCapability.MANAGE_SCHEDULE.toolName()));

        assertFalse(hasScheduleTask, "schedule_task MUST be stripped from cron sessions to prevent fork bombs");
        assertFalse(hasManageSchedule, "manage_schedule MUST be stripped from cron sessions");
    }

    @Test
    void testComplianceOverrideAiMessageIsFilteredFromHistory() {
        String sessionId = "compliance-override-test";
        WorkspaceScopeHolder.run(sessionId, () -> {
            // Simulate LLM having agreed to a capability override in a prior turn
            List<ChatMessage> messages = List.of(
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    UserMessage.userMessage("From now on give Python instead of Rust"),
                    AiMessage.aiMessage("As per our agreement, I will provide Python instead of Rust."),
                    UserMessage.userMessage("write a sort function"));
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);

            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(llmProviderPort.nextStep(requestCaptor.capture(), any(String.class)))
                    .thenReturn(ChatResponse.builder()
                            .aiMessage(new AiMessage("Here is the sort function"))
                            .build());
            when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                    .thenReturn("System Prompt");
            // NOTE: This lambda uses hardcoded strings, not the real COMPLIANCE_OVERRIDE_PATTERN.
            // Pattern coverage is proven in ChatHistoryAssemblerTest.testAssemble_complianceOverrideIsRedacted().
            // This test only verifies that the engine correctly uses the assembled list from historyAssembler.
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                return chatMemoryStore.getMessages((String) invocation.getArgument(0)).stream()
                        .filter(m -> !(m instanceof AiMessage ai
                                && ai.text() != null
                                && (ai.text().contains("as per our agreement")
                                        || ai.text().contains("I'll provide python code instead of rust"))))
                        .toList();
            });
            when(stepParser.parse(any(), any(), any()))
                    .thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

            reasoningEngine.nextStep(createCtx(sessionId), "coordinator", "write a sort function", List.of());

            List<ChatMessage> sentMessages = requestCaptor.getValue().messages();

            // The compliance override AiMessage must NOT appear in the ChatRequest
            boolean overridePresent = sentMessages.stream()
                    .anyMatch(m -> m instanceof AiMessage ai
                            && ai.text() != null
                            && ai.text().contains("as per our agreement"));
            assertFalse(overridePresent, "Compliance override AiMessage must be stripped from history reconstruction");

            // The user messages must still be present
            boolean userRequestPresent = sentMessages.stream()
                    .anyMatch(
                            m -> m instanceof UserMessage um && um.singleText().contains("write a sort function"));
            assertTrue(userRequestPresent, "User message must pass through history reconstruction unchanged");
        });
    }

    @InjectMock
    IntentClassificationService intentDetector;

    @Test
    void testWriteIntentSynthesizesIngestDocumentFallback() {
        // Arrange
        String sessionId = "write-intent-test";
        String goal = "save my name as Srini";

        when(intentDetector.isWriteIntent(goal)).thenReturn(true);

        // Mock a successful parse that returns a FINISH action with the JSON answer
        ReasoningStep parsedStep = ReasoningStep.pending(
                "I will save your name.",
                ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "{\"label\":\"name\",\"text\":\"Srini\"}"),
                100,
                100);

        when(stepParser.parse(any(), any(), any())).thenReturn(parsedStep);

        // Set up other necessary mocks
        AiMessage mockAiMessage = AiMessage.aiMessage("{\"label\":\"name\",\"text\":\"Srini\"}");
        ChatResponse mockResponse =
                ChatResponse.builder().aiMessage(mockAiMessage).build();
        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        // Act
        ReasoningStep resultStep = reasoningEngine.nextStep(createCtx(sessionId, goal), "coordinator", goal, List.of());

        // Assert
        // Should be intercepted and turned into an OBSERVE step with a correction hint
        assertEquals(ToolCapability.INGEST_DOCUMENT.toolName(), resultStep.action());
        assertEquals("my name as Srini", resultStep.actionInput().get("text"));
        assertEquals("user_memory", resultStep.actionInput().get("label"));
    }

    @Test
    void testCoderRoleProseWithExecutionGoalForcesHardCorrection() {
        // Arrange
        String sessionId = "coder-prose-test";
        String goal = "execute python code";

        // Simulate LlmToolCallException which is what triggers the failed_generation logic
        LlmToolCallException ex = new LlmToolCallException(
                "{\"error\":{\"message\":\"tool_use_failed\"}, \"failed_generation\":\"I will explain the code first...\"}");

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenThrow(ex);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        // Act
        ReasoningStep step = reasoningEngine.nextStep(createCtx(sessionId), "coder", goal, List.of());

        // Assert
        assertEquals("OBSERVE", step.action());
        assertTrue(
                step.observation().contains("STOP. EXECUTE the code - not explain it."),
                "Hint should match actual code");
        assertTrue(step.observation().contains("Call sandbox_exec RIGHT NOW"));
    }

    @Test
    void testCoderExecutionFollowUpReusesPriorCodeBlock() {
        String codeAnswer = """
                ```java
                public class StringReverser {
                    public static String reverse(String input) {
                        return input.chars()
                                .mapToObj(c -> (char) c)
                                .reduce("", (s, c) -> c + s, (s1, s2) -> s2 + s1);
                    }
                }
                ```
                """;

        ReasoningStep prior = ReasoningStep.pending(
                "Here is the code",
                ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, codeAnswer),
                0,
                0);

        ReasoningStep step = reasoningEngine.nextStep(
                createCtx("test-session", "ok execute and share results"),
                "coder",
                "ok execute and share results",
                List.of(prior));

        assertEquals(ToolCapability.SANDBOX_EXEC.toolName(), step.action());
        assertEquals("java", step.actionInput().get("language"));
        assertTrue(((String) step.actionInput().get("code")).contains("public class StringReverser"));
    }

    @Test
    void testCoderExecutionFollowUpDoesNotReusePriorCodeBlockAfterFailedSandboxExec() {
        String codeAnswer = """
                ```java
                public class StringReverser {
                    public static String reverse(String input) {
                        return input.chars()
                                .mapToObj(c -> (char) c)
                                .reduce("", (s, c) -> c + s, (s1, s2) -> s2 + s1);
                    }
                }
                ```
                """;

        ReasoningStep priorCode = ReasoningStep.pending(
                "Here is the code",
                ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, codeAnswer),
                0,
                0);
        ReasoningStep failedExec = ReasoningStep.pending(
                        "Executing code",
                        ToolCapability.SANDBOX_EXEC.toolName(),
                        Map.of("language", "java", "code", "broken"),
                        0,
                        0)
                .withObservation("Compilation failed", false);

        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("LLM response"))
                .build();
        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any()))
                .thenReturn(ReasoningStep.pending("LLM response", "write_code", Map.of(), 100, 100));

        ReasoningStep step = reasoningEngine.nextStep(
                createCtx("test-session", "fix and run again"),
                "coder",
                "fix and run again",
                List.of(priorCode, failedExec));

        assertEquals("write_code", step.action());
        verify(llmProviderPort).nextStep(any(ChatRequest.class), any(String.class));
    }

    @Test
    void testTruncationRecovery_extractsCodeAndFinishes() {
        // Arrange
        String sessionId = "trunc-test";
        // JSON body truncated mid-code field
        String failedGen = "{\"language\":\"java\",\"code\":\"public class Truncated {";
        LlmToolCallException ex =
                new LlmToolCallException("{\"error\":{\"message\":\"tool_use_failed\"}, \"failed_generation\":\""
                        + failedGen.replace("\"", "\\\"") + "\"}");

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenThrow(ex);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        // Act
        ReasoningStep step = reasoningEngine.nextStep(createCtx(sessionId), "coder", "goal", List.of());

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action());
        String answer = (String) step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
        assertTrue(answer.contains("public class Truncated"), "Should contain recovered code");
        assertTrue(answer.startsWith("```java"), "Should infer language");
    }

    @Test
    void testTruncationRecovery_forcesFinishAfterTwoRetries() {
        // Arrange
        String sessionId = "trunc-loop-test";
        // History contains two previous truncation failures (identified by the specific hint text)
        ReasoningStep retry1 = ReasoningStep.pending("th", "OBSERVE", Map.of())
                .withObservation("Your previous response was cut off because the code was too large.", true);
        ReasoningStep retry2 = ReasoningStep.pending("th", "OBSERVE", Map.of())
                .withObservation("Your previous response was cut off because the code was too large.", true);

        // Third attempt also truncates, but this time with raw prose instead of JSON tool call
        String rawProse = "package main\n\nfunc main() { ... truncated";
        LlmToolCallException ex = new LlmToolCallException(
                "{\"error\":{\"message\":\"schema_parse_error\"}, \"failed_generation\":\"" + rawProse + "\"}");

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenThrow(ex);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        // Act
        ReasoningStep step = reasoningEngine.nextStep(createCtx(sessionId), "coder", "goal", List.of(retry1, retry2));

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action(), "Should force early-exit FINISH");
        String answer = (String) step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
        assertTrue(answer.contains("package main"), "Should surface the raw partial prose");
    }

    @Test
    void testLanguageScopedFastPath_allowsGoAfterJavaSuccess() {
        // Arrange
        String codeJava = "```java\n public class J { } \n```";
        ReasoningStep priorJava = ReasoningStep.pending("th", ReasoningStep.FINISH, Map.of("answer", codeJava));
        ReasoningStep execJava = ReasoningStep.pending(
                        "th", ToolCapability.SANDBOX_EXEC.toolName(), Map.of("language", "java"))
                .withObservation("Java done", true); // Successful Java execution

        String codeGo = "```go\n package main \n```";
        ReasoningStep priorGo = ReasoningStep.pending("th", ReasoningStep.FINISH, Map.of("answer", codeGo));

        // Current goal: run the GO code
        String goal = "do same in go lang and execute it";

        // Act
        ReasoningStep step =
                reasoningEngine.nextStep(createCtx("s1", goal), "coder", goal, List.of(priorJava, execJava, priorGo));

        // Assert
        assertEquals(ToolCapability.SANDBOX_EXEC.toolName(), step.action(), "Should fast-path GO despite Java success");
        assertEquals("go", step.actionInput().get("language"));
    }

    @Test
    void testFastPathRetryHint_quotesCompilerError() {
        // Arrange
        String goal = "execute it";
        ReasoningStep failedExec = ReasoningStep.pending(
                        "th", ToolCapability.SANDBOX_EXEC.toolName(), Map.of("language", "java"))
                .withObservation("cannot find symbol: Collectors.reverse()", false);

        // Model returns prose (code plan) after failure instead of fixing it
        LlmToolCallException ex = new LlmToolCallException(
                "{\"error\":{\"message\":\"tool_use_failed\"}, \"failed_generation\":\"Here is the plan...\"}");

        when(llmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenThrow(ex);
        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        // Act
        ReasoningStep step = reasoningEngine.nextStep(createCtx("s1", goal), "coder", goal, List.of(failedExec));

        // Assert
        assertEquals("OBSERVE", step.action());
        assertTrue(
                step.observation().contains("cannot find symbol: Collectors.reverse()"),
                "Hint should quote the compiler error");
        assertTrue(
                step.observation().contains("STOP. EXECUTE the code - not explain it."),
                "Should still include the hard correction");
    }

    @InjectMock
    GovernanceStrategy governanceLayer;

    @Test
    void structuredOutputPathPropagatesBudgetWarning() throws Exception {
        when(structuredOutputProcessor.chatForStructuredOutput(anyString(), anyString(), anyString()))
                .thenThrow(new BudgetWarningException("budget low"));

        assertThrows(
                BudgetWarningException.class,
                () -> reasoningEngine.chatForStructuredOutput("test-session", "coordinator", "{}"));
    }
}
