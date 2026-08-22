package com.amsha.core.agent.loop;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.ReasoningStepSupport;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.tool.DynamicToolAdmission;
import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.InterceptorPriority;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Subclass;

/**
 * Validates tool calls before invocation.
 */
@ApplicationScoped
public class ToolInvocationGuard {
    private static final Logger log = Logger.getLogger(ToolInvocationGuard.class);

    @Inject
    public RoleRegistry roleRegistry;

    @Inject
    public ObjectMapper objectMapper;

    @Inject
    public ToolRegistry toolRegistry;

    @Inject
    public Instance<DynamicToolAdmissionLookup> dynamicToolAdmissionLookup;

    @Inject
    public Instance<ToolGuardInterceptor> interceptors;

    @Inject
    ToolRiskShadowEvaluationService toolRiskShadowEvaluation;

    @Inject
    PostPolicyToolRiskDispatcher postPolicyToolRiskDispatcher;

    private static final int MAX_DEDUP_SESSIONS = 1000;
    private final Map<String, Map<String, String>> sessionDedupeCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> sessionOrder = new ConcurrentLinkedDeque<>();

    private List<ToolGuardInterceptor> sortedInterceptors;

    @PostConstruct
    public void init() {
        log.info("Initializing ToolInvocationGuard with discovered interceptors...");
        if (interceptors == null) {
            this.sortedInterceptors = List.of();
            return;
        }
        this.sortedInterceptors = interceptors.stream()
                .sorted(Comparator.comparingInt(this::getPriority))
                .toList();
    }

    private int getPriority(ToolGuardInterceptor interceptor) {
        Class<?> clazz = interceptor.getClass();
        if (interceptor instanceof Subclass) {
            clazz = clazz.getSuperclass();
        }
        InterceptorPriority priority = clazz.getAnnotation(InterceptorPriority.class);
        return priority != null ? priority.value() : 1000;
    }

    public sealed interface GuardDecision
            permits GuardDecision.Allow,
                    GuardDecision.Deduplicate,
                    GuardDecision.PauseForApproval,
                    GuardDecision.Blocked {
        record Allow() implements GuardDecision {}

        record Deduplicate(String cachedObservation) implements GuardDecision {}

        record PauseForApproval(ApprovalFrame frame) implements GuardDecision {}

        record Blocked(String observation) implements GuardDecision {}
    }

    public GuardDecision evaluate(
            String sessionId,
            String agentRole,
            AutonomyLevel level,
            String toolName,
            Map<String, Object> params,
            List<ReasoningStep> history,
            String goal,
            List<CompiledSkillEntry> skillSnapshot) {

        for (ToolGuardInterceptor interceptor : sortedInterceptors) {
            if (interceptor.handledToolNames().isEmpty()
                    || interceptor.handledToolNames().contains(toolName)) {
                Optional<GuardDecision> decision = interceptor.evaluate(
                        sessionId, agentRole, level, toolName, params, history, goal, skillSnapshot);
                if (decision.isPresent()) {
                    GuardDecision interceptorDecision = decision.get();
                    if (interceptorDecision instanceof GuardDecision.Allow) continue;
                    return interceptorDecision;
                }
            }
        }

        var toolOpt = toolRegistry.findTool(toolName);
        if (toolOpt.isPresent()) {
            String validationError = toolOpt.get().validateParameters(params);
            if (validationError != null && !validationError.isBlank()) {
                return new GuardDecision.Blocked(validationError);
            }
        }
        IdempotencyMode mode = toolOpt.map(AgentTool::idempotencyMode).orElse(IdempotencyMode.NON_IDEMPOTENT);

        if (mode != IdempotencyMode.NON_IDEMPOTENT) {
            GuardDecision dedupResult = checkDeduplication(sessionId, toolName, params, history);
            if (dedupResult != null) return dedupResult;
        }

        boolean isInternalTool = toolOpt.isPresent();
        final DynamicToolAdmission dynamicAdmission;
        if (!isInternalTool) {
            Optional<DynamicToolAdmission> admissionOpt = resolveDynamicAdmission(toolName);
            if (admissionOpt.isEmpty()) {
                log.warnf("GUARD Unknown or unadmitted dynamic tool '%s' in session '%s'.", toolName, sessionId);
                return new GuardDecision.Blocked("Tool '" + toolName + "' is not recognized or permitted.");
            }
            dynamicAdmission = admissionOpt.get();
        } else {
            dynamicAdmission = null;
        }

        boolean isSchedulingTool = ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName);

