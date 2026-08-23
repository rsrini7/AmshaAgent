package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.memory.config.TieredMemoryConfig;
import com.amsha.memory.tiered.CoreMemoryPromptRenderer;
import com.amsha.memory.tiered.CoreMemoryService;
import com.amsha.memory.tiered.PromptBudgetManager;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
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

    @Mock
    CompactionSummaryCache summaryCache;

    @Mock
    TieredMemoryConfig tieredMemoryConfig;

    @Mock
    CoreMemoryService coreMemoryService;

    @Mock
    CoreMemoryPromptRenderer coreMemoryRenderer;

    @Mock
    PromptBudgetManager budgetManager;

    @InjectMocks
    ChatHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        assembler.log = log;
        assembler.summaryCache = summaryCache;
        assembler.defaultObservationMaxLength = 4000;
        assembler.toolSpecificLimits = Optional.empty();

        lenient().when(summaryCache.getSummary(any())).thenReturn(Optional.empty());
        lenient().when(tieredMemoryConfig.enabled()).thenReturn(false);

        // Default: budget manager returns text as-is (no truncation)
        lenient()
                .when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation -> new PromptBudgetManager.BudgetEnforcedSections(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        false,
                        false,
                        false));
    }

    @Test
    void testAssemble_complianceOverrideIsRedacted() {
        // Arrange
        String sessionId = "s1";
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        List<ChatMessage> history = List.of(AiMessage.aiMessage("as per our agreement I will provide something"));
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
        List<ChatMessage> history =
                List.of(SystemMessage.systemMessage("Instruction"), UserMessage.userMessage("Help"));
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
        ReasoningStep step =
                ReasoningStep.pending("thought", "tool", Map.of(), 0, 0).withObservation(longObs);

        // Act
        List<ChatMessage> result = assembler.assemble(null, null, List.of(step));

        // Assert
        assertEquals(2, result.size());
        ToolExecutionResultMessage obsMsg = (ToolExecutionResultMessage) result.get(1);
        String expectedIndicator = "\n[... truncated 1000 chars]";
        assertEquals(4000 + expectedIndicator.length(), obsMsg.text().length());
        assertTrue(obsMsg.text().endsWith("1000 chars]"));
    }

    @Test
    void testAssemble_reactStepObservationTruncated_WithToolSpecificLimit() {
        // Arrange
        assembler.toolSpecificLimits = Optional.of(Map.of("special_tool", 1000));
        String longObs = "A".repeat(1500);
        ReasoningStep step =
                ReasoningStep.pending("thought", "special_tool", Map.of(), 0, 0).withObservation(longObs);

        // Act
        List<ChatMessage> result = assembler.assemble(null, null, List.of(step));

        // Assert
        ToolExecutionResultMessage obsMsg = (ToolExecutionResultMessage) result.get(1);
        String expectedIndicator = "\n[... truncated 500 chars]";
        assertEquals(1000 + expectedIndicator.length(), obsMsg.text().length());
        assertTrue(obsMsg.text().endsWith("500 chars]"));
    }

    @Test
    void testAssemble_scrubsToolMessagesFromPersistentHistory() {
        // Arrange
        String sessionId = "s3";
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1")
                .name("calc")
                .arguments("{\"expr\":\"1+1\"}")
                .build();
        List<ChatMessage> history = List.of(
                UserMessage.userMessage("Calculate 1+1"),
                AiMessage.aiMessage(List.of(req)),
                new ToolExecutionResultMessage("call_1", "calc", "2"),
                AiMessage.aiMessage("The result is 2"));
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(history);

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert
        // Should only contain: UserMessage("Calculate 1+1"), AiMessage("The result is 2")
        // AiMessage with ToolExecutionRequest and ToolExecutionResultMessage should be skipped.
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
        assertTrue(result.get(1) instanceof AiMessage);
        assertFalse(((AiMessage) result.get(1)).hasToolExecutionRequests());
    }

    @Test
    void testAssemble_scrubsAiMessageWithTextAndToolCallsFromPersistentHistory() {
        // Arrange
        String sessionId = "s4";
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1")
                .name("calc")
                .arguments("{}")
                .build();
        List<ChatMessage> history = List.of(
                AiMessage.aiMessage("I will calculate it", List.of(req)),
                new ToolExecutionResultMessage("call_1", "calc", "42"));
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(history);

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert
        assertTrue(result.isEmpty(), "AI message with tool requests AND the tool result should BOTH be scrubbed");
    }

    @Test
    void compliancePatternDoesNotBacktrackOnCraftedInput() {
        String evil = "i will not ".repeat(200) + "discuss some questions going forward";

        Assertions.assertTimeout(
                Duration.ofMillis(100),
                () -> ChatHistoryAssembler.COMPLIANCE_OVERRIDE_PATTERN
                        .matcher(evil)
                        .find());
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
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof AiMessage);
        assertTrue(result.get(1) instanceof ToolExecutionResultMessage);
        assertEquals("(no output)", ((ToolExecutionResultMessage) result.get(1)).text());
        AiMessage ai = (AiMessage) result.get(0);
        assertTrue(ai.hasToolExecutionRequests());
        ToolExecutionRequest req = ai.toolExecutionRequests().get(0);
        assertEquals("myTool", req.name());
        assertEquals("{}", req.arguments());
    }

    // Prompt Budget Integration Tests

    @Test
    void testAssemble_budgetEnforced_coreMemoryTrimmedMarkerAppended() {
        // Arrange: tiered memory enabled, budget returns truncated core memory
        String sessionId = "budget-s1";
        when(tieredMemoryConfig.enabled()).thenReturn(true);
        when(tieredMemoryConfig.coreMemory()).thenReturn(new TieredMemoryConfig.CoreMemoryConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public int maxBlocksPerUser() {
                return 20;
            }

            @Override
            public int maxCharsPerBlock() {
                return 2000;
            }
        });
        when(coreMemoryService.readAll("unknown"))
                .thenReturn(List.of(
                        new CoreMemoryBlockDto("id", "default", "user_profile", "Some profile data", 1, null, null)));
        when(coreMemoryRenderer.render(any())).thenReturn("User: " + "A".repeat(5000));

        // Budget manager signals truncation
        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation -> new PromptBudgetManager.BudgetEnforcedSections(
                        "User: " + "A".repeat(2000), // truncated
                        null,
                        null,
                        true,
                        false,
                        false));

        when(chatMemoryStore.getMessages(sessionId)).thenReturn(List.of());

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert: Core Memory SystemMessage should have truncation marker
        assertTrue(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t ->
                                t.contains("[USER CONTEXT]") && t.contains("[... truncated to fit prompt budget]")),
                "Core Memory block should have truncation marker");
    }

    @Test
    void testAssemble_budgetEnforced_summaryTrimmedFirst() {
        // Arrange: both core and summary present, budget trims summary
        String sessionId = "budget-s2";
        when(tieredMemoryConfig.enabled()).thenReturn(false); // core memory off

        when(summaryCache.getSummary(sessionId))
                .thenReturn(Optional.of("This is a very long summary that will be truncated by the budget manager."));

        // Budget returns summary truncated, core as null
        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation -> new PromptBudgetManager.BudgetEnforcedSections(
                        null,
                        "This is a very long summary", // truncated
                        null,
                        false,
                        true,
                        false));

        when(chatMemoryStore.getMessages(sessionId)).thenReturn(List.of());

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert: Summary SystemMessage should have truncation marker and sentinel
        assertTrue(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL)
                                && t.contains("[... truncated to fit prompt budget]")),
                "Summary block should have sentinel + truncation marker");
    }

    @Test
    void testAssemble_coreMemoryAbsent_whenTieredMemoryDisabled() {
        // Arrange: tiered memory disabled, so coreText stays null.
        // budgetManager.enforce() is still called (passthrough mock) but receives null inputs.
        String sessionId = "budget-s3";
        when(tieredMemoryConfig.enabled()).thenReturn(false);

        when(summaryCache.getSummary(sessionId)).thenReturn(Optional.empty());
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(List.of(UserMessage.userMessage("Hello")));

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert: no core memory injected (both sections were null → enforce returned nulls)
        assertFalse(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains("[USER CONTEXT]")),
                "Core Memory should not be injected when tiered memory is disabled");
    }

    @Test
    void testAssemble_budgetSummarySentinelPreserved() {
        // Arrange: summary present, budget does NOT truncate — sentinel should still be intact
        String sessionId = "budget-s4";
        when(tieredMemoryConfig.enabled()).thenReturn(false);
        when(summaryCache.getSummary(sessionId)).thenReturn(Optional.of("Short summary that fits in budget."));

        // Budget returns summary as-is (no truncation)
        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation -> new PromptBudgetManager.BudgetEnforcedSections(
                        null, invocation.getArgument(1), null, false, false, false));

        when(chatMemoryStore.getMessages(sessionId)).thenReturn(List.of());

        // Act
        List<ChatMessage> result = assembler.assemble(sessionId, null, List.of());

        // Assert: sentinel should be present in one of the SystemMessages
        assertTrue(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL)),
                "Sentinel should be preserved when budget does not truncate");
    }
}
