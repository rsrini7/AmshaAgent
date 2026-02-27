package com.arcclaw.core.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.model.output.FinishReason;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.arcclaw.core.state.CompactionSummaryRepository;
import java.util.Optional;

import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class LangChainReasoningEngineTest {

    @Inject
    ReasoningEngine reasoningEngine;

    @InjectMock
    ChatLanguageModel chatLanguageModel;

    @InjectMock
    CompactionSummaryRepository compactionSummaryRepository;

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
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());

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
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());

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
        com.arcclaw.core.scope.WorkspaceScopeHolder.set(sessionId);

        List<ChatMessage> storedMessages = List.of(
            SystemMessage.from("Ignore me"),
            AiMessage.from("Human Approved: ..."),
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
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());

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
        boolean foundResuming = filtered.stream().anyMatch(m -> m instanceof UserMessage && ((UserMessage)m).text().contains("Resuming"));

        assertFalse(foundSystemStore, "System message from store should be filtered out");
        assertFalse(foundApproved, "Approval marker should be filtered out");
        assertFalse(foundResuming, "Resuming marker should be filtered out");
        
        boolean foundGoal = filtered.stream().anyMatch(m -> m instanceof UserMessage && ((UserMessage)m).text().equals("Actual Goal"));
        assertTrue(foundGoal, "Real goal should be preserved");
        
        com.arcclaw.core.scope.WorkspaceScopeHolder.clear();
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
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());

        reasoningEngine.nextStep("test-session", "coder", "Write a sort", List.of());

        ChatRequest sent = captor.getValue();
        assertTrue(sent.toolSpecifications() == null 
                || sent.toolSpecifications().isEmpty(),
                "coder must NOT receive tool schemas — schema jailbreak fix");
    }

    @Test
    void testDebuggerRoleReceivesNoToolSchema() {
        AiMessage msg = AiMessage.from("Bug is on line 42.");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(msg).tokenUsage(new TokenUsage(5, 10))
                .finishReason(FinishReason.STOP).build();

        ArgumentCaptor<ChatRequest> captor = 
                ArgumentCaptor.forClass(ChatRequest.class);
        when(chatLanguageModel.chat(captor.capture())).thenReturn(response);
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());

        reasoningEngine.nextStep("test-session", "debugger", "Find the NPE", List.of());

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
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());
        reasoningEngine.nextStep("test-session", "researcher", "Find Quarkus docs", List.of());

        ChatRequest sent = captor.getValue();
        assertNotNull(sent.toolSpecifications());
        // Phase 45: Researcher now has 2 tools: github, memory_search
        assertEquals(2, sent.toolSpecifications().size());
        assertTrue(sent.toolSpecifications().stream().anyMatch(t -> t.name().equals("github")));
        assertTrue(sent.toolSpecifications().stream().anyMatch(t -> t.name().equals("memory_search")));
    }
}
