package com.arcclaw.core.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.output.FinishReason;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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

        // Act
        ReasoningStep step = reasoningEngine.nextStep("coordinator", "Do something", List.of());

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

        // Act
        ReasoningStep step = reasoningEngine.nextStep("coordinator", "Do something", List.of());

        // Assert
        assertEquals("github", step.action());
        assertEquals("I will use github", step.thought());
        assertEquals(15, step.inputTokens());
        assertEquals(25, step.outputTokens());
        assertTrue(step.actionInput().containsKey("repo"));
        assertEquals("test/repo", step.actionInput().get("repo"));
    }

    @InjectMock
    dev.langchain4j.store.memory.chat.ChatMemoryStore chatMemoryStore;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void testNextStepFiltersHistory() {
        // Arrange
        String sessionId = "test-session";
        com.arcclaw.core.scope.WorkspaceScopeHolder.set(sessionId);

        List<dev.langchain4j.data.message.ChatMessage> storedMessages = List.of(
            dev.langchain4j.data.message.SystemMessage.from("Ignore me"),
            dev.langchain4j.data.message.AiMessage.from("Human Approved: ..."),
            dev.langchain4j.data.message.UserMessage.from("Actual Goal"),
            dev.langchain4j.data.message.UserMessage.from("Resuming task after approval")
        );
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(storedMessages);

        AiMessage mockAiMessage = AiMessage.from("Processing");
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(mockAiMessage)
                .build();

        // Capture the request to verify filtered messages
        org.mockito.ArgumentCaptor<ChatRequest> requestCaptor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        when(chatLanguageModel.chat(requestCaptor.capture())).thenReturn(mockResponse);

        // Act
        reasoningEngine.nextStep("coordinator", "Actual Goal", List.of());

        // Assert
        ChatRequest capturedRequest = requestCaptor.getValue();
        List<dev.langchain4j.data.message.ChatMessage> filtered = capturedRequest.messages();

        // Should have: 
        // 1. Initial System Message (Trimurti prompt)
        // 2. "Actual Goal" (from history)
        // Count: 2
        
        // Let's verify specifically that "Ignore me", "Human Approved", and "Resuming" were skipped
        boolean foundSystemStore = filtered.stream().anyMatch(m -> m instanceof dev.langchain4j.data.message.SystemMessage && ((dev.langchain4j.data.message.SystemMessage)m).text().equals("Ignore me"));
        boolean foundApproved = filtered.stream().anyMatch(m -> m.toString().contains("Human Approved"));
        boolean foundResuming = filtered.stream().anyMatch(m -> m instanceof dev.langchain4j.data.message.UserMessage && ((dev.langchain4j.data.message.UserMessage)m).text().contains("Resuming"));

        assertFalse(foundSystemStore, "System message from store should be filtered out");
        assertFalse(foundApproved, "Approval marker should be filtered out");
        assertFalse(foundResuming, "Resuming marker should be filtered out");
        
        boolean foundGoal = filtered.stream().anyMatch(m -> m instanceof dev.langchain4j.data.message.UserMessage && ((dev.langchain4j.data.message.UserMessage)m).text().equals("Actual Goal"));
        assertTrue(foundGoal, "Real goal should be preserved");
        
        com.arcclaw.core.scope.WorkspaceScopeHolder.clear();
    }
}
