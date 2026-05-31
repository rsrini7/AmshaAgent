package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.tools.ToolResponse;

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

        if (decision != null) {
            return decision;
        }

        return evaluateCircuitBreaker(completedStep, response, consecutiveFailures, context.sessionId(), history);
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
            String cleanOutput = obs.split("\n\nCRITICAL DIRECTIVE:", 2)[0];
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

    private StepDecision handleDelegateOutcome(
            AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null
                && res.success()
                && !obs.startsWith("⚠️")
                && !obs.startsWith("⏳")
                && !obs.startsWith("Tool execution failed")) {
            // Don't terminate — let the parent agent process the sub-agent's result.
            // The LLM will decide whether to delegate again, write code, or finish.
            return null;
        }
        // Delegation paused for human approval — terminal with message, don't retry.
        // The delegate checkpoint is AWAITING_APPROVAL with channel info so the user
        // can approve the inner session directly.
        if (res != null && !res.success() && obs.contains("paused for human approval")) {
            String msg = "⏳ " + obs;
            writer.saveAnswer(ctx.sessionId(), msg);
            return StepDecision.terminal(AgentResult.finished(msg, List.copyOf(history)));
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
