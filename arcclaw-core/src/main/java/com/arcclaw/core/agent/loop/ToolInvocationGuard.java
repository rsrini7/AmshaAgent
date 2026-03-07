package com.arcclaw.core.agent.loop;

import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.ReasoningStep;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.GithubTool;
import com.arcclaw.core.agent.RoleRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates tool calls before invocation.
 * Handles deduplication, security intent guards, and pre-invocation human-in-the-loop (HITL) approvals.
 */
@ApplicationScoped
public class ToolInvocationGuard {
    private static final Logger log = Logger.getLogger(ToolInvocationGuard.class);

    @Inject RoleRegistry roleRegistry;
    @Inject ObjectMapper objectMapper;
    @Inject StatefulAgentStore agentStore;

    // Use word boundaries (\b) so "pr" doesn't match "prepare" or "approve"
    private static final Pattern GITHUB_TRIGGER_PATTERN =
            Pattern.compile(
                    "(?i)\\b(repo|repository|github|gh|fetch|gist|clone|" +
                            "pull\\s+request|pr|get|show|describe|description|desc|" +
                            "star|fork|issue|commit|branch|tag|readme|release)\\b"
            );

    public sealed interface GuardDecision
            permits GuardDecision.Allow, GuardDecision.Deduplicate,
                    GuardDecision.PauseForApproval, GuardDecision.Blocked {
        record Allow() implements GuardDecision {}
        record Deduplicate(String cachedObservation) implements GuardDecision {}
        record PauseForApproval(String toolName, String paramsJson) implements GuardDecision {}
        record Blocked(String observation) implements GuardDecision {}
    }

    /**
     * Evaluates whether a tool call should proceed, be blocked, be deduplicated, or pause for approval.
     */
    public GuardDecision evaluate(String sessionId, String agentRole,
                                 String toolName, Map<String, Object> params,
                                 List<ReasoningStep> history, String goal) {

        // 1. DEDUPLICATION CHECK
        for (ReasoningStep past : history) {
            if (toolName.equals(past.action())
                    && past.observation() != null
                    && !past.observation().isBlank()) {

                // Primary check: Map equality (fast)
                boolean argsMatch = Objects.equals(params, past.actionInput());

                // Fallback check: JSON string equality (handles LinkedHashMap ordering differences)
                if (!argsMatch) {
                    try {
                        String currentArgs = objectMapper.writeValueAsString(params);
                        String pastArgs = objectMapper.writeValueAsString(past.actionInput());
                        argsMatch = currentArgs.equals(pastArgs);
                    } catch (Exception e) {
                        log.debugf("DEDUP-GUARD: JSON comparison failed for tool '%s' in session %s: %s",
                                toolName, sessionId, e.getMessage());
                    }
                }

                if (argsMatch) {
                    log.warnf("[DEDUP-GUARD] Duplicate tool call '%s' detected for session=%s — returning cached observation.",
                            toolName, sessionId);
                    String cachedObs = past.observation() + "\n\n[NOTE: This result was retrieved from " +
                            "the current session cache. The tool was not re-executed.]";
                    return new GuardDecision.Deduplicate(cachedObs);
                }
            }
        }

        // 2. GITHUB INTENT GUARD
        if (GithubTool.NAME.equals(toolName)) {
            boolean isPreApproved = agentStore.load(sessionId, agentRole)
                    .filter(cp -> cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL
                            && toolName.equals(cp.getPendingToolName()))
                    .isPresent();

            if (!isPreApproved && !GITHUB_TRIGGER_PATTERN.matcher(goal).find()) {
                String guardObs = "Tool 'github' was blocked by the Tool Intent Guard: " +
                        "the user's request does not reference a GitHub resource. " +
                        "Please answer the user's question using your own knowledge " +
                        "and respond with FINISH. Do not call any tools.";

                log.warnf("[TOOL-INTENT-GUARD] github blocked session=%s goal='%s'",
                        sessionId, goal.length() > 100 ? goal.substring(0, 100) + "..." : goal);
                return new GuardDecision.Blocked(guardObs);
            }
        }

        // 3. HITL PRE-APPROVAL CHECK
        AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
        boolean isSchedulingTool = "scheduletask".equals(toolName) || "manageschedule".equals(toolName);

        if (roleRegistry.requiresApprovalForTools(resolvedRole)
                && !isSchedulingTool
                && !AutonomyLevel.FULL.equals(WorkspaceContext.autonomy())) {

            try {
                String paramsJson = objectMapper.writeValueAsString(params);
                return new GuardDecision.PauseForApproval(toolName, paramsJson);
            } catch (Exception e) {
                log.errorf(e, "Failed to serialize params for HITL in session %s — blocking tool for safety",
                        sessionId);
                return new GuardDecision.Blocked(
                        "Tool blocked: failed to serialize parameters for approval.");
            }
        }

        return new GuardDecision.Allow();
    }
}
