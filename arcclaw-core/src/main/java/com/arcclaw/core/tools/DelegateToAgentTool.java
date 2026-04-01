package com.arcclaw.core.tools;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
        // Dispatching a goal is read-only at the coordinator level.
        // The inner agent's own tool calls are gated by their specific Operations.
        return ToolOperation.READ_FS;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String agentName = (String) params.get("agent");
        String goal = (String) params.get("goal");

        if (agentName == null || agentName.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'agent' is required.");
        }
        if (goal == null || goal.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'goal' is required.");
        }

        String normalized = agentName.trim().toLowerCase();

        // 1. Self-delegation guard
        if ("coordinator".equals(normalized) || "vishnu".equals(normalized)) {
            return ToolResponse.failure(400,
                "Cannot delegate to yourself (coordinator/vishnu). " +
                "Route to a specialist: brahma, shiva, saraswati, brihaspati, dattatreya.");
        }

        // 2. Role validation
        if (!AgentRoleEnum.isKnownRole(agentName)) {
            return ToolResponse.failure(400,
                "Unknown agent '" + agentName + "'. Valid agents: " +
                "brahma, shiva, saraswati, brihaspati, dattatreya.");
        }

        AgentRoleEnum targetRole = AgentRoleEnum.fromString(agentName);

        // 2a. Governance Guard: Capability Escalation Check (§4.10)
        String currentRoleName = WorkspaceContext.agentRole();
        AgentRoleEnum currentRole = AgentRoleEnum.fromString(currentRoleName);
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
                String.format("Delegation to %s rejected: target role has capabilities [%s] " +
                              "not permitted for delegating role [%s]. " +
                              "Use a role with sufficient capabilities or request coordinator to delegate.",
                              targetRole.toScopedName(), unauthorizedList, currentRoleName)
            );
        }
        
        // AgentExecutionContext constructor throws on maxIterations <= 0
        int maxIterations = Math.max(1, targetRole.definition().maxIterations());
        String outerSessionId = WorkspaceContext.requireSessionId();

        // NOTE: Inner sessions bypass SessionRegistry intentionally.
        // StatefulAgentStore.save() must not require a session_registry entry.
        // Verified: save() is keyed only on (sessionId, agentRole) — no FK constraint.
        String innerSessionId = outerSessionId + ":d:" + targetRole.toScopedName()
                                               + ":" + UUID.randomUUID().toString().substring(0, 8);

        log.infof("[DELEGATE] coordinator → agent=%s goal='%s' session=%s",
                  targetRole.toScopedName(), goal, outerSessionId);

        // CRITICAL: Rebind WorkspaceContext with AutonomyLevel.FULL for inner loop.
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
                WorkspaceContext.userId(),
                WorkspaceContext.chatId(),
                AutonomyLevel.FULL,
                targetRole.toScopedName(),
                () -> agentLoop.get().run(
                    innerSessionId,
                    targetRole.toScopedName(),
                    goal,
                    reasoningEngine,
                    maxIterations
                )
            );
        } catch (BudgetExceededException e) {
            throw e; // MUST propagate to top level
        } catch (Exception e) {
            return ToolResponse.failure(500,
                "Delegation to '" + targetRole.toScopedName() + "' failed: " + e.getMessage());
        }

        // 3. Handle specific terminal subtypes BEFORE formatting
        if (result instanceof AgentResult.AwaitingApproval aa) {
            return ToolResponse.failure(409,
                "Agent '" + targetRole.toScopedName() + "' paused for human approval " +
                "of tool '" + aa.toolName() + "'. " +
                "Delegation cannot complete until the inner session is approved. " +
                "Session: " + aa.sessionId());
        }
        if (result instanceof AgentResult.LoopExceeded) {
            return ToolResponse.failure(500,
                "Agent '" + targetRole.toScopedName() + "' exceeded its iteration budget (" +
                maxIterations + " iterations). Try simplifying the goal.");
        }

        // 4. Success path uses standard formatter
        String formatted = AgentResultFormatter.format(result);
        log.infof("[DELEGATE] agent=%s completed for session=%s",
                  targetRole.toScopedName(), outerSessionId);
        return ToolResponse.success(formatted);
    }
}
