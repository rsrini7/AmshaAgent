package com.amsha.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.config.MemoryPromptBudgetConfig;

/**
 * Enforces prompt budget limits on memory sections injected into the system prompt.
 * <p>
 * Uses a two-pass strategy:
 * <ol>
 *   <li>Individual caps per section (core memory, compaction summary, archival)</li>
 *   <li>Total cap with hierarchical trimming (archival first, summary second, core last)</li>
 * </ol>
 * <p>
 * Token estimation uses {@code chars / 4} as a heuristic. Named {@link #estimateTokens(String)}
 * to signal approximation. A 10% safety margin is applied to the token budget before conversion
 * to character budget ({@code effectiveTokenBudget * 4}).
 */
@ApplicationScoped
public class PromptBudgetManager {

    @Inject
    MemoryPromptBudgetConfig config;

    /**
     * Result of budget enforcement. Each section may be truncated.
     * The truncation flags indicate whether the caller should append a marker.
     */
    public record BudgetEnforcedSections(
            String coreMemory,
            String compactionSummary,
            String archivalContext,
            boolean coreWasTruncated,
            boolean summaryWasTruncated,
            boolean archivalWasTruncated) {}

    /**
     * Enforce budget limits on memory prompt sections.
     *
     * @param coreMemory        raw core memory text (nullable)
     * @param compactionSummary raw compaction summary text (nullable)
     * @param archivalContext    raw archival context text (nullable)
     * @return budget-enforced sections with truncation flags
     */
    public BudgetEnforcedSections enforce(String coreMemory, String compactionSummary, String archivalContext) {
        boolean[] truncated = {false, false, false};
        String core = coreMemory;
        String summary = compactionSummary;
        String archival = archivalContext;

        // Pass 1: Individual caps
        int coreCharBudget = calculateCharBudget(config.maxCoreMemoryTokens());
        int summaryCharBudget = calculateCharBudget(config.maxCompactionSummaryTokens());
        if (core != null && core.length() > coreCharBudget) {
            core = truncateSafely(core, coreCharBudget);
            truncated[0] = true;
        }
        if (summary != null && summary.length() > summaryCharBudget) {
            summary = truncateSafely(summary, summaryCharBudget);
            truncated[1] = true;
        }

        // Pass 2: Total cap with hierarchical trimming (archival → summary → core)
        int totalCharBudget = calculateCharBudget(config.totalNonConversationTokens());
        int[] excess = {len(core) + len(summary) + len(archival) - totalCharBudget};
        if (excess[0] > 0) {
            archival = trimSection(archival, 0, excess, truncated, 2);
            summary = trimSection(summary, 100, excess, truncated, 1);
            core = trimSection(core, 100, excess, truncated, 0);
        }

        return new BudgetEnforcedSections(core, summary, archival, truncated[0], truncated[1], truncated[2]);
    }

    private String trimSection(String text, int minKeep, int[] excess, boolean[] truncated, int idx) {
        if (text == null || excess[0] <= 0) return text;
        int targetLen = Math.max(minKeep, text.length() - excess[0]);
        String trimmed = truncateSafely(text, targetLen);
        int actualRemoved = text.length() - trimmed.length();
        if (actualRemoved > 0) {
            excess[0] -= actualRemoved;
            truncated[idx] = true;
        }
        return trimmed;
    }

    /**
     * Estimates token count from text length. Uses {@code chars / 4} heuristic.
     * Named to signal approximation — not a real tokenizer.
     */
    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.length() / 4;
    }

    /**
     * Truncates text to {@code maxChars} on a valid code point boundary.
     * Tries to break at the last space within 80% of the limit to avoid mid-word cuts.
     * Does NOT append a marker — the caller controls marker text.
     */
    static String truncateSafely(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }

        // Ensure we don't split a surrogate pair (emoji, CJK)
        int safeIndex = maxChars;
        if (safeIndex > 0 && Character.isLowSurrogate(text.charAt(safeIndex))) {
            safeIndex--;
        }

        // Try not to cut mid-word (find last space within 80% of limit)
        int lastSpace = text.lastIndexOf(' ', safeIndex);
        if (lastSpace > safeIndex * 0.8) {
            safeIndex = lastSpace;
        }

        return text.substring(0, safeIndex);
    }

    /**
     * Converts token budget to character budget with 10% safety margin.
     * {@code charBudget = (int)(tokenBudget * 0.90) * 4}
     */
    int calculateCharBudget(int tokenBudget) {
        int effectiveTokenBudget = (int) (tokenBudget * 0.90);
        return effectiveTokenBudget * 4;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
