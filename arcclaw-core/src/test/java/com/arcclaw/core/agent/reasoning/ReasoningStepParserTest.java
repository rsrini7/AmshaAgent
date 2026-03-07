package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

class ReasoningStepParserTest {

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    ReasoningStepParser parser;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        parser = new ReasoningStepParser(objectMapper);
    }

    @Test
    void testParse_toolCallResponse() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name("github")
                .arguments("{\"repo\":\"arcclaw\"}")
                .build();
        AiMessage aiMessage = AiMessage.from("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(new TokenUsage(100, 50))
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals("github", step.action());
        assertEquals("arcclaw", step.actionInput().get("repo"));
        assertEquals(100, step.inputTokens());
        assertEquals(50, step.outputTokens());
    }

    @Test
    void testParse_textResponse() {
        // Arrange
        AiMessage aiMessage = AiMessage.from("Final Answer");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action());
        assertEquals("Final Answer", step.actionInput().get("answer"));
    }

    @Test
    void testParse_invalidJsonArgsFallsBackToFinish() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name("github")
                .arguments("invalid json")
                .build();
        AiMessage aiMessage = AiMessage.from("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.FINISH, step.action());
        assertNotNull(step.actionInput().get("error"));
        assertTrue(step.actionInput().get("error").toString().contains("invalid JSON"));
    }

    @Test
    void testParse_tokenUsagePopulated() {
        // Arrange
        AiMessage aiMessage = AiMessage.from("OK");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(new TokenUsage(10, 20))
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(10, step.inputTokens());
        assertEquals(20, step.outputTokens());
    }

    @Test
    void testParse_nullTokenUsageDefaultsToZero() {
        // Arrange
        AiMessage aiMessage = AiMessage.from("OK");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(null)
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(0, step.inputTokens());
        assertEquals(0, step.outputTokens());
    }
}
