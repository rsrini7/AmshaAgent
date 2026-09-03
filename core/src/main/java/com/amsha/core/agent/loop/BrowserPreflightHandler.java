package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.message.UserMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.CronTimeResolver;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class BrowserPreflightHandler {

    private static final Logger log = Logger.getLogger(BrowserPreflightHandler.class);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final ApprovalCheckpointManager checkpointManager;
    private final ToolInvocationGuard invocationGuard;

    private static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    @ConfigProperty(name = "amsha.agent.default-timezone", defaultValue = DEFAULT_TIMEZONE)
    String defaultTimezone = DEFAULT_TIMEZONE;

    @Inject
    public BrowserPreflightHandler(
            ToolRegistry toolRegistry,
            ToolInvoker toolInvoker,
            ApprovalCheckpointManager checkpointManager,
            ToolInvocationGuard invocationGuard) {
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
        this.checkpointManager = checkpointManager;
        this.invocationGuard = invocationGuard;
    }

    public Optional<AgentResult> run(AgentExecutionContext context, List<ReasoningStep> history) {
        if (!history.isEmpty()) {
            return Optional.empty();
        }
        Optional<ReasoningStep> preflightOpt = buildScheduleStep(context);
        if (preflightOpt.isEmpty()) {
            preflightOpt = buildStep(context);
        }
        if (preflightOpt.isEmpty()) {
            return Optional.empty();
        }
        ReasoningStep preflightStep = preflightOpt.get();
        try {
            ToolInvocationGuard.GuardDecision guardDecision = invocationGuard.evaluate(
                    context.sessionId(),
                    context.agentRole(),
                    context.autonomyLevel(),
                    preflightStep.action(),
                    preflightStep.actionInput(),
                    history,
                    context.goal(),
                    List.of());
            if (guardDecision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval pause) {
                return Optional.of(checkpointManager.persist(
                        context,
                        pause.frame(),
                        appendPendingStep(history, preflightStep),
                        context.channelType(),
                        context.channelPlatformId()));
            }
            if (guardDecision instanceof ToolInvocationGuard.GuardDecision.Blocked blocked) {
                return Optional.of(AgentResult.failed(
                        blocked.observation(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
            }
            if (guardDecision instanceof ToolInvocationGuard.GuardDecision.Deduplicate duplicate) {
                ReasoningStep completed = preflightStep.withObservation(duplicate.cachedObservation(), true);
                history.add(completed);
                return Optional.of(AgentResult.finished(duplicate.cachedObservation(), List.copyOf(history)));
            }

            ToolResponse response = invokeTool(context, preflightStep);
            String output = response != null ? response.output() : "No tool response was returned.";
            String safeOutput = UserMessages.stripInternalDirectiveTail(output);
            ReasoningStep completed = preflightStep.withObservation(
                    response != null && response.success() ? safeOutput : "Tool execution failed: " + safeOutput,
                    response != null && response.success());
            history.add(completed);
            if (response == null || !response.success()) {
                return Optional.of(
                        AgentResult.failed(safeOutput, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
            }
            String answer = ToolCapability.SCHEDULE_TASK.toolName().equals(preflightStep.action())
                    ? "✅ " + safeOutput
                    : safeOutput;
            return Optional.of(AgentResult.finished(answer, List.copyOf(history)));
        } catch (RequiresApprovalException e) {
            log.infof(
                    "[BrowserPreflight] Tool '%s' requires approval for session %s - pausing for human-in-the-loop",
                    preflightStep.action(), context.sessionId());
            List<ReasoningStep> historyAtPause = appendPendingStep(history, preflightStep);
            return Optional.of(checkpointManager.persist(
                    context, e.getFrame(), historyAtPause, context.channelType(), context.channelPlatformId()));
        } catch (Exception e) {
            log.warnf("[BrowserPreflight] Failed for session %s: %s", context.sessionId(), LogUtil.rootMessage(e));
        }
        return Optional.empty();
    }

    private Optional<ReasoningStep> buildScheduleStep(AgentExecutionContext context) {
        Optional<AgentTool> toolOpt = toolRegistry.findTool(ToolCapability.SCHEDULE_TASK.toolName());
        if (toolOpt.isEmpty() || !toolOpt.map(AgentTool::isEnabled).orElse(false)) {
            return Optional.empty();
        }
        ZoneId userZone;
        try {
            String configuredTimezone =
                    defaultTimezone == null || defaultTimezone.isBlank() ? DEFAULT_TIMEZONE : defaultTimezone;
            userZone = ZoneId.of(configuredTimezone);
        } catch (RuntimeException e) {
            log.warnf("[BrowserPreflight] Invalid default timezone; using %s", DEFAULT_TIMEZONE);
            userZone = ZoneId.of(DEFAULT_TIMEZONE);
        }
        return CronTimeResolver.parseSchedulingRequest(context.goal(), userZone).map(request -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("goal", request.goal());
            params.put("runOnce", request.runOnce());
            params.put("timezone", request.timezone().getId());
            if (!request.runOnce()) {
                params.put("cronExpression", request.cronExpression());
            }
            return ReasoningStep.fastPath(
                    "Pre-flight: schedule_task resolved without model inference.",
                    ToolCapability.SCHEDULE_TASK.toolName(),
                    params);
        });
    }

    private ToolResponse invokeTool(AgentExecutionContext context, ReasoningStep step) throws Exception {
        return WorkspaceContext.run(
                context.sessionId(),
                context.userId(),
                context.chatId(),
                context.autonomyLevel(),
                context.agentRole(),
                context.channelType(),
                context.channelPlatformId(),
                () -> withSchedulingContext(context, step));
    }

    private ToolResponse withSchedulingContext(AgentExecutionContext context, ReasoningStep step) throws Exception {
        var scope = ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, context.goal());
        String suggestedCron = suggestedValue(context, "SUGGESTED_CRON");
        String suggestedNextFireTime = suggestedValue(context, "SUGGESTED_NEXT_FIRE_TIME");
        String suggestedCronSource = suggestedValue(context, "SUGGESTED_CRON_SOURCE");
        if (!suggestedCron.isBlank()) {
            scope = scope.where(WorkspaceContext.SUGGESTED_CRON, suggestedCron);
        }
        if (!suggestedNextFireTime.isBlank()) {
            scope = scope.where(WorkspaceContext.SUGGESTED_NEXT_FIRE_TIME, suggestedNextFireTime);
        }
        if (!suggestedCronSource.isBlank()) {
            scope = scope.where(WorkspaceContext.SUGGESTED_CRON_SOURCE, suggestedCronSource);
        }
        return scope.call(() -> toolInvoker.invoke(step.action(), step.actionInput(), context.sessionId()));
    }

    private static String suggestedValue(AgentExecutionContext context, String key) {
        Object value = context.data().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Optional<ReasoningStep> buildStep(AgentExecutionContext context) {
        if (!AgentRoleEnum.RESEARCHER.toScopedName().equalsIgnoreCase(context.agentRole())) {
            return Optional.empty();
        }
        Optional<AgentTool> toolOpt = toolRegistry.findTool(ToolCapability.BROWSER_NAVIGATE.toolName());
        if (toolOpt.isEmpty() || !toolOpt.map(AgentTool::isEnabled).orElse(false)) {
            return Optional.empty();
        }
        String goal = context.goal();
        if (goal == null) {
            return Optional.empty();
        }
        Matcher m = URL_PATTERN.matcher(goal);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(ReasoningStep.fastPath(
                "Pre-flight: URL detected in goal, navigating directly.",
                ToolCapability.BROWSER_NAVIGATE.toolName(),
                Map.of("url", m.group(), "action", "extract_text")));
    }

    private List<ReasoningStep> appendPendingStep(List<ReasoningStep> history, ReasoningStep step) {
        List<ReasoningStep> snapshot = new ArrayList<>(history);
        snapshot.add(step);
        return List.copyOf(snapshot);
    }
}
