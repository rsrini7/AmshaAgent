package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.common.util.LogUtil;
import org.jboss.logging.Logger;

@ApplicationScoped
public class BrowserPreflightHandler {

    private static final Logger log = Logger.getLogger(BrowserPreflightHandler.class);

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final ApprovalCheckpointManager checkpointManager;

    @Inject
    public BrowserPreflightHandler(ToolRegistry toolRegistry,
                                    ToolInvoker toolInvoker,
                                    ApprovalCheckpointManager checkpointManager) {
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
        this.checkpointManager = checkpointManager;
    }

    public Optional<AgentResult> run(AgentExecutionContext context, List<ReasoningStep> history) {
        if (!history.isEmpty()) {
            return Optional.empty();
        }
        Optional<ReasoningStep> preflightOpt = buildStep(context);
        if (preflightOpt.isEmpty()) {
            return Optional.empty();
        }
        ReasoningStep preflightStep = preflightOpt.get();
        try {
            ToolResponse response = WorkspaceContext.run(
                    context.sessionId(), context.userId(), context.chatId(),
                    context.autonomyLevel(), context.agentRole(), context.channelType(),
                    () -> toolInvoker.invoke(preflightStep.action(), preflightStep.actionInput(), context.sessionId()));

            ReasoningStep completed = preflightStep.withObservation(
                    response.success() ? response.output() : "Browser navigation failed: " + response.output(),
                    response.success());
            history.add(completed);
        } catch (RequiresApprovalException e) {
            log.infof("[BrowserPreflight] Tool '%s' requires approval for session %s - pausing for human-in-the-loop",
                    preflightStep.action(), context.sessionId());
            List<ReasoningStep> historyAtPause = appendPendingStep(history, preflightStep);
            return Optional.of(checkpointManager.persist(context,
                    e.getFrame(), historyAtPause,
                    context.channelType(), context.channelPlatformId()));
        } catch (Exception e) {
            log.warnf("[BrowserPreflight] Failed for session %s: %s", context.sessionId(), LogUtil.rootMessage(e));
        }
        return Optional.empty();
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
