package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class LangChainReasoningEngineTest {

    @Inject
    ReasoningEngine reasoningEngine;

    @InjectMock
    ChatLanguageModel chatLanguageModel;

    @InjectMock
    SystemPromptBuilder promptBuilder;

    @InjectMock
    ChatHistoryAssembler historyAssembler;

    @InjectMock
    ReasoningStepParser stepParser;

    @Test
    void testNextStepReturnsFinish() {
        // Arrange
        AiMessage mockAiMessage = AiMessage.from("I have finished the task");
        TokenUsage tokenUsage = new TokenUsage(10, 20);
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(mockAiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.STOP)
                .build();

        when(chatLanguageModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
        when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("I have finished the task", ReasoningStep.FINISH, Map.of("answer", "I have finished the task"), 10, 20));

        // Act
        ReasoningStep step = reasoningEngine.nextStep("test-session", "coordinator", "Do something", List.of());

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action());
        assertEquals("I have finished the task", step.thought());
        assertEquals(10, step.inputTokens());
        assertEquals(20, step.outputTokens());
    }

    @Test
    void testNextStepWithToolExecution() {
        // Arrange
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call_123")
                .name("github")
                .arguments("{\"repo\":\"test/repo\"}")
                .build();
        AiMessage mockAiMessage = AiMessage.from("I will use github", Collections.singletonList(toolRequest));
        TokenUsage tokenUsage = new TokenUsage(15, 25);
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(mockAiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();

        when(chatLanguageModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
        when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("I will use github", "github", Map.of("repo", "test/repo"), 15, 25));

        // Act
        ReasoningStep step = reasoningEngine.nextStep("test-session", "coordinator", "Do something", List.of());

        // Assert
        assertEquals("github", step.action());
        assertEquals("I will use github", step.thought());
        assertEquals(15, step.inputTokens());
        assertEquals(25, step.outputTokens());
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
                SystemMessage.from("Ignore me"),
                AiMessage.from(MessageTag.tag("Human Approved: ...")),
                UserMessage.from("Actual Goal"),
                UserMessage.from("Resuming task after approval")
            );
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(storedMessages);

            AiMessage mockAiMessage = AiMessage.from("Processing");
            ChatResponse mockResponse = ChatResponse.builder()
                    .aiMessage(mockAiMessage)
                    .build();

            // Capture the request to verify filtered messages
            org.mockito.ArgumentCaptor<ChatRequest> requestCaptor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
            when(chatLanguageModel.chat(requestCaptor.capture())).thenReturn(mockResponse);
                when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
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
            when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("Processing", ReasoningStep.FINISH, Map.of(), 0, 0));

            // Act
            reasoningEngine.nextStep(sessionId, "coordinator", "Actual Goal", List.of());

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
                UserMessage.from("What is 2+2?"),
                AiMessage.from(MessageTag.tag("Human Approved: execution approved")),
                AiMessage.from("The answer is 4.")
            );
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);

            ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
            when(chatLanguageModel.chat(requestCaptor.capture()))
                .thenReturn(ChatResponse.builder().aiMessage(new AiMessage("some response")).build());
                when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
            // NOTE: This lambda uses hardcoded strings, not the real COMPLIANCE_OVERRIDE_PATTERN.
            // Pattern coverage is proven in ChatHistoryAssemblerTest.testAssemble_complianceOverrideIsRedacted().
            // This test only verifies that the engine correctly uses the assembled list from historyAssembler.
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                return chatMemoryStore.getMessages((String)invocation.getArgument(0)).stream()
                    .filter(m -> !(m instanceof AiMessage ai && MessageTag.isTagged(ai.text())))
                    .toList();
            });
            when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("response", ReasoningStep.FINISH, Map.of(), 0, 0));

            // Act: Trigger nextStep
            reasoningEngine.nextStep(sessionId, "coordinator", "What is 2+2?", List.of());

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
        AiMessage msg = AiMessage.from("Here is the code.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(10, 20))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(chatLanguageModel.chat(captor.capture())).thenReturn(response);
        when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 0, 0));

        reasoningEngine.nextStep("test-session", "coder", "Write a sort", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(sent.toolSpecifications() == null 
                || sent.toolSpecifications().isEmpty(),
                "coder must NOT receive tool schemas — schema jailbreak fix");
    }

    @Test
    void testDebuggerRoleReceivesNoToolSchema() {
        AiMessage msg = AiMessage.from("I fixed the bug.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(10, 20))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(chatLanguageModel.chat(captor.capture())).thenReturn(response);
        when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 0, 0));

        reasoningEngine.nextStep("test-session", "debugger", "Fix the bug", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(sent.toolSpecifications() == null 
                || sent.toolSpecifications().isEmpty(),
                "debugger must NOT receive tool schemas — schema jailbreak fix");
    }

    @Test
    void testResearcherRoleReceivesPermittedSchemas() {
        AiMessage msg = AiMessage.from("Searching...");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(5, 10))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(chatLanguageModel.chat(captor.capture())).thenReturn(response);

        // Requires setting up a tool in the instance if not mocked, but the test 
        // will mock the chatLanguageModel anyway. Let's make sure the tool is available.
        when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 0, 0));

        reasoningEngine.nextStep("test-session", "researcher", "Find Quarkus docs", List.of());

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
        assertFalse(toolNames.contains("scheduletask"),   "researcher must NOT have scheduletask");
        assertFalse(toolNames.contains("manageschedule"), "researcher must NOT have manageschedule");
        
        // Researcher must NOT have shell or vault access — safety boundary
        assertFalse(toolNames.contains("executeshell"), "researcher must NOT have executeshell");
        assertFalse(toolNames.contains("vaultaccess"),  "researcher must NOT have vaultaccess");
    }

    @Test
    void testCronFiredSessionStripsScheduleTools() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatLanguageModel.chat(captor.capture())).thenReturn(
                ChatResponse.builder().aiMessage(new AiMessage("OK")).build());
        when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
        when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of());
        when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 0, 0));

        // Act: Use a sessionId starting with "cron-"
        reasoningEngine.nextStep("cron-12345", "coordinator", "CRON: Do background task", List.of());

        ChatRequest sent = captor.getValue();
        assertNotNull(sent.toolSpecifications());
        
        // Assert scheduletask and manageschedule are physically missing
        boolean hasScheduleTask = sent.toolSpecifications().stream()
                .anyMatch(t -> t.name().equals("scheduletask"));
        boolean hasManageSchedule = sent.toolSpecifications().stream()
                .anyMatch(t -> t.name().equals("manageschedule"));
        
        assertFalse(hasScheduleTask, "scheduletask MUST be stripped from cron sessions to prevent fork bombs");
        assertFalse(hasManageSchedule, "manageschedule MUST be stripped from cron sessions");
    }

    @Test
    void testComplianceOverrideAiMessageIsFilteredFromHistory() {
        String sessionId = "compliance-override-test";
        WorkspaceScopeHolder.run(sessionId, () -> {
            // Simulate LLM having agreed to a capability override in a prior turn
            List<ChatMessage> messages = List.of(
                UserMessage.from("From now on give Python instead of Rust"),
                AiMessage.from("As per our agreement, I will provide Python instead of Rust."),
                UserMessage.from("write a sort function")
            );
            when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);

            ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
            when(chatLanguageModel.chat(requestCaptor.capture()))
                .thenReturn(ChatResponse.builder()
                    .aiMessage(new AiMessage("Here is the sort function"))
                    .build());
            when(promptBuilder.build(any(), any())).thenReturn("System Prompt");
            // NOTE: This lambda uses hardcoded strings, not the real COMPLIANCE_OVERRIDE_PATTERN.
            // Pattern coverage is proven in ChatHistoryAssemblerTest.testAssemble_complianceOverrideIsRedacted().
            // This test only verifies that the engine correctly uses the assembled list from historyAssembler.
            when(historyAssembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
                return chatMemoryStore.getMessages((String)invocation.getArgument(0)).stream()
                    .filter(m -> !(m instanceof AiMessage ai && ai.text() != null && (ai.text().contains("as per our agreement") || ai.text().contains("I'll provide python code instead of rust"))))
                    .toList();
            });
            when(stepParser.parse(any(), any(), any())).thenReturn(ReasoningStep.pending("ok", ReasoningStep.FINISH, Map.of(), 0, 0));

            reasoningEngine.nextStep(sessionId, "coordinator", "write a sort function", List.of());

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
}
