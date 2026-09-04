package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.message.UserMessages;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.jboss.logging.Logger;

public class ToolResultProcessor {

    private static final Logger log = Logger.getLogger(ToolResultProcessor.class);

    private final ChatMemoryWriter writer;

    public ToolResultProcessor(ChatMemoryWriter writer) {
        this.writer = writer;
    }

    public String buildObservation(ToolResponse response) {
        return switch (response) {
            case null -> "Tool invocation returned no response.";
            case ToolResponse.ToolSuccess success -> success.output();
            case ToolResponse.ToolFailure failure -> {
                String out = failure.output();
                if (out == null || out.isEmpty()) {
                    yield "Tool execution failed: No output (exit=" + failure.exitCode() + ")";
                }
                if (out.startsWith("Tool execution failed:")) yield out;
                yield "Tool execution failed: " + out + " (exit=" + failure.exitCode() + ")";
            }
            case ToolResponse.ToolPending pending -> pending.output();
        };
    }

    public StepDecision resolveOutcome(
            AgentExecutionContext context,
            String toolName,
            Map<String, Object> params,
            ToolResponse response,
            String observation,
            ReasoningStep completedStep,
            List<ReasoningStep> history,
            int consecutiveFailures) {

        StepDecision decision = null;
        if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)) {
            decision = handleScheduleTaskOutcome(context, response, observation, history);
        } else if (ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
            decision = handleManageScheduleOutcome(context, params, response, observation, history);
        } else if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) {
            decision = handleDelegateOutcome(context, response, observation, history);
        } else if (ToolCapability.GITHUB.toolName().equalsIgnoreCase(toolName)) {
            decision = handleGithubUnavailableOutcome(context, response, observation, history);
        } else if (ToolCapability.BROWSER_NAVIGATE.toolName().equalsIgnoreCase(toolName)) {
            decision = handleBrowserOutcome(context, params, response, observation, history);
        } else if (ToolCapability.WEB_FETCH.toolName().equalsIgnoreCase(toolName)) {
            decision = handleWebFetchOutcome(context, params, response, observation, history);
        }

        if (decision == null
                && response != null
                && response.success()
                && isArtifactCapableTool(toolName)
                && ToolStepResolutionUtils.isArtifactCompletionSignal(completedStep)) {
            String finalAnswer = "The requested artifact was generated and opened successfully.";
            if (context.sessionId() != null) {
                writer.saveAnswer(context.sessionId(), finalAnswer);
            }
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }

        if (decision != null) {
            return decision;
        }

        return evaluateCircuitBreaker(completedStep, response, consecutiveFailures, context.sessionId(), history);
    }

    /**
     * Returns true only for tools that can produce shell-style artifacts (commands, generated files, browser
     * preview launches). Known non-artifact tools such as {@code serper_web_search}, {@code brave_web_search},
     * {@code web_fetch}, {@code github}, memory tools, and delegation tools are excluded so that broad
     * substring hints in their observations (e.g. "open source") do not falsely terminate the agent loop.
     * Unknown/dynamic tool names are treated as artifact-capable so skill-based custom renderers still work.
     */
    private boolean isArtifactCapableTool(String toolName) {
        return ToolCapability.fromToolName(toolName)
                .map(cap -> cap == ToolCapability.HOST_EXEC
                        || cap == ToolCapability.SANDBOX_EXEC
                        || cap == ToolCapability.BROWSER_NAVIGATE)
                .orElse(true);
    }

    public StepDecision evaluateCircuitBreaker(
            ReasoningStep step, ToolResponse res, int failures, String sid, List<ReasoningStep> history) {
        int newFailures = (res != null && res.success()) ? 0 : failures + 1;

        if (newFailures >= 3) {
            log.warnf(
                    "[STATE-MACHINE] Circuit breaker tripped session=%s after %d consecutive failures",
                    sid, newFailures);
            return StepDecision.terminal(AgentResult.finished(
                    "❌ I encountered repeated errors (" + newFailures
                            + "). Please rephrase your request or check the tool parameters.",
                    List.copyOf(history)));
        }
        return StepDecision.proceedWithFailures(step, newFailures);
    }

    private StepDecision handleScheduleTaskOutcome(
            AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null && res.success()) {
            String cleanOutput = UserMessages.stripInternalDirectiveTail(obs);
            String finalAnswer = "✅ " + cleanOutput;
            writer.saveAnswer(ctx.sessionId(), finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }
        return null;
    }

    private StepDecision handleManageScheduleOutcome(
            AgentExecutionContext ctx,
            Map<String, Object> params,
            ToolResponse res,
            String obs,
            List<ReasoningStep> history) {
        String action = String.valueOf(params.getOrDefault("action", "")).toLowerCase(Locale.ROOT);
        if (!"list".equals(action)) {
            String finalAnswer = (res != null && res.success() ? "✅ " : "❌ ") + obs;
            writer.saveAnswer(ctx.sessionId(), finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }
        if (ToolStepResolutionUtils.isListOnlyGoal(ctx.goal())) {
            writer.saveAnswer(ctx.sessionId(), obs);
            return StepDecision.terminal(AgentResult.finished(obs, List.copyOf(history)));
        }
        return null;
    }

    private static final Pattern PAUSED_TOOL_PATTERN = Pattern.compile("tool '([^']+)' needs human approval");

    private static String extractPausedTool(String obs) {
        if (obs != null) {
            var matcher = PAUSED_TOOL_PATTERN.matcher(obs);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return ToolCapability.DELEGATE_TO_AGENT.toolName();
    }

    private StepDecision handleDelegateOutcome(
            AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        // Delegation paused for human approval — deterministic terminal with a user-safe
        // message, preserving AWAITING_APPROVAL state across the parent and scheduler loops.
        // The delegate checkpoint is AWAITING_APPROVAL with channel info so the user can
        // approve the inner session directly. Matches BOTH shapes: success-shaped (F1 fix) and legacy
        // failure(409).
        if (res != null
                && (obs.contains(UserMessages.DELEGATION_PAUSED_MARKER)
                        || (!res.success() && obs.contains("paused for human approval")))) {
            String msg = "⏳ " + UserMessages.userSafePortion(obs);
            writer.saveAnswer(ctx.sessionId(), msg);
            String tool = extractPausedTool(obs);
            ApprovalFrame frame = new ApprovalFrame(tool, Map.of(), RiskLevel.HIGH);
            return StepDecision.terminal(AgentResult.awaitingApproval(frame, ctx.sessionId(), List.copyOf(history)));
        }
        if (res != null
                && res.success()
                && !obs.startsWith("⚠️")
                && !obs.startsWith("⏳")
                && !obs.startsWith("Tool execution failed")) {
            // Don't terminate — let the parent agent process the sub-agent's result.
            // The LLM will decide whether to delegate again, write code, or finish.
            return null;
        }
        return null;
    }

    private StepDecision handleGithubUnavailableOutcome(
            AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null && !res.success() && obs.contains("GitHub integration is not available")) {
            if (ctx.sessionId() != null) writer.saveAnswer(ctx.sessionId(), obs);
            return StepDecision.terminal(AgentResult.finished(obs, List.copyOf(history)));
        }
        return null;
    }

    private StepDecision handleBrowserOutcome(
            AgentExecutionContext _ctx,
            Map<String, Object> params,
            ToolResponse res,
            String obs,
            List<ReasoningStep> history) {
        if (res != null && !res.success()) {
            String url = String.valueOf(params.getOrDefault("url", ""));
            if (!url.isBlank()) {
                log.infof("[TOOL-STEP] browser_navigate failed for %s - triggering fallback to serper_web_search", url);

                String fallbackGoal = "The browser was unable to visit " + url + " directly. "
                        + "Please use serper_web_search to find information about this page or its content.";

                ReasoningStep fallbackStep = ReasoningStep.observeStep(
                        ToolCapability.BROWSER_NAVIGATE.toolName(),
                        "Tool execution failed: " + obs + ". Fallback: " + fallbackGoal,
                        "Browser navigation failed, falling back to search.");
                history.add(fallbackStep);

                return StepDecision.retryEngine(fallbackGoal, 0);
            }
        }
        return null;
    }

    private StepDecision handleWebFetchOutcome(
            AgentExecutionContext _ctx,
            Map<String, Object> params,
            ToolResponse res,
            String obs,
            List<ReasoningStep> history) {
        if (res != null && !res.success()) {
            String url = String.valueOf(params.getOrDefault("url", ""));
            if (!url.isBlank()) {
                log.infof("[TOOL-STEP] web_fetch failed for %s - triggering fallback to browser_navigate", url);

                String fallbackGoal = "The web_fetch tool was unable to retrieve " + url + " (error: " + obs + "). "
                        + "Please try browser_navigate to visit this URL instead.";

                ReasoningStep fallbackStep = ReasoningStep.observeStep(
                        ToolCapability.WEB_FETCH.toolName(),
                        "Tool execution failed: " + obs + ". Fallback: " + fallbackGoal,
                        "web_fetch failed, falling back to browser_navigate.");
                history.add(fallbackStep);

                return StepDecision.retryEngine(fallbackGoal, 0);
            }
        }
        return null;
    }
}
