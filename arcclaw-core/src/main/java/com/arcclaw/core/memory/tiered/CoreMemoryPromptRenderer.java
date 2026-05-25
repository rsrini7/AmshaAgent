package com.arcclaw.core.memory.tiered;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.config.MemoryPromptBudgetConfig;
import com.arcclaw.persistence.api.CoreMemoryBlockDto;

@ApplicationScoped
public class CoreMemoryPromptRenderer {

    private final MemoryPromptBudgetConfig budgetConfig;

    public CoreMemoryPromptRenderer(MemoryPromptBudgetConfig budgetConfig) {
        this.budgetConfig = budgetConfig;
    }

    protected CoreMemoryPromptRenderer() {
        this(null);
    }

    public String render(List<CoreMemoryBlockDto> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        for (CoreMemoryBlockDto block : blocks) {
            if (block.content() == null || block.content().isBlank()) {
                continue;
            }

            String label = switch (block.blockKey()) {
                case "user_profile" -> "User";
                case "active_task" -> "Active Task";
                case "constraints" -> "Constraints";
                case "project_context" -> "Project Context";
                case "preferences" -> "Preferences";
                default -> block.blockKey();
            };

            sb.append(label).append(": ").append(block.content()).append("\n");
        }

        if (sb.isEmpty()) {
            return null;
        }

        String text = sb.toString().trim();
        return truncateToTokenBudget(text, budgetConfig.maxCoreMemoryTokens());
    }

    private static String truncateToTokenBudget(String text, int maxTokens) {
        int estimatedTokens = estimateTokens(text);
        if (estimatedTokens <= maxTokens) {
            return text;
        }

        double ratio = (double) maxTokens / estimatedTokens;
        int targetChars = Math.min(text.length() - 1, (int) (text.length() * ratio));
        return text.substring(0, targetChars) + "\n[... truncated to fit token budget]";
    }

    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.length() / 3;
    }
}
