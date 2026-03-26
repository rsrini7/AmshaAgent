package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.agent.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

class ChatHistoryAssemblerTest {

    @Mock
    ChatMemoryStore chatMemoryStore;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    Logger log;

    ChatHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        assembler = new ChatHistoryAssembler(chatMemoryStore, objectMapper);
        assembler.log = log;
    }

    @Test
    void testAssemble_complianceOverrideIsRedacted() {
        // Arrange
        String sessionId = "s1";
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        List<ChatMessage> history = List.of(
                AiMessage.aiMessage("as per our agreement I will provide something")
        );
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(history);

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert
        assertTrue(result.isEmpty(), "Compliance override should be redacted");
    }

    @Test
    void testAssemble_systemMessagesSkipped() {
        // Arrange
        String sessionId = "s2";
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        List<ChatMessage> history = List.of(
                SystemMessage.systemMessage("Instruction"),
                UserMessage.userMessage("Help")
        );
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(history);

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
    }

    @Test
    void testAssemble_resumalGoalInjected() {
        // Arrange
        String goal = AgentSentinels.GOAL_TOOL_APPROVED;

        // Act
        List<ChatMessage> result = assembler.assemble(null, goal, List.of());

        // Assert
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
        assertEquals(goal, ((UserMessage) result.get(0)).singleText());
    }

    @Test
    void testAssemble_schemaCorrectionGoalInjectsSystemMessage() {
        // Arrange
        String goal = "Original Goal\n\nSystem: Your previous tool call was rejected...";

        // Act
        List<ChatMessage> result = assembler.assemble(null, goal, List.of());

        // Assert
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof SystemMessage);
        assertTrue(((SystemMessage) result.get(0)).text().contains("rejected"));
        assertTrue(((SystemMessage) result.get(0)).text().contains("Do NOT attempt another tool call"));
    }

    @Test
    void testAssemble_reactStepObservationTruncatedAt4000Chars() {
        // Arrange
        String longObs = "A".repeat(5000);
        ReasoningStep step = ReasoningStep.pending("thought", "tool", Map.of(), 0, 0)
                .withObservation(longObs);

        // Act
        List<ChatMessage> result = assembler.assemble(null, null, List.of(step));

        // Assert
        // Expecting AiMessage and ToolExecutionResultMessage
        assertEquals(2, result.size());
        ToolExecutionResultMessage obsMsg = (ToolExecutionResultMessage) result.get(1);
        assertEquals(4000 + "... [TRUNCATED FOR LENGTH]".length(), obsMsg.text().length());
        assertTrue(obsMsg.text().endsWith("[TRUNCATED FOR LENGTH]"));
    }

    @Test
    void compliancePatternDoesNotBacktrackOnCraftedInput() {
        String evil = "i will not ".repeat(200) + "discuss some questions going forward";

        Assertions.assertTimeout(Duration.ofMillis(100),
            () -> ChatHistoryAssembler.COMPLIANCE_OVERRIDE_PATTERN.matcher(evil).find());
    }

    @Test
    void testAssemble_serializationErrorFallsBackToEmptyParams() {
        // Arrange
        Object thrower = new Object() {
            @SuppressWarnings("unused")
            public String getFail() {
                throw new RuntimeException("Force serialization failure");
            }
        };
        ReasoningStep step = ReasoningStep.pending("thought", "myTool", Map.of("badParam", thrower), 0, 0);

        // Act
        List<ChatMessage> result = assembler.assemble(null, null, List.of(step));

        // Assert
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof AiMessage);
        AiMessage ai = (AiMessage) result.get(0);
        assertTrue(ai.hasToolExecutionRequests());
        dev.langchain4j.agent.tool.ToolExecutionRequest req = ai.toolExecutionRequests().get(0);
        assertEquals("myTool", req.name());
        assertEquals("{}", req.arguments());
    }
}
