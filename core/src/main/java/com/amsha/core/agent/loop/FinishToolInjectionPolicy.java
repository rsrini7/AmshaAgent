package com.amsha.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.tools.CodeLanguageDetector;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;

import org.jboss.logging.Logger;

/**
 * Missed-tool injection policy evaluated at FINISH time. Handles three cases:
 * 1. Refusal + research goal → inject search tool
 * 2. Execution goal + no code in answer + sandbox available → force sandbox_exec correction
 * 3. Scheduling goal + not scheduled + schedule_task permitted → force scheduling correction
 */
class FinishToolInjectionPolicy {

    private static final Logger log = Logger.getLogger(FinishToolInjectionPolicy.class);

    private static final List<String> SEARCH_TOOL_NAMES = List.of(
            ToolCapability.WEB_SEARCH_SERPER.toolName(),
            ToolCapability.WEB_SEARCH_BRAVE.toolName(),
            "web_search",
            "search");

    private final IntentClassificationService intentClassifier;
    private final ToolRegistry toolRegistry;
    private final RoleRegistry roleRegistry;
    private final FinishOutcomeProcessor resultProcessor;

    FinishToolInjectionPolicy(
            IntentClassificationService intentClassifier,
            ToolRegistry toolRegistry,
            RoleRegistry roleRegistry,
            FinishOutcomeProcessor resultProcessor) {
        this.intentClassifier = intentClassifier;
        this.toolRegistry = toolRegistry;
        this.roleRegistry = roleRegistry;
        this.resultProcessor = resultProcessor;
    }

    /**
     * If the agent finished without using the right tool, inject it.
     */
    FinishActionHandler.FinishOutcome tryInjectToolCall(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep.FinishStep step,
            String answer,
            String sessionId) {
        String goal = context.goal();
        if (goal == null || goal.isBlank()) return null;
        if (intentClassifier != null && intentClassifier.isSimpleConversational(goal)) {
            return null;
        }

        String availableTools = String.join(",", toolRegistry.listTools());
        boolean isRefusal = AgentSentinels.REFUSAL_PATTERN.matcher(answer).find();

        // Case 1: Refusal + research goal → inject search
        if (isRefusal && AgentSentinels.RESEARCH_KEYWORDS.matcher(goal).find()) {
            String searchTool = findSearchTool(availableTools);
            if (searchTool != null && isToolPermitted(context.agentRole(), searchTool)) {
                log.infof(
                        "TOOL-INJECT session %s: agent finished without searching, injecting %s",
                        sessionId, searchTool);
                Map<String, Object> args = new HashMap<>();
                args.put(
                        "query",
                        goal.replaceAll("(?i)^(do )?research (on )?(use )?", "").trim());
                args.put("count", 5);
                return new FinishActionHandler.FinishOutcome.InjectToolCall(
                        searchTool, args, step.withObservation("Injecting search tool call that was missed"));
            }
        }

        // Case 2: Execution goal + no code in answer + sandbox available → retry with correction
        // Skip correction if sandbox_exec was already called successfully — the answer
        // describes execution output, not source code, so code fence check would fail spuriously.
        if (AgentSentinels.EXECUTION_KEYWORDS.matcher(goal).find()
                && isToolPermitted(context.agentRole(), ToolCapability.SANDBOX_EXEC.toolName())
                && !answer.contains("```")
                && !historyContainsSuccess(ToolCapability.SANDBOX_EXEC.toolName(), history)) {
            log.infof("TOOL-INJECT session %s: execution goal but no code in answer, forcing correction", sessionId);
            return new FinishActionHandler.FinishOutcome.Correction(
                    step.withObservation("Agent finished without writing code for an execution goal"),
                    "STOP. You MUST write a complete program and call sandbox_exec to execute it. "
                            + "The sandbox_exec tool requires 'language' (e.g. "
                            + CodeLanguageDetector.SUPPORTED_LANGUAGES_HINT
                            + ") and 'code' parameters. "
                            + "Do NOT explain. Do NOT research. Write the code NOW and call sandbox_exec immediately.");
        }

        // Case 3: Scheduling goal + not scheduled + schedule_task permitted -> force scheduling correction
        // BUT skip if the goal is about SEARCHING for scheduled tasks (e.g., "search memory for cron tasks we
        // scheduled")
        boolean isSchedulingGoal =
                AgentSentinels.SCHEDULING_KEYWORDS.matcher(goal).find();
        boolean isSearchGoal = AgentSentinels.RESEARCH_KEYWORDS.matcher(goal).find()
                || goal.toLowerCase(Locale.ROOT).contains("memory")
                || goal.toLowerCase(Locale.ROOT).contains("recall")
                || goal.toLowerCase(Locale.ROOT).contains("find");
        if (isSchedulingGoal
                && !isSearchGoal
                && isToolPermitted(context.agentRole(), ToolCapability.SCHEDULE_TASK.toolName())) {
            boolean actuallyScheduled = resultProcessor.historyContainsSuccessfulSchedule(history);
            if (!actuallyScheduled) {
                log.infof(
                        "TOOL-INJECT session %s: scheduling goal but no schedule_task call found, forcing correction",
                        sessionId);
                return new FinishActionHandler.FinishOutcome.Correction(
                        step.withObservation("Agent finished without calling schedule_task for a scheduling goal"),
                        "System: STOP. The user requested a future/scheduled action, but you did NOT call "
                                + "the schedule_task tool. You MUST compute the correct cron expression (based on current system time "
                                + "and timezone conversions if any) and call the schedule_task tool now before finishing.");
            }
        }

        return null;
    }

    private String findSearchTool(String availableTools) {
        for (String name : SEARCH_TOOL_NAMES) {
            if (availableTools.contains(name)) return name;
        }
        return null;
    }

    private boolean isToolPermitted(String agentRole, String toolName) {
        return roleRegistry.resolvePermittedTools(agentRole).contains(toolName);
    }

    private static boolean historyContainsSuccess(String toolName, List<ReasoningStep> history) {
        return history.stream()
                .anyMatch(s -> s instanceof ReasoningStep.ToolCallStep tc
                        && toolName.equals(tc.action())
                        && Boolean.TRUE.equals(tc.success()));
    }
}
