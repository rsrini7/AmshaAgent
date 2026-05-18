package com.arcclaw.core.tools;

import static com.arcclaw.core.message.UserMessages.*;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.persistence.api.CheckpointCrudPort;
import com.arcclaw.persistence.api.CheckpointDto;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Allows the coordinator to delegate specialized tasks (like coding or debugging)
 * to domain expert sub-agents (e.g., Shiva, Brahma).
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "delegate_to_agent",
    description = "Delegate a task to a domain expert sub-agent",
    defaultRoles = {"architect", "coordinator", "researcher", "coder", "debugger", "synthesizer"}
)
public class DelegateToAgentTool implements AgentTool {

    private static final Logger log = Logger.getLogger(DelegateToAgentTool.class);

    @Inject ReasoningEngine reasoningEngine;
    @Inject Instance<AgentLoop> agentLoop;
    @Inject CheckpointCrudPort checkpointCrud;

    @ConfigProperty(name = "arcclaw.delegation.max-depth", defaultValue = "3")
    int maxDelegationDepth = 3;

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
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addProperty("agent", JsonStringSchema.builder()
                .description("The name of the specialist sub-agent (e.g., shiva, brahma, saraswati, brihaspati, dattatreya, coordinator).")
                .build())
            .addProperty("goal", JsonStringSchema.builder()
                .description("The self-contained task description for the sub-agent.")
                .build())
            .required("agent", "goal")
            .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String currentRoleName = ctx.agentRole();
        String outerSessionId = ctx.sessionId();
        String userId = ctx.userId();
        String chatId = ctx.chatId();

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

        int depth = outerSessionId.split(Pattern.quote(AgentSentinels.DELEGATE_SESSION_SENTINEL)).length - 1;
        if (depth >= maxDelegationDepth) {
            log.warnf("[DELEGATE] Hard block: delegation depth limit (%d) reached for session %s", maxDelegationDepth, outerSessionId);
            return ToolResponse.failure(400, String.format(
                "Recursion limit exceeded: Maximum delegation depth of %d reached. " +
                "You cannot delegate further to prevent infinite loops.", maxDelegationDepth));
        }

        if (isSelfDelegation(normalized, currentRoleName)) {
            return ToolResponse.failure(400, delegationSelfGuard(normalized, delegatableList));
        }

        if (isCoderDelegatingToResearcher(normalized, currentRoleName)) {
            return ToolResponse.failure(400,
                "STOP. Do NOT delegate. You are the Coder — write the code NOW and call sandbox_exec with it. " +
                "Use what you know about the topic. If unsure, write your best attempt. sandbox_exec will run it.");
        }

        if (isCombinedGoalToResearcher(normalized, currentRoleName, goal)) {
            log.infof("[REDIRECT] %s → %s blocked (combined goal), auto-redirecting to brahma", currentRoleName, normalized);
            normalized = AgentRoleEnum.CODER.id();
            agentName = normalized;
        }

        if (!AgentRoleEnum.isKnownRole(agentName)) {
            return ToolResponse.failure(400, delegationUnknownAgent(agentName, delegatableList));
        }

        AgentRoleEnum targetRole = AgentRoleEnum.fromString(agentName);

        AgentResult result;
        try {
            result = runSubAgent(ctx, outerSessionId, agentName, goal, targetRole, userId, chatId);
        } catch (BudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            return handleRunSubAgentException(targetRole, e);
        }

