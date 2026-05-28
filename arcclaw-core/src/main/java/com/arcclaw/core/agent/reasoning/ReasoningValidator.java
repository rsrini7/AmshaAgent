package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Validates reasoning history and checks for redundant tool execution attempts.
 */
@ApplicationScoped
public class ReasoningValidator {

    private final CompactionSummaryCache summaryCache;

    @Inject
    public ReasoningValidator(CompactionSummaryCache summaryCache) {
        this.summaryCache = summaryCache;
    }

    protected ReasoningValidator() {
        this(null);
    }

    public boolean historyAlreadyContainsAttemptForLanguage(
            List<ReasoningStep> history, String toolName, String language, String sessionId) {
        if (history != null
                && history.stream().anyMatch(step -> {
                    if (!toolName.equals(step.action())
                            || step.observation() == null
                            || step.observation().isBlank()) return false;
                    if (language == null) return false;
                    Object langParam =
                            step.actionInput() != null ? step.actionInput().get("language") : null;
                    return language.equalsIgnoreCase(String.valueOf(langParam));
                })) return true;

        if (sessionId != null && summaryCache != null) {
            Optional<String> summary = summaryCache.getSummary(sessionId);
            if (summary.isPresent() && language != null) {
                String s = summary.get().toLowerCase();
                if (s.contains("failed") && s.contains(language.toLowerCase()) && s.contains(toolName.toLowerCase()))
                    return true;
            }
        }
        return false;
    }

    public int countTruncationAttempts(List<ReasoningStep> history) {
        if (history == null) return 0;
        return (int) history.stream()
                .filter(step -> ReasoningStep.OBSERVE.equals(step.action())
                        && step.observation() != null
                        && step.observation().contains("was cut off"))
                .count();
    }

    public boolean historyAlreadyContains(List<ReasoningStep> history, String toolName) {
        if (history == null) return false;
        return history.stream()
                .anyMatch(step -> toolName.equals(step.action())
                        && step.observation() != null
                        && !step.observation().isBlank());
    }
}
