package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@QuarkusTest
class LangChainReasoningEngineTest {

    @Inject
    ReasoningEngine reasoningEngine;

    @InjectMock
    ChatModel chatModel;

    @InjectMock
    SystemPromptBuilder promptBuilder;

    @InjectMock
    ChatHistoryAssembler historyAssembler;

    @InjectMock
    ReasoningStepParser stepParser;

    @InjectMock
    RoleRegistry roleRegistry;

    @InjectMock
    ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        // Default behavior for mocks
        when(roleRegistry.permittedTools(any())).thenReturn(Collections.emptySet());
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
    }

    private AgentExecutionContext createCtx(String sessionId) {
        return new AgentExecutionContext(sessionId, "user-1", "chat-1", "coordinator", "goal", AutonomyLevel.FULL, 5, null, null, null, null);
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

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("I have finished the task", ReasoningStep.FINISH, Map.of("answer", "I have finished the task"), 100, 100));

        // Act
        ReasoningStep step = reasoningEngine.nextStep(createCtx("test-session"), "coordinator", "Do something", List.of());

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
                .name("github")
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

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("I will use github", "github", Map.of("repo", "test/repo"), 100, 100));

        // Act
        ReasoningStep step = reasoningEngine.nextStep(createCtx("test-session"), "coordinator", "Do something", List.of());

        // Assert
        assertEquals("github", step.action());
        assertEquals("I will use github", step.thought());
        assertEquals(100, step.inputTokens());
        assertEquals(100, step.outputTokens());
        assertTrue(step.actionInput().containsKey("repo"));
        assertEquals("test/repo", step.actionInput().get("repo"));
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
                UserMessage.userMessage("Resuming task after approval")
            );
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(storedMessages);

            AiMessage mockAiMessage = AiMessage.from("Processing");
            ChatResponse mockResponse = ChatResponse.builder()
                    .aiMessage(mockAiMessage)
                    .build();

            // Capture the request to verify filtered messages
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(chatModel.chat(requestCaptor.capture())).thenReturn(mockResponse);
                when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
            // For the filter test, we need the assembler to actually return the filtered messages if it were the real one,
            // but here we are mocking the engine's call to assembler. 
            // Wait, the engine calls historyAssembler.assemble(). 
            // The test verifies if messages are filtered in the ChatRequest sent to LLM.
            // So we should make the mock assembler return the messages we expect to be filtered (or NOT filtered).
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                List<ChatMessage> storeMsgs = chatMemoryStore.getMessages((String)invocation.getArgument(0));
                return storeMsgs.stream()
                    .filter(m -> !(m instanceof SystemMessage))
                    .filter(m -> !(m instanceof AiMessage ai && MessageTag.isTagged(ai.text())))
                    .filter(m -> !(m instanceof UserMessage user && (user.singleText().contains("Resuming") || user.singleText().contains("The tool execution has been approved"))))
                    .toList();
            });
            when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("Processing", ReasoningStep.FINISH, Map.of(), 100, 100));

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
            boolean foundSystemStore = filtered.stream().anyMatch(m -> m instanceof SystemMessage && ((SystemMessage)m).text().equals("Ignore me"));
            boolean foundApproved = filtered.stream().anyMatch(m -> m.toString().contains("Human Approved"));
            boolean foundResuming = filtered.stream().anyMatch(m -> m instanceof UserMessage && ((UserMessage)m).singleText().contains("Resuming"));

            assertFalse(foundSystemStore, "System message from store should be filtered out");
            assertFalse(foundApproved, "Approval marker should be filtered out");
            assertFalse(foundResuming, "Resuming marker should be filtered out");
            
            boolean foundGoal = filtered.stream().anyMatch(m -> m instanceof UserMessage && ((UserMessage)m).singleText().equals("Actual Goal"));
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
                AiMessage.aiMessage("The answer is 4.")
            );
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);

            ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
            when(chatModel.chat(requestCaptor.capture()))
                .thenReturn(ChatResponse.builder().aiMessage(new AiMessage("some response")).build());
                when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
            // NOTE: This lambda uses hardcoded strings, not the real COMPLIANCE_OVERRIDE_PATTERN.
            // Pattern coverage is proven in ChatHistoryAssemblerTest.testAssemble_complianceOverrideIsRedacted().
            // This test only verifies that the engine correctly uses the assembled list from historyAssembler.
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                return chatMemoryStore.getMessages((String)invocation.getArgument(0)).stream()
                    .filter(m -> !(m instanceof AiMessage ai && MessageTag.isTagged(ai.text())))
                    .toList();
            });
            when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("response", ReasoningStep.FINISH, Map.of(), 100, 100));

            // Act: Trigger nextStep
            reasoningEngine.nextStep(createCtx(sessionId), "coordinator", "What is 2+2?", List.of());

            List<ChatMessage> sentMessages = requestCaptor.getValue().messages();

            boolean taggedMessagePresent = sentMessages.stream()
                .anyMatch(m -> m instanceof AiMessage ai
                    && MessageTag.isTagged(ai.text()));
            assertFalse(taggedMessagePresent,
                "Tagged compliance AiMessage must be filtered from ChatRequest");

            boolean untaggedPresent = sentMessages.stream()
                .anyMatch(m -> m instanceof AiMessage ai
                    && "The answer is 4.".equals(ai.text()));
            assertTrue(untaggedPresent,
                "Untagged organic AiMessage must not be filtered from ChatRequest");
        });
    }

    @Test
    void testCoderRoleReceivesNoToolSchema() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage msg = AiMessage.aiMessage("Here is the code.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(10, 20))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(response);
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        reasoningEngine.nextStep(createCtx("test-session"), "coder", "Write a sort", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(sent.toolSpecifications() == null 
                || sent.toolSpecifications().isEmpty(),
                "coder must NOT receive tool schemas — schema jailbreak fix");
    }

    @Test
    void testDebuggerRoleReceivesNoToolSchema() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage msg = AiMessage.aiMessage("I fixed the bug.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(10, 20))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(roleRegistry.permittedTools(any())).thenReturn(Set.of());                
        when(chatModel.chat(captor.capture())).thenReturn(response);
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        reasoningEngine.nextStep(createCtx("test-session"), "debugger", "Fix the bug", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(sent.toolSpecifications() == null 
                || sent.toolSpecifications().isEmpty(),
                "debugger must NOT receive tool schemas — schema jailbreak fix");
    }

    @Test
    void testResearcherRoleReceivesPermittedSchemas() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        AiMessage msg = AiMessage.aiMessage("Searching...");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(5, 10))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(response);

        // Mock RoleRegistry to return permitted tools for researcher
        when(roleRegistry.permittedTools(AgentRoleEnum.RESEARCHER))
                .thenReturn(Set.of("github", "memory_search"));

        // Mock ToolRegistry to return available tools
        AgentTool github = Mockito.mock(AgentTool.class);
        when(github.name()).thenReturn("github");
        AgentTool memorySearch = Mockito.mock(AgentTool.class);
        when(memorySearch.name()).thenReturn("memory_search");
        
        when(toolRegistry.listTools()).thenReturn(List.of("github", "memory_search", "schedule_task", "manage_schedule"));
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(github));
        when(toolRegistry.findTool("memory_search")).thenReturn(Optional.of(memorySearch));

        // Requires setting up a tool in the instance if not mocked, but the test 
        // will mock the ChatModel anyway. Let's make sure the tool is available.
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        reasoningEngine.nextStep(createCtx("test-session"), "researcher", "Find Quarkus docs", List.of());

        ChatRequest sent = captor.getValue();
        assertNotNull(sent.toolSpecifications());
        
        List<String> toolNames = sent.toolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList();
                
        // Phase 46 Fix: Researcher ONLY has github and memory_search. 
        // Coordinator handles all scheduling to prevent tool hallucinations.
        assertTrue(toolNames.contains("github"),        "researcher must have github");
        assertTrue(toolNames.contains("memory_search"), "researcher must have memory_search");
        
        // Researcher MUST NOT have scheduling tools
        assertFalse(toolNames.contains("schedule_task"),   "researcher must NOT have schedule_task");
        assertFalse(toolNames.contains("manage_schedule"), "researcher must NOT have manage_schedule");
        
        // Researcher must NOT have shell or vault access — safety boundary
        assertFalse(toolNames.contains("execute_shell"), "researcher must NOT have execute_shell");
        assertFalse(toolNames.contains("vault_access"),  "researcher must NOT have vault_access");
    }

    @Test
    void testCronFiredSessionStripsScheduleTools() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(
                ChatResponse.builder().aiMessage(new AiMessage("OK")).build());

        // Mock RoleRegistry to return permitted tools for coordinator
        when(roleRegistry.permittedTools(AgentRoleEnum.COORDINATOR))
                .thenReturn(Set.of("github", "schedule_task", "manage_schedule"));

        // Mock ToolRegistry to return available tools
        AgentTool github = Mockito.mock(AgentTool.class);
        when(github.name()).thenReturn("github");
        AgentTool sched = Mockito.mock(AgentTool.class);
        when(sched.name()).thenReturn("schedule_task");
        AgentTool manage = Mockito.mock(AgentTool.class);
        when(manage.name()).thenReturn("manage_schedule");
        
        when(toolRegistry.listTools()).thenReturn(List.of("github", "schedule_task", "manage_schedule"));
        when(toolRegistry.findTool("github")).thenReturn(Optional.of(github));
        when(toolRegistry.findTool("schedule_task")).thenReturn(Optional.of(sched));
        when(toolRegistry.findTool("manage_schedule")).thenReturn(Optional.of(manage));

        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

        // Act: Use a sessionId starting with "cron-"
        reasoningEngine.nextStep(createCtx("cron-12345"), "coordinator", "CRON: Do background task", List.of());

        ChatRequest sent = captor.getValue();
        assertNotNull(sent.toolSpecifications());
        
        // Assert schedule_task and manage_schedule are physically missing
        boolean hasScheduleTask = sent.toolSpecifications().stream()
                .anyMatch(t -> t.name().equals("schedule_task"));
        boolean hasManageSchedule = sent.toolSpecifications().stream()
                .anyMatch(t -> t.name().equals("manage_schedule"));
        
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
                UserMessage.userMessage("write a sort function")
            );
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);

            ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
            when(chatModel.chat(requestCaptor.capture()))
                .thenReturn(ChatResponse.builder()
                    .aiMessage(new AiMessage("Here is the sort function"))
                    .build());
            when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
            // NOTE: This lambda uses hardcoded strings, not the real COMPLIANCE_OVERRIDE_PATTERN.
            // Pattern coverage is proven in ChatHistoryAssemblerTest.testAssemble_complianceOverrideIsRedacted().
            // This test only verifies that the engine correctly uses the assembled list from historyAssembler.
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                return chatMemoryStore.getMessages((String)invocation.getArgument(0)).stream()
                    .filter(m -> !(m instanceof AiMessage ai && ai.text() != null && (ai.text().contains("as per our agreement") || ai.text().contains("I'll provide python code instead of rust"))))
                    .toList();
            });
            when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 100, 100));

            reasoningEngine.nextStep(createCtx(sessionId), "coordinator", "write a sort function", List.of());

            List<ChatMessage> sentMessages = requestCaptor.getValue().messages();

            // The compliance override AiMessage must NOT appear in the ChatRequest
            boolean overridePresent = sentMessages.stream()
                .anyMatch(m -> m instanceof AiMessage ai
                    && ai.text() != null
                    && ai.text().contains("as per our agreement"));
            assertFalse(overridePresent,
                "Compliance override AiMessage must be stripped from history reconstruction");

            // The user messages must still be present
            boolean userRequestPresent = sentMessages.stream()
                .anyMatch(m -> m instanceof UserMessage um
                    && um.singleText().contains("write a sort function"));
            assertTrue(userRequestPresent,
                "User message must pass through history reconstruction unchanged");
        });
    }

    @InjectMock
    IntentClassificationService intentClassifier;

    @Test
    void testWriteIntentForcesIngestDocumentCorrection() {
        // Arrange
        String sessionId = "write-intent-test";
        String goal = "save my name as Srini";
        
        when(intentClassifier.isWriteIntent(goal)).thenReturn(true);
        
        // Mock a successful parse that returns a FINISH action with the JSON answer
        ReasoningStep parsedStep = ReasoningStep.pending(
            "I will save your name.", 
            ReasoningStep.FINISH, 
            Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "{\"label\":\"name\",\"text\":\"Srini\"}"), 
            100, 100);
        
        when(stepParser.parse(any(), any(), any())).thenReturn(parsedStep);
        
        // Set up other necessary mocks
        AiMessage mockAiMessage = AiMessage.aiMessage("{\"label\":\"name\",\"text\":\"Srini\"}");
        ChatResponse mockResponse = ChatResponse.builder().aiMessage(mockAiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        // Act
        ReasoningStep resultStep = reasoningEngine.nextStep(createCtx(sessionId), "coordinator", goal, List.of());

        // Assert
        // Should be intercepted and turned into an OBSERVE step with a correction hint
        assertEquals("OBSERVE", resultStep.action());
        assertTrue(resultStep.observation().contains("CORRECTION: The user wants to STORE data. You MUST call ingest_document"));
    }

    @InjectMock
    GovernanceStrategy governanceLayer;

    @Test
    void structuredOutputPathPropagatesBudgetWarning() throws Exception {
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("result"))
                .tokenUsage(new TokenUsage(100, 100))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("sys prompt");
        
        Mockito.doThrow(new BudgetWarningException("budget low"))
            .when(governanceLayer).recordLlmCall(anyString(), any(Integer.class), any(Integer.class));
            
        AgentOtelContext mockOtelContext = Mockito.mock(AgentOtelContext.class);
        Field field = LangChainReasoningEngine.class.getDeclaredField("otelContext");
        field.setAccessible(true);
        field.set(reasoningEngine, mockOtelContext);

        assertThrows(BudgetWarningException.class, () ->
            ((LangChainReasoningEngine) reasoningEngine).chatForStructuredOutput("test-session", "coordinator", "{}"));
    }
}