        boolean staticRequiresApproval =
                toolOpt.map(tool -> tool.requiresManualApproval(params)).orElse(false);
        boolean dynamicRequiresApproval = dynamicAdmission != null && dynamicAdmission.requiresApproval();

        // Memory search tools are read-only and should be exempt from HITL approval
        // at both BACKGROUND and SUPERVISED levels. ingest_document is WRITE_MEMORY
        // and must pause consistently here before parallel execution starts.
        boolean isMemoryReadTool =
                ToolCapability.RECALL_MEMORY_SEARCH.toolName().equals(toolName)
                        || ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName().equals(toolName)
                        || ToolCapability.CORE_MEMORY_READ.toolName().equals(toolName);

        boolean isExempt = isSchedulingTool || (isMemoryReadTool && level != AutonomyLevel.READONLY);

        ToolOperation op = toolOpt.map(AgentTool::requiredOperation)
                .orElseGet(() -> Objects.requireNonNull(dynamicAdmission).requiredOperation());
        boolean alwaysRequiresHitl = op == ToolOperation.EXECUTE_SHELL || op == ToolOperation.NETWORK_OUTBOUND;

        if (!isExempt
                && (alwaysRequiresHitl
                        || dynamicRequiresApproval
                        || ((roleRegistry.requiresApprovalForTools(agentRole) || staticRequiresApproval)
                                && !AutonomyLevel.FULL.equals(level)))) {
            // HITL is the deliberate stronger control for approved operations. The automated
            // post-policy classifier is not re-run after this pause/resume boundary.
            observeToolRiskForCollection(sessionId, agentRole, level, toolName, params, goal);
            ApprovalFrame frame = new ApprovalFrame(toolName, params, op.riskLevel());
            return new GuardDecision.PauseForApproval(frame);
        }
        GuardDecision postPolicyDecision =
                applyPostPolicyToolRisk(agentRole, level, toolName, params, history, goal, op);
        if (postPolicyDecision instanceof GuardDecision.Allow) {
            observeProvisionalAllow(sessionId, agentRole, level, toolName, params, goal);
        }
        return postPolicyDecision;
    }

    /**
     * Resolves dynamic admission fail-closed: CDI absence, ambiguity, lookup errors, and
     * empty/invalid metadata all yield empty Optional (caller Blocks).
     */
    private Optional<DynamicToolAdmission> resolveDynamicAdmission(String toolName) {
        if (dynamicToolAdmissionLookup == null
                || dynamicToolAdmissionLookup.isUnsatisfied()
                || dynamicToolAdmissionLookup.isAmbiguous()) {
            return Optional.empty();
        }
        try {
            Optional<DynamicToolAdmission> admission =
                    dynamicToolAdmissionLookup.get().findAdmission(toolName);
            if (admission == null || admission.isEmpty()) {
                return Optional.empty();
            }
            DynamicToolAdmission metadata = admission.get();
            if (!toolName.equals(metadata.toolName())) {
                log.warnf("GUARD Dynamic admission name mismatch for '%s'.", toolName);
                return Optional.empty();
            }
            return Optional.of(metadata);
        } catch (RuntimeException | LinkageError e) {
            log.warnf(
                    "GUARD Dynamic admission lookup failed for '%s': %s",
                    toolName, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private GuardDecision applyPostPolicyToolRisk(
            String agentRole,
            AutonomyLevel level,
            String toolName,
            Map<String, Object> params,
            List<ReasoningStep> history,
            String goal,
            ToolOperation operation) {
        if (postPolicyToolRiskDispatcher == null) return new GuardDecision.Allow();
        try {
            ClassifierResult<ToolRiskAction> result = postPolicyToolRiskDispatcher.evaluate(new PostPolicyToolContext(
                    agentRole,
                    level,
                    toolName,
                    params == null ? Map.of() : params,
                    goal,
                    history != null && history.stream().anyMatch(ReasoningStepSupport::isToolCallStep)));
            return switch (result) {
                case ClassifierResult.Opinion<ToolRiskAction> opinion ->
                    switch (opinion.value()) {
                        case BLOCK -> new GuardDecision.Blocked("Tool call blocked by the post-policy risk gate.");
                        case REQUIRE_APPROVAL ->
                            new GuardDecision.PauseForApproval(new ApprovalFrame(
                                    toolName, params == null ? Map.of() : params, operation.riskLevel()));
                    };
                case ClassifierResult.NoOpinion<ToolRiskAction> _ -> new GuardDecision.Allow();
            };
        } catch (RuntimeException | LinkageError _) {
            return new GuardDecision.Allow();
        }
    }

    private void observeProvisionalAllow(
            String sessionId,
            String agentRole,
            AutonomyLevel level,
            String toolName,
            Map<String, Object> params,
            String goal) {
        observeToolRisk(sessionId, agentRole, level, toolName, params, goal, true);
    }

    private void observeToolRiskForCollection(
            String sessionId,
            String agentRole,
            AutonomyLevel level,
            String toolName,
            Map<String, Object> params,
            String goal) {
        observeToolRisk(sessionId, agentRole, level, toolName, params, goal, false);
    }

    private void observeToolRisk(
            String sessionId,
            String agentRole,
            AutonomyLevel level,
            String toolName,
            Map<String, Object> params,
            String goal,
            boolean includeShadow) {
        try {
            if (toolRiskShadowEvaluation == null) {
                return;
            }
            ToolRiskShadowContext context =
                    new ToolRiskShadowContext(agentRole, level, toolName, params == null ? Map.of() : params, goal);
            if (includeShadow) {
                toolRiskShadowEvaluation.observeProvisionalAllow(sessionId, context);
            } else {
                toolRiskShadowEvaluation.observeForDataCollection(sessionId, context);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A shadow-only observer must never alter a deterministic guard decision.
        }
    }

    private GuardDecision checkDeduplication(
            String sessionId, String toolName, Map<String, Object> params, List<ReasoningStep> history) {
        if (sessionDedupeCache.size() >= MAX_DEDUP_SESSIONS && !sessionDedupeCache.containsKey(sessionId)) {
            String evictKey = sessionOrder.pollFirst();
            if (evictKey != null) {
                sessionDedupeCache.remove(evictKey);
            }
        }
        Map<String, String> dedupeIndex = sessionDedupeCache.computeIfAbsent(sessionId, k -> {
            Map<String, String> index = new ConcurrentHashMap<>();
            sessionOrder.addLast(k);
            for (ReasoningStep past : List.copyOf(history)) {
                if (past.observation() != null && !past.observation().isBlank()) {
                    index.put(buildDedupeKey(past.action(), past.actionInput()), past.observation());
                }
            }
            return index;
        });

        String currentKey = buildDedupeKey(toolName, params);
        if (dedupeIndex.containsKey(currentKey)) {
            return new GuardDecision.Deduplicate(dedupeIndex.get(currentKey) + "\n\n[DEDUP]");
        }

        // Fallback: check history directly if not in index
        int scanLimit = Math.min(history.size(), 10);
        for (int i = history.size() - 1; i >= history.size() - scanLimit; i--) {
            ReasoningStep past = history.get(i);
            if (past.observation() != null
                    && !past.observation().isBlank()
                    && toolName.equals(past.action())
                    && Objects.equals(params, past.actionInput())) {
                log.infof("GUARD: Caught duplicate tool call for '%s' in history for session %s", toolName, sessionId);
                return new GuardDecision.Deduplicate(past.observation() + "\n\n[DEDUP-HISTORY]");
            }
        }
        return null;
    }

    public void recordToolCall(String sessionId, String toolName, Map<String, Object> params, String observation) {
        if (observation == null || observation.isBlank()) return;
        Map<String, String> index = sessionDedupeCache.computeIfAbsent(sessionId, k -> {
            sessionOrder.addLast(k);
            return new ConcurrentHashMap<>();
        });
        index.put(buildDedupeKey(toolName, params), observation);
    }

    public void clearSession(String sessionId) {
        sessionDedupeCache.remove(sessionId);
        sessionOrder.remove(sessionId);
    }

    private String buildDedupeKey(String toolName, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return toolName + ":empty";
        try {
            TreeMap<String, Object> sorted = new TreeMap<>(params);
            return toolName + ":" + objectMapper.writeValueAsString(sorted);
        } catch (Exception _) {
            return toolName + ":err:" + Objects.hash(params);
        }
    }
}
