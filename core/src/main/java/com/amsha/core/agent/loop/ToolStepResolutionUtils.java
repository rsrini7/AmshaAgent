package com.amsha.core.agent.loop;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.ToolRegistry;

import org.jboss.logging.Logger;

public final class ToolStepResolutionUtils {

    private static final Logger log = Logger.getLogger(ToolStepResolutionUtils.class);
    private static final int ARTIFACT_COMPLETION_LOOKBACK = 8;

    private static final Set<String> MUTATING_GOAL_KEYWORDS = Set.of(
            "delete", "remove", "cancel", "stop", "modify", "change", "update", "edit", "pause", "resume", "clear",
            "disable");
    private static final Set<String> ARTIFACT_COMPLETION_HINTS = Set.of("output/", "xdg-open", "default browser");

    private static final Set<String> ARTIFACT_COMPLETION_LINE_START_HINTS = Set.of("open ", "start ");

    private ToolStepResolutionUtils() {}

    public static boolean isHallucinatedFinish(ReasoningStep step) {
        return ReasoningStep.FINISH.equalsIgnoreCase(step.action())
                && step.actionInput() != null
                && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
    }

    public static ReasoningStep rescueFinish(ReasoningStep step, String sessionId) {
        log.infof("[ToolStepHandler] Rescued hallucinated 'finish' tool for session %s", sessionId);
        return ReasoningStep.pending(
                step.thought(),
                ReasoningStep.FINISH,
                Map.copyOf(step.actionInput()),
                step.inputTokens(),
                step.outputTokens());
    }

    public static boolean isKnownTool(
            String toolName, ToolRegistry toolRegistry, Instance<DynamicToolLookup> dynamicToolLookup) {
        if (toolRegistry.findTool(toolName).isPresent()) return true;
        return !dynamicToolLookup.isUnsatisfied() && dynamicToolLookup.get().isDynamicToolAllowed(toolName);
    }

    public static String buildDelegateGoal(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "Use the " + toolName + " tool and return the complete results.";
        }
        String action = String.valueOf(args.getOrDefault("action", "")).strip();
        String command = String.valueOf(args.getOrDefault("command", "")).strip();
        String target = String.valueOf(args.getOrDefault("target", "")).strip();

        StringBuilder sb = new StringBuilder("Use the ").append(toolName).append(" tool");
        if (!action.isBlank() && !"null".equals(action))
            sb.append(" with action=").append(action);
        if (!command.isBlank() && !"null".equals(command))
            sb.append(" command=").append(command);
        if (!target.isBlank() && !"null".equals(target))
            sb.append(" for user/resource=").append(target);
        sb.append(" and return the complete results.");
        return sb.toString();
    }

    public static boolean isListOnlyGoal(String goal) {
        if (goal == null || goal.isBlank()) return true;
        String lower = goal.toLowerCase(Locale.ROOT);
        return MUTATING_GOAL_KEYWORDS.stream().noneMatch(lower::contains);
    }

    public static boolean hasRecentArtifactCompletion(List<ReasoningStep> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }

        int inspected = 0;
        for (int i = history.size() - 1; i >= 0 && inspected < ARTIFACT_COMPLETION_LOOKBACK; i--, inspected++) {
            ReasoningStep step = history.get(i);
            if (!Boolean.TRUE.equals(step.success())) {
                continue;
            }
            if (!ToolCapability.HOST_EXEC.toolName().equals(step.action())) {
                continue;
            }
            if (isArtifactCompletionSignal(step)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isArtifactCompletionSignal(ReasoningStep step) {
        StringBuilder probe = new StringBuilder();
        if (step.actionInput() != null) {
            step.actionInput().values().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(v -> probe.append(v).append('\n'));
        }
        if (step.observation() != null) {
            probe.append(step.observation());
        }
        String lower = probe.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("templates/") && !lower.contains("output/")) {
            return false;
        }
        if (ARTIFACT_COMPLETION_HINTS.stream().anyMatch(lower::contains)) {
            return true;
        }
        if (ARTIFACT_COMPLETION_LINE_START_HINTS.stream().anyMatch(h -> containsAtLineStart(lower, h))) {
            return true;
        }
        return lower.contains("opened browser preview")
                || lower.contains("opened default browser")
                || lower.contains("browser preview:")
                || lower.contains("artifact was generated")
                || lower.contains("generated artifact")
                || lower.contains("file:///")
                || lower.contains("file://");
    }

    /**
     * Case-insensitive substring match that only succeeds when {@code needle} appears either at index 0
     * of {@code text} or immediately after a newline character. This is intended for shell-command hints
     * such as {@code "open "} or {@code "start "}, which should not match mid-sentence prose like
     * "open source" returned by web search tools.
     */
    private static boolean containsAtLineStart(String text, String needle) {
        if (text == null || needle == null || text.isEmpty() || needle.isEmpty()) {
            return false;
        }
        if (text.regionMatches(true, 0, needle, 0, needle.length())) {
            return true;
        }
        int from = 0;
        while (true) {
            int newline = text.indexOf('\n', from);
            if (newline < 0) {
                return false;
            }
            int start = newline + 1;
            if (start + needle.length() > text.length()) {
                return false;
            }
            if (text.regionMatches(true, start, needle, 0, needle.length())) {
                return true;
            }
            from = start;
        }
    }
}
