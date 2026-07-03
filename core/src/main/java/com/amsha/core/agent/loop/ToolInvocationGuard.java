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
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tool.InterceptorPriority;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolRegistry;
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
    public Instance<DynamicToolLookup> dynamicToolLookup;

    @Inject
    public Instance<ToolGuardInterceptor> interceptors;

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

        record PauseForApproval(ApprovalFrame frame) implements GuardDecision {
            public PauseForApproval(String reason, String toolName) {
                this(new ApprovalFrame(toolName, Map.of(), ApprovalFrame.RiskLevel.MEDIUM));
            }
        }

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
                    return decision.get();
                }
            }
        }

        var toolOpt = toolRegistry.findTool(toolName);
        IdempotencyMode mode = toolOpt.map(AgentTool::idempotencyMode).orElse(IdempotencyMode.NON_IDEMPOTENT);

        if (mode != IdempotencyMode.NON_IDEMPOTENT) {
            GuardDecision dedupResult = checkDeduplication(sessionId, toolName, params, history);
            if (dedupResult != null) return dedupResult;
        }

        boolean isInternalTool = toolOpt.isPresent();
        boolean isDynamicAllowed = !isInternalTool
                && dynamicToolLookup != null
                && !dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName);

        if (!isInternalTool && !isDynamicAllowed) {
            log.warnf(
                    "GUARD Unknown tool call attempt '%s' in session '%s' - "
                            + "not in ToolRegistry and not allowed by DynamicToolLookup.",
                    toolName, sessionId);
            return new GuardDecision.Blocked("Tool '" + toolName + "' is not recognized or permitted.");
        }

        AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
        boolean isSchedulingTool = ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName);
        boolean isDelegationTool = ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName);

        boolean toolRequiresApproval =
                toolOpt.map(tool -> tool.requiresManualApproval(params)).orElse(false);

        // Memory search/ingest tools are read-only and should be exempt from HITL approval
        // at both BACKGROUND and SUPERVISED levels. Only FULL autonomy bypasses all checks.
        boolean isMemoryTool = ToolCapability.RECALL_MEMORY_SEARCH.toolName().equals(toolName)
                || ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName().equals(toolName)
                || ToolCapability.INGEST_DOCUMENT.toolName().equals(toolName);

        boolean isExempt = isSchedulingTool || isDelegationTool || (isMemoryTool && level != AutonomyLevel.READONLY);

        if ((roleRegistry.requiresApprovalForTools(agentRole) || toolRequiresApproval)
                && !isExempt
                && !AutonomyLevel.FULL.equals(level)) {

            ToolOperation op = toolOpt.map(AgentTool::requiredOperation).orElse(ToolOperation.EXECUTE_SHELL);
            ApprovalFrame frame = new ApprovalFrame(toolName, params, op.riskLevel());
            return new GuardDecision.PauseForApproval(frame);
        }
        return new GuardDecision.Allow();
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
        } catch (Exception e) {
            return toolName + ":err:" + Objects.hash(params);
        }
    }
}
