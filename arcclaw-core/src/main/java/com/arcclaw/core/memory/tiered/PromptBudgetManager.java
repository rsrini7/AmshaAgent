package com.arcclaw.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.config.MemoryPromptBudgetConfig;

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
        boolean archivalWasTruncated
    ) {}

    /**
     * Enforce budget limits on memory prompt sections.
     *
     * @param coreMemory        raw core memory text (nullable)
     * @param compactionSummary raw compaction summary text (nullable)
     * @param archivalContext    raw archival context text (nullable, Phase 6+)
     * @return budget-enforced sections with truncation flags
     */
    public BudgetEnforcedSections enforce(String coreMemory, String compactionSummary, String archivalContext) {
        boolean coreTruncated = false;
        boolean summaryTruncated = false;
        boolean archivalTruncated = false;

        String core = coreMemory;
        String summary = compactionSummary;
        String archival = archivalContext;

        // Pass 1: Individual caps
        int coreCharBudget = calculateCharBudget(config.maxCoreMemoryTokens());
        int summaryCharBudget = calculateCharBudget(config.maxCompactionSummaryTokens());

        if (core != null && core.length() > coreCharBudget) {
            core = truncateSafely(core, coreCharBudget);
            coreTruncated = true;
        }
        if (summary != null && summary.length() > summaryCharBudget) {
            summary = truncateSafely(summary, summaryCharBudget);
            summaryTruncated = true;
        }

        // Pass 2: Total cap with hierarchical trimming
        int totalCharBudget = calculateCharBudget(config.totalNonConversationTokens());
        int currentTotal = len(core) + len(summary) + len(archival);

        if (currentTotal > totalCharBudget) {
            int excess = currentTotal - totalCharBudget;

            // Drop in priority order: archival first, summary second, core last
            // Use actual removed length (truncateSafely may break at word boundary)
            if (archival != null && excess > 0) {
                int targetLen = Math.max(0, archival.length() - excess);
                String trimmed = truncateSafely(archival, targetLen);
                int actualRemoved = archival.length() - trimmed.length();
                if (actualRemoved > 0) {
                    archival = trimmed;
                    excess -= actualRemoved;
                    archivalTruncated = true;
                }
            }
            if (summary != null && excess > 0) {
                int minKeep = Math.min(100, summary.length());
                int targetLen = Math.max(minKeep, summary.length() - excess);
                String trimmed = truncateSafely(summary, targetLen);
                int actualRemoved = summary.length() - trimmed.length();
                if (actualRemoved > 0) {
                    summary = trimmed;
                    excess -= actualRemoved;
                    summaryTruncated = true;
                }
            }
            if (core != null && excess > 0) {
                int minKeep = Math.min(100, core.length());
                int targetLen = Math.max(minKeep, core.length() - excess);
                String trimmed = truncateSafely(core, targetLen);
                int actualRemoved = core.length() - trimmed.length();
                if (actualRemoved > 0) {
                    core = trimmed;
                    excess -= actualRemoved;
                    coreTruncated = true;
                }
            }
        }

        return new BudgetEnforcedSections(core, summary, archival, coreTruncated, summaryTruncated, archivalTruncated);
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
