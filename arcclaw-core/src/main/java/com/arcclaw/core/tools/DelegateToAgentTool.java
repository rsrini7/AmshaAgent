package com.arcclaw.core.tools;

import static com.arcclaw.core.message.UserMessages.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.util.AgentResultFormatter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Allows the coordinator to delegate specialized tasks (like coding or debugging)
 * to domain expert sub-agents (e.g., Shiva, Brahma).
 */
@ApplicationScoped
public class DelegateToAgentTool implements AgentTool {

    private static final Logger log = Logger.getLogger(DelegateToAgentTool.class);

    @Inject ReasoningEngine reasoningEngine;
    @Inject Instance<AgentLoop> agentLoop;

    @ConfigProperty(name = "arcclaw.delegation.max-depth", defaultValue = "2")
    int maxDelegationDepth = 2;

    @Override
    public String name() {
        return ToolCapability.DELEGATE_TO_AGENT.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.DELEGATE_TO_AGENT.description();
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.DELEGATE_AUTONOMOUS;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String currentRoleName;
        String outerSessionId;
        String userId;
        String chatId;

        try {
            // NS2: Fail fast on missing context
            currentRoleName = WorkspaceContext.requireAgentRole();
            outerSessionId  = WorkspaceContext.requireSessionId();
            userId          = WorkspaceContext.requireUserId();
            chatId          = WorkspaceContext.requireChatId();
        } catch (IllegalStateException e) {
            // NS2 backward compatibility: convert missing context into 403 ToolResponse
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }

        Object agentObj = params.get("agent");
        Object goalObj = params.get("goal");

        String delegatableList = getDelegatableAgentList();

        if (agentObj == null || String.valueOf(agentObj).isBlank()) {
            return ToolResponse.failure(400, delegationAgentRequired(delegatableList));
        }
        if (goalObj == null || String.valueOf(goalObj).isBlank()) {
            return ToolResponse.failure(400, DELEGATION_GOAL_REQUIRED);
        }

        String agentName = String.valueOf(agentObj);
        String goal = String.valueOf(goalObj);

        String normalized = agentName.trim().toLowerCase();

        // 0. Recursion Guard: Prevent infinite delegation loops
        int depth = outerSessionId.split(Pattern.quote(AgentSentinels.DELEGATE_SESSION_SENTINEL)).length - 1;
        if (depth >= maxDelegationDepth) {
            log.warnf("[DELEGATE] Hard block: delegation depth limit (%d) reached for session %s", maxDelegationDepth, outerSessionId);
            return ToolResponse.failure(400, String.format(
                "Recursion limit exceeded: Maximum delegation depth of %d reached. " +
                "You cannot delegate further to prevent infinite loops.", maxDelegationDepth));
        }

        // 1. Self-delegation guard using registry metadata
        if (isSelfDelegation(normalized, currentRoleName)) {
            return ToolResponse.failure(400,
                delegationSelfGuard(normalized, delegatableList));
        }

        // 2. Role validation
        if (!AgentRoleEnum.isKnownRole(agentName)) {
            return ToolResponse.failure(400,
                delegationUnknownAgent(agentName, delegatableList));
        }

        AgentRoleEnum targetRole = AgentRoleEnum.fromString(agentName);

        // 2a. Governance Guard: Capability Escalation Check (§4.10)
        AgentRoleEnum currentRole = AgentRoleEnum.fromString(currentRoleName);

        if (!currentRole.equals(AgentRoleEnum.COORDINATOR)) {
            Set<ToolCapability> currentCaps = currentRole.definition().capabilities();
            Set<ToolCapability> targetCaps = targetRole.definition().capabilities();

            Set<ToolCapability> unauthorizedCaps = new HashSet<>(targetCaps);
            unauthorizedCaps.removeAll(currentCaps);

            if (!unauthorizedCaps.isEmpty()) {
                String unauthorizedList = unauthorizedCaps.stream()
                    .map(Enum::name)
                    .sorted()
                    .collect(Collectors.joining(", "));
                
                return ToolResponse.failure(403,
                    delegationRejectedCaps(targetRole.toScopedName(), unauthorizedList, currentRoleName)
                );
            }
        }
        
        // AgentExecutionContext constructor throws on maxIterations <= 0
        int maxIterations = Math.max(1, targetRole.definition().maxIterations());

        // NOTE: Inner sessions bypass SessionRegistry intentionally.
        // StatefulAgentStore.save() must not require a session_registry entry.
        // Verified: save() is keyed only on (sessionId, agentRole) — no FK constraint.
        String innerSessionId = outerSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL + agentName + ":" + System.currentTimeMillis();

        log.infof("[DELEGATE] coordinator → agent=%s goal='%s' session=%s",
                  agentName, goal, outerSessionId);

        // Resolve inner autonomy level (§Invariant-Sub-Governance)
        AutonomyLevel outerLevel = WorkspaceContext.requireAutonomyOrSupervised();
        AutonomyLevel innerLevel = switch (outerLevel) {
            case READONLY -> AutonomyLevel.READONLY;
            default -> AutonomyLevel.FULL; // SUPERVISED/FULL -> FULL to avoid unresumable synchronous pauses
        };

        // CRITICAL: Rebind WorkspaceContext with resolved level for inner loop.
        // ToolInvocationGuard.evaluate() line 120 reads WorkspaceContext.autonomy()
        // — if SUPERVISED, all inner tool calls pause for HITL approval, creating
        // unresumable checkpoint corruption. FULL permits all operations.
        //
        // NOTE ON COMPACTION:
        // The inner loop does NOT go through the MessagePipeline (AgentDispatchHandler,
        // CompactionHandler, etc.) — it enters AgentLoop.runWithRole() directly.
        // Compaction is NOT triggered because the inner history bypasses the pipeline.
        // The inner loop DOES persist a checkpoint to DB under (innerSessionId, role).
        // This is intentional — it enables crash recovery if delegation is interrupted.
        // Do NOT "fix" this by routing through the pipeline.
        AgentResult result;
        try {
            result = WorkspaceContext.run(
                innerSessionId,
                userId,
                chatId,
                innerLevel,
                targetRole.toScopedName(),
                () -> agentLoop.get().run(
                    innerSessionId,
                    targetRole.toScopedName(),
                    goal,
                    reasoningEngine,
                    maxIterations
                )
            );
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        } catch (BudgetExceededException e) {
            throw e; // MUST propagate to top level
        } catch (Exception e) {
            return ToolResponse.failure(500,
                delegationFailed(targetRole.toScopedName(), e.getMessage()));
        }

        // 3. Handle specific terminal subtypes BEFORE formatting
        if (result instanceof AgentResult.AwaitingApproval aa) {
            return ToolResponse.failure(409,
                delegationPaused(targetRole.toScopedName(), aa.toolName(), aa.sessionId()));
        }
        if (result instanceof AgentResult.LoopExceeded) {
            return ToolResponse.failure(500,
                delegationExceeded(targetRole.toScopedName(), maxIterations));
        }

        // 4. Success path uses standard formatter
        String formatted = AgentResultFormatter.format(result);
        log.infof("[DELEGATE] agent=%s completed for session=%s",
                  targetRole.toScopedName(), outerSessionId);
        return ToolResponse.success(formatted);
    }

    private String getDelegatableAgentList() {
        return AgentRoleEnum.values().stream()
                .filter(role -> !role.equals(AgentRoleEnum.COORDINATOR))
                .flatMap(role -> role.definition().aliases().stream())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private boolean isSelfDelegation(String normalized, String currentRoleName) {
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        return current.id().equals(normalized) ||
               current.definition().aliases().contains(normalized);
    }
}
