package com.arcclaw.core.tools;

import static com.arcclaw.core.message.UserMessages.*;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.observability.AgentOTelConstants;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.util.AgentGuards;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.persistence.api.CheckpointCrudPort;
import com.arcclaw.persistence.api.CheckpointDto;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

/**
 * Allows the coordinator to delegate specialized tasks (like coding or debugging)
 * to domain expert sub-agents (e.g., Shiva, Brahma).
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "garuda",
        description = "Garuda - The divine vahana of Vishnu. Dispatch tasks to domain expert sub-agents.",
        defaultRoles = {
            "architect", "coordinator", "researcher", "coder", "debugger", "synthesizer",
            "optimizer", "sentinel", "healer", "protector", "purifier", "nurturer"
        })
public class DelegateToAgentTool implements AgentTool {

    private static final Logger log = Logger.getLogger(DelegateToAgentTool.class);
    private static final String PARAM_AGENT = "agent";
    private static final String PARAM_GOAL = "goal";

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    Instance<AgentLoop> agentLoop;

    @Inject
    CheckpointCrudPort checkpointCrud;

    @Inject
    AuditService auditService;

    @Inject
    AgentOtelContext otelContext;

    @ConfigProperty(name = "arcclaw.delegation.max-depth", defaultValue = "3")
    int maxDelegationDepth = 3;

    @Override
    public String name() {
        return ToolCapability.DELEGATE_TO_AGENT.toolName(); // "garuda"
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
                .addProperty(
                        PARAM_AGENT,
                        JsonStringSchema.builder()
                                .description(
                                        "The name of the specialist sub-agent (e.g., shiva, brahma, saraswati, brihaspati, dattatreya, ganesha, kartikeya, lakshmi, durga, kali, parvati, coordinator).")
                                .build())
                .addProperty(
                        PARAM_GOAL,
                        JsonStringSchema.builder()
                                .description("The self-contained task description for the sub-agent.")
                                .build())
                .required(PARAM_AGENT, PARAM_GOAL)
                .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        // Guard against shutdown race — CDI container may be dead if Quarkus shut down
        // while a session was still running (known pattern: shutdown-race-condition-pattern).
        if (AgentGuards.isShuttingDown()) {
            log.warnf("[DELEGATE] CDI container shutting down, aborting delegation");
            return ToolResponse.failure(503, "System is shutting down. Delegation aborted.");
        }

        String currentRoleName = ctx.agentRole();
        String outerSessionId = ctx.sessionId();
        String userId = ctx.userId();
        String chatId = ctx.chatId();

        Object agentObj = params.get(PARAM_AGENT);
        Object goalObj = params.get(PARAM_GOAL);
        String delegatableList = getDelegatableAgentList();

        if (agentObj == null || String.valueOf(agentObj).isBlank()) {
            return ToolResponse.failure(400, delegationAgentRequired(delegatableList));
        }
        if (goalObj == null || String.valueOf(goalObj).isBlank()) {
            return ToolResponse.failure(400, DELEGATION_GOAL_REQUIRED);
        }

        String agentName = stripSurroundingQuotes(String.valueOf(agentObj));
        String goal = String.valueOf(goalObj);

        int depth = outerSessionId.split(Pattern.quote(AgentSentinels.DELEGATE_SESSION_SENTINEL)).length - 1;
        if (depth >= maxDelegationDepth) {
            log.warnf(
                    "[DELEGATE] Hard block: delegation depth limit (%d) reached for session %s",
                    maxDelegationDepth, outerSessionId);
            // Return success (not failure) to prevent the LLM from retrying.
            // A failure response causes the LLM to loop: retry delegate → blocked → retry...
            // A success response lets the LLM FINISH naturally with the status as its answer.
            return ToolResponse.success(String.format(
                    "Delegation blocked: maximum depth of %d reached. You CANNOT delegate further. "
                            + "You MUST FINISH now with a plain text summary of what was accomplished "
                            + "and what remains. Do NOT attempt to use garuda again.",
                    maxDelegationDepth));
        }

        String normalized;
        try {
            normalized = normalizeAgent(agentName, goal, currentRoleName, delegatableList);
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(400, e.getMessage());
        }
        if (normalized == null)
            return ToolResponse.success(
                    "You tried to delegate a task to yourself. Proceed with your own tools directly.");
        agentName = normalized;

        AgentRoleEnum targetRole = AgentRoleEnum.fromString(agentName);

        auditService.record(
                outerSessionId,
                userId,
                ctx.agentRole(),
                SecurityEventType.DELEGATION_DISPATCHED,
                "target=" + agentName + " depth=" + depth + " goalLen=" + goal.length());

        // Capture parent OTel context before spawning sub-agent
        Context parentOtelContext = Context.current();
        log.debugf(
                "GARUDA: Dispatching to agent=%s depth=%d parentTraceId=%s",
                agentName, depth, parentOtelContext.toString());

        AgentResult result;
        Span delegationSpan = otelContext.childSpan(outerSessionId, AgentOTelConstants.SPAN_DELEGATION_DISPATCH);
        try {
            if (delegationSpan != null) {
                delegationSpan.setAttribute(AgentOTelConstants.ATTR_DELEGATION_TARGET, agentName);
                delegationSpan.setAttribute(AgentOTelConstants.ATTR_DELEGATION_DEPTH, (long) depth);
                delegationSpan.setAttribute(AgentOTelConstants.ATTR_DELEGATION_GOAL_LEN, (long) goal.length());
            }
            result = runSubAgent(ctx, outerSessionId, agentName, goal, targetRole, userId, chatId);
            if (delegationSpan != null) delegationSpan.setStatus(StatusCode.OK);
        } catch (BudgetExceededException e) {
            if (delegationSpan != null) {
                delegationSpan.setStatus(StatusCode.ERROR, e.getMessage());
                delegationSpan.recordException(e);
                delegationSpan.end();
            }
            throw e;
        } catch (Exception e) {
            if (delegationSpan != null) {
                delegationSpan.setStatus(StatusCode.ERROR, e.getMessage());
                delegationSpan.recordException(e);
                delegationSpan.end();
            }
            return handleRunSubAgentException(targetRole, e);
        } finally {
            if (delegationSpan != null) delegationSpan.end();
        }

        return handleSubAgentResult(result, targetRole, outerSessionId);
    }

    private AgentResult runSubAgent(
            ToolExecutionContext ctx,
            String outerSessionId,
            String agentName,
            String goal,
            AgentRoleEnum targetRole,
            String userId,
            String chatId)
            throws Exception {
        int maxIterations = Math.max(1, targetRole.definition().maxIterations());
        String innerSessionId = outerSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL + agentName + ":"
                + System.currentTimeMillis();

        log.infof("[DELEGATE] coordinator → agent=%s goal='%s' session=%s", agentName, goal, outerSessionId);

        AutonomyLevel outerLevel = ctx.autonomyLevel();
        AutonomyLevel innerLevel =
                switch (outerLevel) {
                    case READONLY -> AutonomyLevel.READONLY;
                    default -> outerLevel;
                };

        String channelType = ctx.channelType();
        String platformId = ctx.channelPlatformId();
        AgentResult result = WorkspaceContext.run(
                innerSessionId,
                userId,
                chatId,
                innerLevel,
                targetRole.toScopedName(),
                channelType,
                platformId,
                () -> agentLoop
                        .get()
                        .run(innerSessionId, targetRole.toScopedName(), goal, reasoningEngine, maxIterations));

        // Link sub-agent checkpoint to parent
        checkpointCrud.findBySessionAndRole(outerSessionId, ctx.agentRole()).ifPresent(parent -> {
            checkpointCrud
                    .findBySessionAndRole(innerSessionId, targetRole.toScopedName())
                    .ifPresent(child -> {
                        checkpointCrud.save(new CheckpointDto(
                                child.id(),
                                child.sessionId(),
                                child.agentRole(),
                                child.status(),
                                child.autonomyLevel(),
                                child.stateJson(),
                                child.channelPlatformId(),
                                child.channelType(),
                                child.originalGoal(),
                                child.failedAt(),
                                child.completedAt(),
                                child.failReason(),
                                child.pendingToolName(),
                                child.batchToolDecisions(),
                                child.pendingToolParametersJson(),
                                child.preExecutedResults(),
                                child.maxIterations(),
                                child.iterationsUsed(),
                                child.retryAfterMs(),
                                child.systemNote(),
                                child.contextDataJson(),
                                child.errorCode(),
                                child.traceId(),
                                child.rootCauseClass(),
                                parent.id(),
                                parent.depth() + 1,
                                child.createdAt(),
                                child.updatedAt()));
                    });
        });

        return result;
    }

    private ToolResponse handleRunSubAgentException(AgentRoleEnum targetRole, Exception e) {
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

    private ToolResponse handleSubAgentResult(AgentResult result, AgentRoleEnum targetRole, String outerSessionId) {
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

    private static String stripSurroundingQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * Validates and normalizes the target agent name. Returns null if self-delegation
     * after redirect (caller should tell LLM to proceed directly).
     */
    private String normalizeAgent(String agentName, String goal, String currentRoleName, String delegatableList) {
        String normalized = agentName.trim().toLowerCase();

        if (isSelfDelegation(normalized, currentRoleName)) {
            log.infof("[REDIRECT] %s tried to delegate to itself — auto-redirecting to coordinator", currentRoleName);
            normalized = AgentRoleEnum.COORDINATOR.id();
            agentName = normalized;
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
            agentName = normalized;
        }

        if (!AgentRoleEnum.isKnownRole(agentName)) {
            throw new IllegalArgumentException(delegationUnknownAgent(agentName, delegatableList));
        }
        return normalized;
    }

    private boolean isSelfDelegation(String normalized, String currentRoleName) {
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        return current.id().equals(normalized) || current.definition().aliases().contains(normalized);
    }

    private boolean isCoderDelegatingToResearcher(String normalized, String currentRoleName) {
        if (!AgentRoleEnum.isKnownRole(normalized)) return false;
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.CODER && target == AgentRoleEnum.RESEARCHER;
    }

    private boolean isCombinedGoalToResearcher(String normalized, String currentRoleName, String goal) {
        if (!AgentRoleEnum.isKnownRole(normalized)) return false;
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.COORDINATOR
                && target == AgentRoleEnum.RESEARCHER
                && goal != null
                && AgentSentinels.CODE_EXECUTION_KEYWORDS.matcher(goal).find();
    }
}
