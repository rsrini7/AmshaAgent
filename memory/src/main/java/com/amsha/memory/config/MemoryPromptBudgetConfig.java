package com.amsha.memory.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for tiered memory prompt injection budget.
 * Controls size caps and ordering for memory blocks injected into the system prompt.
 * <p>
 * Agent will enforce these budgets in {@code ChatHistoryAssembler}.
 */
@ConfigMapping(prefix = "amsha.memory.prompt-budget")
public interface MemoryPromptBudgetConfig {

    @WithDefault("800")
    int maxCoreMemoryTokens();

    @WithDefault("1200")
    int maxCompactionSummaryTokens();

    @WithDefault("3")
    int maxPrefetchedArchivalResults();

    @WithDefault("5")
    int maxDirectives();

    @WithDefault("2500")
    int totalNonConversationTokens();
}