        return handleSubAgentResult(result, targetRole, outerSessionId);
    }

    private AgentResult runSubAgent(ToolExecutionContext ctx, String outerSessionId, String agentName, String goal, AgentRoleEnum targetRole, String userId, String chatId) throws Exception {
        int maxIterations = Math.max(1, targetRole.definition().maxIterations());
        String innerSessionId = outerSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL + agentName + ":" + System.currentTimeMillis();

        log.infof("[DELEGATE] coordinator → agent=%s goal='%s' session=%s", agentName, goal, outerSessionId);

        AutonomyLevel outerLevel = ctx.autonomyLevel();
        AutonomyLevel innerLevel = switch (outerLevel) {
            case READONLY -> AutonomyLevel.READONLY;
            default -> AutonomyLevel.FULL;
        };

        String channelType = ctx.channelType();
        String platformId = ctx.channelPlatformId();
        AgentResult result = WorkspaceContext.run(
            innerSessionId, userId, chatId, innerLevel, targetRole.toScopedName(),
            channelType, platformId,
            () -> agentLoop.get().run(innerSessionId, targetRole.toScopedName(), goal, reasoningEngine, maxIterations)
        );

        // Link sub-agent checkpoint to parent
        checkpointCrud.findBySessionAndRole(outerSessionId, ctx.agentRole()).ifPresent(parent -> {
            checkpointCrud.findBySessionAndRole(innerSessionId, targetRole.toScopedName()).ifPresent(child -> {
                checkpointCrud.save(new CheckpointDto(
                    child.id(), child.sessionId(), child.agentRole(), child.status(),
                    child.autonomyLevel(), child.stateJson(), child.channelPlatformId(),
                    child.channelType(), child.originalGoal(), child.failedAt(), child.completedAt(),
                    child.failReason(), child.pendingToolName(), child.batchToolDecisions(),
                    child.pendingToolParametersJson(), child.preExecutedResults(),
                    child.maxIterations(), child.iterationsUsed(), child.retryAfterMs(),
                    child.systemNote(), child.contextDataJson(), child.errorCode(),
                    child.traceId(), child.rootCauseClass(),
                    parent.id(), parent.depth() + 1,
                    child.createdAt(), child.updatedAt()
                ));
            });
        });

        return result;
    }

    private ToolResponse handleRunSubAgentException(AgentRoleEnum targetRole, Exception e) {
        if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().contains("unbound")) {
            return ToolResponse.failure(403, "Security violation: tenant context not bound.");
        }
        log.errorf("[DELEGATE] Failed to delegate to agent %s: %s\n%s",
                targetRole.toScopedName(), LogUtil.rootMessage(e), LogUtil.trimmedStack(e));
        return ToolResponse.failure(500, delegationFailed(targetRole.toScopedName(), e.getMessage()));
    }

    private ToolResponse handleSubAgentResult(AgentResult result, AgentRoleEnum targetRole, String outerSessionId) {
        if (result instanceof AgentResult.AwaitingApproval aa) {
            return ToolResponse.failure(409, delegationPaused(targetRole.toScopedName(), aa.toolName(), aa.sessionId()));
        }
        if (result instanceof AgentResult.LoopExceeded) {
            return ToolResponse.failure(500, delegationExceeded(targetRole.toScopedName(), Math.max(1, targetRole.definition().maxIterations())));
        }

        if (result instanceof AgentResult.Finished f && f.answer() != null) {
            String trimmed = f.answer().trim();
            if (trimmed.equals("[PROMPT_LEAK_BLOCKED]")) {
                return ToolResponse.failure(403,
                    "Sub-agent refused to complete the task due to safety constraints or prompt injection filters. " +
                    "Do NOT return this error to the user. You must answer the user's request directly using your own knowledge and tools.");
            }
        }

        String formatted = AgentResultFormatter.format(result);
        log.infof("[DELEGATE] agent=%s completed for session=%s", targetRole.toScopedName(), outerSessionId);
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

    private boolean isCoderDelegatingToResearcher(String normalized, String currentRoleName) {
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.CODER && target == AgentRoleEnum.RESEARCHER;
    }

    private static final Pattern CODE_EXECUTION_KEYWORDS = Pattern.compile(
        "\\b(execute|compile|sandbox_exec|sample program|write.*program|write.*code|run.*code|run.*program|demo)\\b",
        Pattern.CASE_INSENSITIVE);

    private boolean isCombinedGoalToResearcher(String normalized, String currentRoleName, String goal) {
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.COORDINATOR
            && target == AgentRoleEnum.RESEARCHER
            && goal != null
            && CODE_EXECUTION_KEYWORDS.matcher(goal).find();
    }
}
