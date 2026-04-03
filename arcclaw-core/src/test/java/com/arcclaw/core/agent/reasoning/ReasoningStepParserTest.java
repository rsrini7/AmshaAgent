package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

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
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", Collections.singletonList(toolReq));
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
        AiMessage aiMessage = AiMessage.aiMessage("Final Answer");
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
    void testParse_invalidJsonArgsReturnsObserve() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name("github")
                .arguments("invalid json")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.OBSERVE, step.action());
        assertNotNull(step.observation());
        assertTrue(step.observation().contains("invalid JSON"));
    }

    @Test
    void malformedJsonToolArgsReturnsObserveNotFinish() {
        // Arrange
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_invalid")
                .name("sometool")
                .arguments("{ broken json }")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", Collections.singletonList(toolReq));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.OBSERVE, step.action());
        assertTrue(step.observation().contains("JSON"));
    }

    @Test
    void testParse_tokenUsagePopulated() {
        // Arrange
        AiMessage aiMessage = AiMessage.aiMessage("OK");
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
        AiMessage aiMessage = AiMessage.aiMessage("OK");
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

    @Test
    void testParse_parallelToolCalls_generatesUuidsForOllama() {
        // Arrange: Ollama returns tool calls with NULL id()
        ToolExecutionRequest tool1 = ToolExecutionRequest.builder()
                .id(null) // Ollama style
                .name("github")
                .arguments("{\"repo\":\"arcclaw\"}")
                .build();
        ToolExecutionRequest tool2 = ToolExecutionRequest.builder()
                .id(null)
                .name("read_file")
                .arguments("{\"path\":\"REAME.md\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Doing two things", List.of(tool1, tool2));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(aiMessage)
                .build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        assertEquals(ReasoningStep.PARALLEL_SENTINEL_DUNDER, step.action());
        String batchJson = (String) step.actionInput().get("batch");
        assertNotNull(batchJson);
        assertTrue(batchJson.contains("github-")); // UUID fallback
        assertTrue(batchJson.contains("read_file-")); // UUID fallback
    }

    @Test
    void testParse_parallelToolCalls_preservesExistingIds() {
        // Arrange: OpenAI/Anthropic return tool calls with IDs
        ToolExecutionRequest tool1 = ToolExecutionRequest.builder()
                .id("call_abc")
                .name("github")
                .arguments("{\"repo\":\"arcclaw\"}")
                .build();
        AiMessage aiMessage = AiMessage.aiMessage("Thinking", List.of(tool1));
        // Force requests.size() > 1 for this test path (in parse it handles > 1)
        ToolExecutionRequest tool2 = ToolExecutionRequest.builder().id("call_xyz").name("read").arguments("{}").build();
        aiMessage = AiMessage.aiMessage("Doing context", List.of(tool1, tool2));

        ChatResponse response = ChatResponse.builder().aiMessage(aiMessage).build();

        // Act
        ReasoningStep step = parser.parse("s1", "coordinator", response);

        // Assert
        String batchJson = (String) step.actionInput().get("batch");
        assertTrue(batchJson.contains("call_abc"));
        assertTrue(batchJson.contains("call_xyz"));
    }
}
