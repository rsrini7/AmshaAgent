package com.amsha.core.tools;

import static com.amsha.core.message.UserMessages.*;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.GroupAnalyticsService;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.util.AgentGuards;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

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
    private static final String PARAM_MODE = "mode";

    /** Delegation mode: single agent handles alone. */
    static final String MODE_SOLO = "solo";

    /** Delegation mode: group lead consults family members if task spans multiple domains. */
    static final String MODE_GROUP_CONSULT = "group_consult";

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

    @Inject
    GroupConsultationService groupConsultationService;

    @Inject
    GroupConsultationEngine groupConsultationEngine;

    @Inject
    TaskClassifierService taskClassifier;

    @Inject
    GroupAnalyticsService groupAnalytics;

    @ConfigProperty(name = "amsha.delegation.max-depth", defaultValue = "3")
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
                .addProperty(
                        PARAM_MODE,
                        JsonStringSchema.builder()
                                .description("Delegation mode. 'solo' (default): delegate to single agent. "
                                        + "'group_consult': delegate to group lead who will consult family members "
                                        + "if the task spans multiple domains within their realm.")
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

        // Read delegation mode (default: solo)
        Object modeObj = params.get(PARAM_MODE);
        String mode = (modeObj != null && !String.valueOf(modeObj).isBlank())
                ? String.valueOf(modeObj).trim().toLowerCase(Locale.ROOT)
                : MODE_SOLO;

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

        // Group consultation mode: enhance goal with family huddle prompt
        if (MODE_GROUP_CONSULT.equals(mode)) {
            String leadRoleId = targetRole.toScopedName();
            if (groupConsultationService.isGroupLead(leadRoleId)) {
                return executeGroupConsultation(
                        ctx, outerSessionId, leadRoleId, goal, targetRole, userId, chatId, depth);
            }
            // Not a group lead — fall through to normal delegation
            log.debugf("[DELEGATE] group_consult mode but %s is not a group lead — delegating normally", leadRoleId);
        }

        auditService.record(
                outerSessionId,
                userId,
                ctx.agentRole(),
                SecurityEventType.DELEGATION_DISPATCHED,
                "target=" + agentName + " depth=" + depth + " goalLen=" + goal.length() + " mode=" + mode);

        log.debugf("GARUDA: Dispatching to agent=%s depth=%d", agentName, depth);

        return executeNormalDelegation(ctx, outerSessionId, agentName, goal, targetRole, userId, chatId, depth);
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
     * Executes a group consultation: spawns parallel sub-agents for family members,
     * collects their analyses, and passes the synthesized result to the group lead
     * for final synthesis.
     */
    private ToolResponse executeGroupConsultation(
            ToolExecutionContext ctx,
            String outerSessionId,
            String leadRoleId,
            String goal,
            AgentRoleEnum targetRole,
            String userId,
            String chatId,
            int depth) {

        var groupOpt = groupConsultationEngine.groupRegistry.findById(groupConsultationService
                .getGroup(leadRoleId)
                .map(g -> g.groupId())
                .orElse(""));
        if (groupOpt.isEmpty()) {
            log.warnf("[GROUP_CONSULT] No group found for %s — falling back to normal delegation", leadRoleId);
            goal = enhanceGoalWithGroupConsultation(leadRoleId, goal);
            return executeNormalDelegation(ctx, outerSessionId, leadRoleId, goal, targetRole, userId, chatId, depth);
        }

        var group = groupOpt.get();
        var subTasks = groupConsultationEngine.buildSubTasks(group, goal);

        if (subTasks.isEmpty()) {
            log.debugf("[GROUP_CONSULT] %s can self-handle — no family consultation needed", leadRoleId);
            return executeNormalDelegation(ctx, outerSessionId, leadRoleId, goal, targetRole, userId, chatId, depth);
        }

        log.infof("[GROUP_CONSULT] Spawning %d family members for %s", subTasks.size(), leadRoleId);

        // Build the sub-agent runner that delegates to the actual agent loop
        GroupConsultationEngine.SubAgentRunner runner = (memberId, memberGoal) -> {
            AgentRoleEnum memberRole = AgentRoleEnum.fromString(memberId);
            int maxIter = Math.max(1, memberRole.definition().maxIterations());
            String memberSession = outerSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL + memberId + ":consult:"
                    + System.currentTimeMillis();

            AgentResult result = WorkspaceContext.run(
                    memberSession,
                    userId,
                    chatId,
                    ctx.autonomyLevel(),
                    memberRole.toScopedName(),
                    ctx.channelType(),
                    ctx.channelPlatformId(),
                    () -> agentLoop
                            .get()
                            .run(memberSession, memberRole.toScopedName(), memberGoal, reasoningEngine, maxIter));

            return switch (result) {
                case AgentResult.Finished f -> f.answer();
                case AgentResult.Failed fail -> "[Failed: " + fail.reason() + "]";
                default -> "[No output]";
            };
        };

        // Run parallel consultation
        var consultResult = groupConsultationEngine.consultGroup(group.groupId(), goal, subTasks, runner);

        auditService.record(
                outerSessionId,
                userId,
                ctx.agentRole(),
                SecurityEventType.DELEGATION_DISPATCHED,
                String.format(
                        "group_consult group=%s consulted=%d failed=%d",
                        consultResult.groupId(), consultResult.membersConsulted(), consultResult.membersFailed()));

        // Record analytics
        var domains = taskClassifier.classify(goal);
        groupAnalytics.recordOutcome(
                consultResult.groupId(),
                domains.stream().map(d -> d.name().toLowerCase(Locale.ROOT)).toList(),
                consultResult.membersConsulted(),
                consultResult.membersFailed(),
                consultResult.hasUsefulResults());

        if (!consultResult.hasUsefulResults()) {
            log.warnf("[GROUP_CONSULT] No useful results from family — falling back to lead-only", leadRoleId);
            return executeNormalDelegation(ctx, outerSessionId, leadRoleId, goal, targetRole, userId, chatId, depth);
        }

        // Enhance the goal with family results and delegate to lead for synthesis
        String enhancedGoal = goal + "\n\n" + consultResult.toSynthesisPrompt();
        log.infof("[GROUP_CONSULT] Family consultation complete — delegating to %s for synthesis", leadRoleId);

        return executeNormalDelegation(
                ctx, outerSessionId, leadRoleId, enhancedGoal, targetRole, userId, chatId, depth);
    }

    /**
     * Executes a normal (non-group-consult) delegation to a single agent.
     */
    private ToolResponse executeNormalDelegation(
            ToolExecutionContext ctx,
            String outerSessionId,
            String agentName,
            String goal,
            AgentRoleEnum targetRole,
            String userId,
            String chatId,
            int depth) {

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

    /**
     * Enhances the delegation goal with group consultation prompt when mode=group_consult.
     * If the target agent is a group lead, triages the task and adds consultation guidance.
     * If the target is not a group lead, the goal is returned unchanged.
     */
    private String enhanceGoalWithGroupConsultation(String agentName, String goal) {
        var triage = groupConsultationService.triage(agentName, goal);

        if (triage.canSelfHandle()) {
            log.debugf("[GROUP_CONSULT] %s can self-handle: %s", agentName, triage.reason());
            return goal;
        }

        // Enhance the goal with consultation prompt
        String enhanced = goal
                + "\n\n" + triage.consultationPrompt()
                + "\n\nINSTRUCTIONS: You have been delegated with mode=" + MODE_GROUP_CONSULT + ". "
                + "Consult the family members listed above via garuda, then synthesize their "
                + "analyses into a unified response. Do NOT skip the consultation step.";

        log.infof(
                "[GROUP_CONSULT] Enhanced goal for %s: consulting %s — %s",
                agentName, triage.recommendedMembers(), triage.reason());
        return enhanced;
    }

    /**
     * Validates and normalizes the target agent name. Returns null if self-delegation
     * after redirect (caller should tell LLM to proceed directly).
     */
    private String normalizeAgent(String agentName, String goal, String currentRoleName, String delegatableList) {
        String normalized = agentName.trim().toLowerCase(Locale.ROOT);

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

    @SuppressWarnings("ReferenceEquality") // enum identity comparison is correct
    private boolean isCoderDelegatingToResearcher(String normalized, String currentRoleName) {
        if (!AgentRoleEnum.isKnownRole(normalized)) return false;
        AgentRoleEnum current = AgentRoleEnum.fromString(currentRoleName);
        AgentRoleEnum target = AgentRoleEnum.fromString(normalized);
        return current == AgentRoleEnum.CODER && target == AgentRoleEnum.RESEARCHER;
    }

    @SuppressWarnings("ReferenceEquality") // enum identity comparison is correct
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
