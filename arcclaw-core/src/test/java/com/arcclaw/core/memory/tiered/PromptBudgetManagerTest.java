package com.arcclaw.core.memory.tiered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arcclaw.core.config.MemoryPromptBudgetConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for PromptBudgetManager — Phase 4 prompt budget hardening.
 */
class PromptBudgetManagerTest {

    PromptBudgetManager manager;

    @BeforeEach
    void setup() {
        manager = new PromptBudgetManager();
        manager.config = new MemoryPromptBudgetConfig() {
            @Override
            public int maxCoreMemoryTokens() {
                return 800;
            }

            @Override
            public int maxCompactionSummaryTokens() {
                return 1200;
            }

            @Override
            public int maxPrefetchedArchivalResults() {
                return 3;
            }

            @Override
            public int maxDirectives() {
                return 5;
            }

            @Override
            public int totalNonConversationTokens() {
                return 2500;
            }
        };
    }

    @Test
    void testCoreMemoryAloneExceedsBudget_trimmedWithMarker() {
        // Core Memory: 800 tokens → 720 effective → 2,880 chars budget
        // Create 5,000 chars (~1,250 tokens) — exceeds 800 token budget
        String largeCore = "A".repeat(5000);

        PromptBudgetManager.BudgetEnforcedSections result = manager.enforce(largeCore, null, null);

        assertNotNull(result.coreMemory());
        assertTrue(result.coreMemory().length() < largeCore.length(), "Core Memory should be truncated");
        assertTrue(result.coreWasTruncated(), "coreWasTruncated flag should be true");
        assertFalse(result.summaryWasTruncated(), "summaryWasTruncated should be false");
        assertNull(result.compactionSummary(), "Summary was null, should stay null");
    }

    @Test
    void testCorePlusSummaryExceedTotal_summaryTrimmedFirstCorePreserved() {
        // Total budget: 2500 tokens → 2250 effective → 9,000 chars
        // Core: 2,000 chars (within 2,880 individual cap)
        // Summary: 8,000 chars (within 4,320 individual cap)
        // Total: 10,000 > 9,000 → excess 1,000 → summary trimmed first
        String core = "B".repeat(2000);
        String summary = "S".repeat(8000);

        PromptBudgetManager.BudgetEnforcedSections result = manager.enforce(core, summary, null);

        assertEquals(core, result.coreMemory(), "Core should be preserved (within individual cap)");
        assertFalse(result.coreWasTruncated(), "Core should not be truncated");
        assertTrue(
                result.compactionSummary().length() < summary.length(),
                "Summary should be trimmed to fit total budget");
        assertTrue(result.summaryWasTruncated(), "summaryWasTruncated flag should be true");
    }

    @Test
    void testAllSectionsWithinBudget_noTruncation() {
        // All sections comfortably within budget
        String core = "C".repeat(500); // ~125 tokens
        String summary = "D".repeat(1000); // ~250 tokens

        PromptBudgetManager.BudgetEnforcedSections result = manager.enforce(core, summary, null);

        assertEquals(core, result.coreMemory());
        assertEquals(summary, result.compactionSummary());
        assertFalse(result.coreWasTruncated());
        assertFalse(result.summaryWasTruncated());
    }

    @Test
    void testUnicodeSafety_emojiTruncatedAtValidBoundary() {
        // Create text ending with emoji (multi-codepoint)
        // Emoji 👨‍💻 is 5 code units (3 code points) in Java
        String prefix = "X".repeat(2876);
        String text = prefix + "👨‍💻👨‍💻"; // total > 2880 chars

        String result = PromptBudgetManager.truncateSafely(text, 2880);

        assertNotNull(result);
        assertTrue(result.length() <= 2880, "Result should not exceed maxChars");
        // Should not contain broken surrogate — no replacement characters
        assertFalse(result.contains("\uFFFD"), "Should not contain replacement characters");
    }

    @Test
    void testNullSections_returnedAsNull() {
        PromptBudgetManager.BudgetEnforcedSections result = manager.enforce(null, null, null);

        assertNull(result.coreMemory());
        assertNull(result.compactionSummary());
        assertNull(result.archivalContext());
        assertFalse(result.coreWasTruncated());
        assertFalse(result.summaryWasTruncated());
        assertFalse(result.archivalWasTruncated());
    }

    @Test
    void testEstimateTokens() {
        assertEquals(0, PromptBudgetManager.estimateTokens(null));
        assertEquals(0, PromptBudgetManager.estimateTokens(""));
        assertEquals(0, PromptBudgetManager.estimateTokens("   "));
        assertEquals(25, PromptBudgetManager.estimateTokens("A".repeat(100)));
        assertEquals(500, PromptBudgetManager.estimateTokens("A".repeat(2000)));
    }

    @Test
    void testCalculateCharBudget_mathIsCorrect() {
        // 800 tokens → 720 effective → 2,880 chars
        assertEquals(2880, manager.calculateCharBudget(800));
        // 1200 tokens → 1080 effective → 4,320 chars
        assertEquals(4320, manager.calculateCharBudget(1200));
        // 2500 tokens → 2250 effective → 9,000 chars
        assertEquals(9000, manager.calculateCharBudget(2500));
    }

    @Test
    void testTruncateSafely_shortTextReturnedAsIs() {
        String text = "Hello world";
        assertEquals(text, PromptBudgetManager.truncateSafely(text, 100));
    }

    @Test
    void testTruncateSafely_breaksAtWordBoundary() {
        String text = "The quick brown fox jumps over the lazy dog";
        String result = PromptBudgetManager.truncateSafely(text, 20);
        // Should break at a space, not mid-word
        assertTrue(result.length() <= 20);
        assertFalse(result.endsWith(" "), "Should not end with space");
        // Should be a prefix of the original
        assertTrue(text.startsWith(result));
    }
}
