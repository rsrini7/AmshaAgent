package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.core.memory.tiered.CoreMemoryPromptRenderer;
import com.amsha.core.memory.tiered.CoreMemoryService;
import com.amsha.core.memory.tiered.PromptBudgetManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatHistoryAssemblerUnitTest {

    @Mock
    Logger log;

    @Mock
    ChatMemoryStore chatMemoryStore;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    CompactionSummaryCache summaryCache;

    @Mock
    CoreMemoryService coreMemoryService;

    @Mock
    CoreMemoryPromptRenderer coreMemoryRenderer;

    @Mock
    TieredMemoryConfig tieredMemoryConfig;

    @Mock
    PromptBudgetManager budgetManager;

    @Mock
    ArchivalContextCache archivalContextCache;

    @InjectMocks
    ChatHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler.defaultObservationMaxLength = 4000;
        assembler.toolSpecificLimits = Optional.of(Map.of("web_fetch", 2000));

        lenient().when(tieredMemoryConfig.enabled()).thenReturn(false);
        lenient().when(summaryCache.getSummary(anyString())).thenReturn(Optional.empty());

        PromptBudgetManager.BudgetEnforcedSections emptyBudget =
                new PromptBudgetManager.BudgetEnforcedSections(null, null, null, false, false, false);
        lenient().when(budgetManager.enforce(any(), any(), any())).thenReturn(emptyBudget);
    }

    @Test
    void testValidate() {
        assertDoesNotThrow(() -> assembler.validate());
    }

    @Test
    void testAssembleEmptyHistory() {
        List<ChatMessage> messages = assembler.assemble("sess-1", "user goal", List.of());
        assertNotNull(messages);
    }

    @Test
    void testAssembleWithReActHistory() {
        ReasoningStep step1 = ReasoningStep.pending("Thinking step 1", ReasoningStep.FINISH, Map.of());
        ReasoningStep step2 = ReasoningStep.pending("Executing shell", "execute_shell", Map.of("command", "ls"));

        List<ChatMessage> messages = assembler.assemble("sess-1", "run command", List.of(step1, step2));
        assertFalse(messages.isEmpty());
    }

    @Test
    void testAssembleWithGoalHint() {
        ReasoningStep step1 = ReasoningStep.pending("Thinking", ReasoningStep.FINISH, Map.of());
        List<ChatMessage> messages = assembler.assemble("sess-1", "System: Retry command with flags", List.of(step1));
        assertFalse(messages.isEmpty());
    }

    @Test
    void testComplianceOverridePattern() {
        assertTrue(ChatHistoryAssembler.COMPLIANCE_OVERRIDE_PATTERN
                .matcher("as per our agreement i will provide... instead of")
                .find());
    }
}
