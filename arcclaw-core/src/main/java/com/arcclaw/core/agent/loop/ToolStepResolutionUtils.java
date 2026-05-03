package com.arcclaw.core.agent.loop;

import java.util.Map;
import java.util.Set;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.ToolRegistry;
import org.jboss.logging.Logger;

public final class ToolStepResolutionUtils {

    private static final Logger log = Logger.getLogger(ToolStepResolutionUtils.class);

    private static final Set<String> MUTATING_GOAL_KEYWORDS = Set.of(
            "delete", "remove", "cancel", "stop", "modify", "change",
            "update", "edit", "pause", "resume", "clear", "disable"
    );

    private ToolStepResolutionUtils() {}

    public static boolean isHallucinatedFinish(ReasoningStep step) {
        return ReasoningStep.FINISH.equalsIgnoreCase(step.action())
                && step.actionInput() != null
                && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
    }

    public static ReasoningStep rescueFinish(ReasoningStep step, String sessionId) {
        log.infof("[ToolStepHandler] Rescued hallucinated 'finish' tool for session %s", sessionId);
        return ReasoningStep.pending(step.thought(), ReasoningStep.FINISH, Map.copyOf(step.actionInput()),      
                step.inputTokens(), step.outputTokens());
    }

    public static boolean isKnownTool(String toolName, ToolRegistry toolRegistry, Instance<DynamicToolLookup> dynamicToolLookup) {
        if (toolRegistry.findTool(toolName).isPresent()) return true;
        return !dynamicToolLookup.isUnsatisfied() && dynamicToolLookup.get().isDynamicToolAllowed(toolName);    
    }

    public static String buildDelegateGoal(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "Use the " + toolName + " tool and return the complete results.";
        }
        String action  = String.valueOf(args.getOrDefault("action",  "")).strip();
        String command = String.valueOf(args.getOrDefault("command", "")).strip();
        String target  = String.valueOf(args.getOrDefault("target",  "")).strip();

        StringBuilder sb = new StringBuilder("Use the ").append(toolName).append(" tool");
        if (!action.isBlank()  && !"null".equals(action))  sb.append(" with action=").append(action);
        if (!command.isBlank() && !"null".equals(command)) sb.append(" command=").append(command);
        if (!target.isBlank()  && !"null".equals(target))  sb.append(" for user/resource=").append(target);     
        sb.append(" and return the complete results.");
        return sb.toString();
    }

    public static boolean isListOnlyGoal(String goal) {
        if (goal == null || goal.isBlank()) return true;
        String lower = goal.toLowerCase();
        return MUTATING_GOAL_KEYWORDS.stream().noneMatch(lower::contains);
    }
}
