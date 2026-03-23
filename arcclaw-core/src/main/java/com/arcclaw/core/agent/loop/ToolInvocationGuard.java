package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.GithubTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Validates tool calls before invocation.
 * Handles deduplication, security intent guards, and pre-invocation
 * human-in-the-loop (HITL) approvals.
 */
@ApplicationScoped
public class ToolInvocationGuard {
    private static final Logger log = Logger.getLogger(ToolInvocationGuard.class);

    @Inject
    public RoleRegistry roleRegistry;
    @Inject
    public ObjectMapper objectMapper;
    @Inject
    public StatefulAgentStore agentStore;
    @Inject
    public ToolRegistry toolRegistry;
    @Inject
    public Instance<DynamicToolLookup> dynamicToolLookup;
    @Inject
    public ChatMemoryStore chatMemoryStore;

    // Use word boundaries (\b) so "pr" doesn't match "prepare" or "approve"
    private static final Pattern GITHUB_TRIGGER_PATTERN = Pattern.compile(
            "(?i)\\b(repo|repository|github|gh|fetch|gist|gists|clone|" +
                    "pull\\s+request|pr|get|show|describe|description|desc|" +
                    "star|fork|issue|commit|branch|tag|readme|release)\\b");

    public sealed interface GuardDecision
            permits GuardDecision.Allow, GuardDecision.Deduplicate,
            GuardDecision.PauseForApproval, GuardDecision.Blocked {
        record Allow() implements GuardDecision {}
        record Deduplicate(String cachedObservation) implements GuardDecision {}
        record PauseForApproval(String toolName, String paramsJson) implements GuardDecision {}
        record Blocked(String observation) implements GuardDecision {}
    }

    /**
     * Evaluates whether a tool call should proceed, be blocked, be deduplicated, or
     * pause for approval.
     */
    public GuardDecision evaluate(String sessionId, String agentRole,
            String toolName, Map<String, Object> params,
            List<ReasoningStep> history, String goal, List<CompiledSkillEntry> skillSnapshot) {

        // 1. DEDUPLICATION CHECK
        for (ReasoningStep past : history) {
            if (toolName.equals(past.action())
                    && past.observation() != null
                    && !past.observation().isBlank()) {

                // Primary check: Map equality (fast)
                boolean argsMatch = Objects.equals(params, past.actionInput());

                // Fallback check: JSON string equality (handles LinkedHashMap ordering
                // differences)
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
                    log.warnf(
                            "[DEDUP-GUARD] Duplicate tool call '%s' detected for session=%s — returning cached observation.",
                            toolName, sessionId);
                    String cachedObs = past.observation() + "\n\n[NOTE: This result was retrieved from " +
                            "the current session cache. The tool was not re-executed.]";
                    return new GuardDecision.Deduplicate(cachedObs);
                }
            }
        }

        // 1.1 CROSS-LOOP DEDUP (GitHub only)
        // If ChatMemory already has a github result for the same args in this session,
        // let the LLM reuse it instead of triggering a new HITL prompt.
        if (GithubTool.NAME.equals(toolName)) {
            try {
                List<ChatMessage> memory = chatMemoryStore.getMessages(sessionId);
                if (memory != null) {
                    String currentArgsJson = objectMapper.writeValueAsString(params);
                    // Match prefix to handle potential JSON field ordering or truncation differences
                    String matchPrefix = currentArgsJson.substring(0, Math.min(20, currentArgsJson.length()));
                    for (ChatMessage msg : memory) {
                        if (msg instanceof AiMessage ai && ai.text() != null && ai.text().contains(matchPrefix)) {
                            log.debugf("[DEDUP-GUARD] Cross-loop github match for session %s. Allowing reuse.", sessionId);
                            return new GuardDecision.Allow();
                        }
                    }
                }
            } catch (Exception e) {
                log.warnf("Cross-loop dedup failed for github in session %s: %s", sessionId, e.getMessage());
                log.debug("Full cross-loop dedup failure stack trace:", e);
            }
        }

        // 1.5 DYNAMIC TOOL FALLBACK
        // Only bypass for tools that are NOT registered as internal tools.
        // Internal tools (github, memory_search) MUST still go through HITL.
        // The self-MCP connection re-exposes internal tools as dynamic tools,
        // so without this guard, isDynamicToolAllowed("github") bypasses HITL.
        boolean isInternalTool = toolRegistry.findTool(toolName).isPresent();
        if (!isInternalTool
                && dynamicToolLookup != null && !dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName)) {
            return new GuardDecision.Allow(); // permitted via dynamic MCP registry (external only)
        }

        // 2. GITHUB INTENT GUARD
        if (GithubTool.NAME.equals(toolName)) {
            boolean isPreApproved = agentStore.load(sessionId, agentRole)
                    .filter(cp -> cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL
                            && toolName.equals(cp.getPendingToolName()))
                    .isPresent();

            boolean isSentinelGoal = AgentSentinels.GOAL_TOOL_APPROVED.equals(goal)
                    || AgentSentinels.GOAL_RESUME_APPROVED.equals(goal);

            boolean hasRecentGithubContext = history.stream()
                    .anyMatch(s -> GithubTool.NAME.equals(s.action())
                            && s.observation() != null
                            && !s.observation().isBlank());

            if (!isPreApproved
                    && !isSentinelGoal
                    && !hasRecentGithubContext
                    && !GITHUB_TRIGGER_PATTERN.matcher(goal).find()) {
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
        //
        // Gate logic: BOTH the role policy AND the tool's own declaration must agree
        // that approval is needed. Using OR here would cause read-only tools (github
        // view/list, requiresManualApproval=false) to still trigger HITL in SUPERVISED
        // mode purely because the researcher role has requiresApprovalForTools=true —
        // which defeats the purpose of the per-tool approval declaration.
        //
        // Decision table:
        //   role=requiresApproval  tool=requiresApproval  → HITL?
        //   true                   true                   → YES  (write ops, e.g. future gh create)
        //   true                   false                  → NO   (read-only gh view/list)
        //   false                  true                   → NO   (coordinator with write tool — role trusts itself)
        //   false                  false                  → NO   (fully autonomous read)
        AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
        boolean isSchedulingTool = ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName);

        boolean toolRequiresApproval = toolRegistry.findTool(toolName)
                .map(tool -> tool.requiresManualApproval(params))
                .orElse(false);

        if (roleRegistry.requiresApprovalForTools(resolvedRole)
                || toolRequiresApproval
                && !isSchedulingTool
                && !AutonomyLevel.FULL.equals(WorkspaceContext.autonomy())) {

            try {
                String paramsJson = objectMapper.writeValueAsString(params);
                return new GuardDecision.PauseForApproval(toolName, paramsJson);
            } catch (Exception e) {
                log.errorf("Failed to serialize params for HITL in session %s — blocking tool for safety: %s", sessionId, e.getMessage());
                log.debug("Full parameter serialization failure stack trace:", e);
                return new GuardDecision.Blocked(
                        "Tool blocked: failed to serialize parameters for approval.");
            }
        }

        return new GuardDecision.Allow();
    }
}
