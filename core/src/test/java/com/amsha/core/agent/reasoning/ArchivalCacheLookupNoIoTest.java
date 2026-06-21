package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Optional;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.core.memory.tiered.CoreMemoryPromptRenderer;
import com.amsha.core.memory.tiered.CoreMemoryService;
import com.amsha.core.memory.tiered.PromptBudgetManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests that ChatHistoryAssembler reads archival context from cache (O(1) lookup)
 * and does NOT trigger any retrieval I/O (tool-first policy).
 */
class ArchivalCacheLookupNoIoTest {

    @Mock
    ChatMemoryStore chatMemoryStore;

    @Mock
    CompactionSummaryCache summaryCache;

    @Mock
    TieredMemoryConfig tieredMemoryConfig;

    @Mock
    TieredMemoryConfig.ArchivalConfig archivalConfig;

    @Mock
    TieredMemoryConfig.CoreMemoryConfig coreMemoryConfig;

    @Mock
    CoreMemoryService coreMemoryService;

    @Mock
    CoreMemoryPromptRenderer coreMemoryRenderer;

    @Mock
    PromptBudgetManager budgetManager;

    @Mock
    ArchivalContextCache archivalContextCache;

    @InjectMocks
    ChatHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        assembler.defaultObservationMaxLength = 4000;
        assembler.toolSpecificLimits = Optional.empty();

        lenient().when(summaryCache.getSummary(any())).thenReturn(Optional.empty());
        lenient().when(tieredMemoryConfig.enabled()).thenReturn(true);
        lenient().when(tieredMemoryConfig.archival()).thenReturn(archivalConfig);
        lenient().when(tieredMemoryConfig.coreMemory()).thenReturn(coreMemoryConfig);
        lenient().when(coreMemoryConfig.enabled()).thenReturn(false); // core memory off for these tests
        lenient().when(archivalConfig.enabled()).thenReturn(true);
        lenient().when(archivalContextCache.getText()).thenReturn(Optional.empty());
    }

    @Test
    void testAssembler_readsFromCache_noIo() {
        // Arrange: cache has pre-fetched archival results
        String cachedKnowledge = "### [PATTERN] confidence=0.90\nAlways use records for DTOs";
        when(archivalContextCache.getText()).thenReturn(Optional.of(cachedKnowledge));

        // Budget manager passes through archival text
        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation -> new PromptBudgetManager.BudgetEnforcedSections(
                        null, null, invocation.getArgument(2), false, false, false));

        when(chatMemoryStore.getMessages("s1"))
                .thenReturn(List.of(UserMessage.userMessage("What patterns should I use?")));

        // Act
        List<ChatMessage> result = assembler.assemble("s1", null, List.of());

        // Assert: [RELEVANT KNOWLEDGE] section is present
        assertTrue(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains("[RELEVANT KNOWLEDGE]") && t.contains("Always use records for DTOs")),
                "Archival context from cache should be injected as [RELEVANT KNOWLEDGE]");
    }

    @Test
    void testAssembler_noCacheHit_noInjection() {
        // Arrange: cache is empty (no tool call this session)
        when(archivalContextCache.getText()).thenReturn(Optional.empty());

        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation ->
                        new PromptBudgetManager.BudgetEnforcedSections(null, null, null, false, false, false));

        when(chatMemoryStore.getMessages("s1")).thenReturn(List.of(UserMessage.userMessage("Hello")));

        // Act
        List<ChatMessage> result = assembler.assemble("s1", null, List.of());

        // Assert: no [RELEVANT KNOWLEDGE] section
        assertFalse(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains("[RELEVANT KNOWLEDGE]")),
                "No archival section should be injected when cache is empty");
    }

    @Test
    void testAssembler_archivalDisabled_noCacheLookup() {
        // Arrange: archival tier disabled
        when(archivalConfig.enabled()).thenReturn(false);

        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation ->
                        new PromptBudgetManager.BudgetEnforcedSections(null, null, null, false, false, false));

        when(chatMemoryStore.getMessages("s1")).thenReturn(List.of(UserMessage.userMessage("Hello")));

        // Act
        List<ChatMessage> result = assembler.assemble("s1", null, List.of());

        // Assert: cache.getText() should NOT have been called
        verify(archivalContextCache, never()).getText();

        // Assert: no [RELEVANT KNOWLEDGE] section
        assertFalse(result.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).text())
                .anyMatch(t -> t.contains("[RELEVANT KNOWLEDGE]")));
    }

    @Test
    void testAssembler_emptyResult_neverCached_neverInjected() {
        // Structural guard: ArchivalMemorySearchTool never caches empty results,
        // so the assembler never sees them. This test verifies the assembler
        // side of that contract — when cache is empty (getText() = Optional.empty()),
        // no [RELEVANT KNOWLEDGE] section is injected.

        // Arrange: cache empty (tool did not cache anything because results were empty)
        when(archivalContextCache.getText()).thenReturn(Optional.empty());

        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation ->
                        new PromptBudgetManager.BudgetEnforcedSections(null, null, null, false, false, false));

        when(chatMemoryStore.getMessages("s1")).thenReturn(List.of(UserMessage.userMessage("hello")));

        // Act
        List<ChatMessage> result = assembler.assemble("s1", null, List.of());

        // Assert: no [RELEVANT KNOWLEDGE] section injected
        assertFalse(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains("[RELEVANT KNOWLEDGE]")),
                "No [RELEVANT KNOWLEDGE] when cache is empty");
    }

    @Test
    void testAssembler_truncatedArchivalGetsMarker() {
        // Arrange: cache has content, budget truncates it
        String cachedKnowledge = "A".repeat(5000);
        when(archivalContextCache.getText()).thenReturn(Optional.of(cachedKnowledge));

        when(budgetManager.enforce(any(), any(), any()))
                .thenAnswer(invocation -> new PromptBudgetManager.BudgetEnforcedSections(
                        null, null, "A".repeat(2000), false, false, true)); // archivalWasTruncated = true

        when(chatMemoryStore.getMessages("s1")).thenReturn(List.of(UserMessage.userMessage("tell me everything")));

        // Act
        List<ChatMessage> result = assembler.assemble("s1", null, List.of());

        // Assert: truncation marker present
        assertTrue(
                result.stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text())
                        .anyMatch(t -> t.contains("[RELEVANT KNOWLEDGE]")
                                && t.contains("[... truncated to fit prompt budget]")),
                "Truncated archival section should have truncation marker");
    }
}
