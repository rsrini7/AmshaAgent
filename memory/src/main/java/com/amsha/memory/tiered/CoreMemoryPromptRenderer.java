package com.amsha.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import com.amsha.persistence.api.CoreMemoryBlockDto;

/**
 * Renders Core Memory blocks into a flat text block for prompt injection.
 * <p>
 * This renderer performs pure formatting (block-key to label mapping).
 * Budget enforcement is handled by {@link PromptBudgetManager}.
 */
@ApplicationScoped
public class CoreMemoryPromptRenderer {

    public String render(List<CoreMemoryBlockDto> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        for (CoreMemoryBlockDto block : blocks) {
            if (block.content() == null || block.content().isBlank()) {
                continue;
            }

            String label =
                    switch (block.blockKey()) {
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

        return sb.toString().trim();
    }
}
