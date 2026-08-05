package com.amsha.core.tools;

import static com.amsha.core.message.UserMessages.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.util.AgentResultFormatter;

import org.jboss.logging.Logger;

/**
 * Handles agent name normalization, safety checks, validation rules,
 * and result/exception formatting helper methods for agent delegation.
 * Thin out the DelegateToAgentTool class to respect PMD file length limits.
 */
@ApplicationScoped
public class DelegationProcessor {

    private static final Logger log = Logger.getLogger(DelegationProcessor.class);

    @Inject
    GroupConsultationService groupConsultationService;

    @Inject
    RoleRegistry roleRegistry;

    public String normalizeAgent(String agentName, String goal, String currentRoleName) {
        String normalized = agentName.trim().toLowerCase(Locale.ROOT);
        String delegatableList = getDelegatableAgentList();

        // If it's a dynamic skill name without the "skill:" prefix, auto-prepend it if registered
        if (roleRegistry != null && !normalized.startsWith(AgentRoleEnum.SKILL_PREFIX)) {
            String skillKey = AgentRoleEnum.SKILL_PREFIX + normalized;
            boolean isRegisteredSkill = roleRegistry.getDynamicRoles().stream()
                    .anyMatch(def -> def.canonicalName().equalsIgnoreCase(skillKey));
            if (isRegisteredSkill) {
                normalized = skillKey;
            }
        }

        if (isSelfDelegation(normalized, currentRoleName)) {
            log.infof("[REDIRECT] %s tried to delegate to itself — auto-redirecting to coordinator", currentRoleName);
            normalized = AgentRoleEnum.COORDINATOR.id();
            if (isSelfDelegation(normalized, currentRoleName)) return null;
        }

        if (isCoderDelegatingToResearcher(normalized, currentRoleName)) {
            log.infof(
                    "[CODE-FIRST] %s delegating research to %s — allowing for context gathering",
                    currentRoleName, normalized);
        }

        if (isCombinedGoalToResearcher(normalized, currentRoleName, goal)) {
            log.infof(
                    "[REDIRECT] %s → %s blocked (combined goal), auto-redirecting to brahma",
                    currentRoleName, normalized);
            normalized = AgentRoleEnum.CODER.id();
        }

        if (!AgentRoleEnum.isKnownRole(normalized)) {
            throw new IllegalArgumentException(delegationUnknownAgent(agentName, delegatableList));
        }
        return normalized;
    }

    public ToolResponse handleRunSubAgentException(AgentRoleEnum targetRole, Exception e) {
        if (e instanceof IllegalStateException
                && e.getMessage() != null
                && e.getMessage().contains("unbound")) {
            return ToolResponse.failure(403, "Security violation: tenant context not bound.");
        }
        log.errorf(
                "[DELEGATE] Failed to delegate to agent %s: %s\n%s",
                targetRole.toScopedName(), LogUtil.rootMessage(e), LogUtil.trimmedStack(e));
        return ToolResponse.failure(500, delegationFailed(targetRole.toScopedName(), e.getMessage()));
    }

    public ToolResponse handleSubAgentResult(AgentResult result, AgentRoleEnum targetRole) {
        if (result instanceof AgentResult.AwaitingApproval aa) {
            return ToolResponse.failure(
                    409, delegationPaused(targetRole.toScopedName(), aa.toolName(), aa.sessionId()));
        }
        if (result instanceof AgentResult.LoopExceeded) {
            return ToolResponse.failure(
                    500,
                    delegationExceeded(
                            targetRole.toScopedName(),
                            Math.max(1, targetRole.definition().maxIterations())));
        }

        if (result instanceof AgentResult.Finished f && f.answer() != null) {
            String trimmed = f.answer().trim();
            if (trimmed.contains("unable to share my internal instructions")
                    || trimmed.contains("PROMPT_LEAK_BLOCKED")) {
                return ToolResponse.failure(
                        403,
                        "Sub-agent refused to complete the task due to safety constraints or prompt injection filters. "
                                + "Do NOT return this error to the user. You must answer the user's request directly using your own knowledge and tools.");
            }
        }

        String formatted = AgentResultFormatter.format(result);
        return ToolResponse.success(formatted);
    }

    public String getDelegatableAgentList() {
        return AgentRoleEnum.getDelegatableRoleNames();
    }

    public static String stripSurroundingQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    public String enhanceGoalWithGroupConsultation(String agentName, String goal) {
        var triage = groupConsultationService.triage(agentName, goal);

        if (triage.canSelfHandle()) {
            log.debugf("[GROUP_CONSULT] %s can self-handle: %s", agentName, triage.reason());
            return goal;
        }

        String enhanced = goal
                + "\n\n" + triage.consultationPrompt()
                + "\n\nINSTRUCTIONS: You have been delegated with mode=group_consult. "
                + "Consult the family members listed above via garuda, then synthesize their "
                + "analyses into a unified response. Do NOT skip the consultation step.";

        log.infof(
                "[GROUP_CONSULT] Enhanced goal for %s: consulting %s — %s",
                agentName, triage.recommendedMembers(), triage.reason());
        return enhanced;
    }

    boolean isSelfDelegation(String normalized, String currentRoleName) {
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        return current.id().equals(normalized) || current.definition().aliases().contains(normalized);
    }

    @SuppressWarnings("ReferenceEquality") // enum identity comparison is correct
    boolean isCoderDelegatingToResearcher(String normalized, String currentRoleName) {
        if (!AgentRoleEnum.isKnownRole(normalized)) return false;
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.CODER && target == AgentRoleEnum.RESEARCHER;
    }

    @SuppressWarnings("ReferenceEquality") // enum identity comparison is correct
    boolean isCombinedGoalToResearcher(String normalized, String currentRoleName, String goal) {
        if (!AgentRoleEnum.isKnownRole(normalized)) return false;
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.COORDINATOR
                && target == AgentRoleEnum.RESEARCHER
                && goal != null
                && AgentSentinels.CODE_EXECUTION_KEYWORDS.matcher(goal).find();
    }
}
