package com.arcclaw.core.agent.loop;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tool.InterceptorPriority;
import com.arcclaw.core.tool.ToolGuardInterceptor;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Subclass;
import org.jboss.logging.Logger;

/**
 * Validates tool calls before invocation.
 */
@ApplicationScoped
public class ToolInvocationGuard {
    private static final Logger log = Logger.getLogger(ToolInvocationGuard.class);

    @Inject public RoleRegistry roleRegistry;
    @Inject public ObjectMapper objectMapper;
    @Inject public ToolRegistry toolRegistry;
    @Inject public Instance<DynamicToolLookup> dynamicToolLookup;
    @Inject public Instance<ToolGuardInterceptor> interceptors;
    
    private final Map<String, Map<String, String>> sessionDedupeCache = new ConcurrentHashMap<>();

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
            permits GuardDecision.Allow, GuardDecision.Deduplicate,
            GuardDecision.PauseForApproval, GuardDecision.Blocked {
        record Allow() implements GuardDecision {}
        record Deduplicate(String cachedObservation) implements GuardDecision {}
        record PauseForApproval(String toolName, String paramsJson) implements GuardDecision {}
        record Blocked(String observation) implements GuardDecision {}
    }

    public GuardDecision evaluate(String sessionId, String agentRole,
            String toolName, Map<String, Object> params,
            List<ReasoningStep> history, String goal, List<CompiledSkillEntry> skillSnapshot) {

        for (ToolGuardInterceptor interceptor : sortedInterceptors) {
            if (interceptor.handledToolNames().isEmpty()
                    || interceptor.handledToolNames().contains(toolName)) {
                Optional<GuardDecision> decision = interceptor.evaluate(
                        sessionId, agentRole, toolName, params, history, goal, skillSnapshot);
                if (decision.isPresent()) {
                    return decision.get();
                }
            }
        }

        IdempotencyMode mode = toolRegistry.findTool(toolName)
                .map(AgentTool::idempotencyMode)
                .orElse(IdempotencyMode.NON_IDEMPOTENT);

        if (mode != IdempotencyMode.NON_IDEMPOTENT) {
            Map<String, String> dedupeIndex = sessionDedupeCache.computeIfAbsent(sessionId, k -> {
                Map<String, String> index = new ConcurrentHashMap<>();
                for (ReasoningStep past : history) {
                    if (past.observation() != null && !past.observation().isBlank()) {
                        index.put(buildDedupeKey(past.action(), past.actionInput()), past.observation());
                    }
                }
                return index;
            });

            String currentKey = buildDedupeKey(toolName, params);
            if (dedupeIndex.containsKey(currentKey)) {
                String pastObservation = dedupeIndex.get(currentKey);
                String cachedObs = pastObservation + "\n\n[DEDUP]";
                return new GuardDecision.Deduplicate(cachedObs);
            }
        }

        boolean isInternalTool = toolRegistry.findTool(toolName).isPresent();
        boolean isDynamicAllowed = !isInternalTool
                && dynamicToolLookup != null && !dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName);

        if (!isInternalTool && !isDynamicAllowed) {
            log.warnf(
                "GUARD Unknown tool call attempt '%s' in session '%s' - " +
                "not in ToolRegistry and not allowed by DynamicToolLookup.",
                toolName, sessionId);
            return new GuardDecision.Blocked("Tool '" + toolName + "' is not recognized or permitted.");
        }

        AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
        boolean isSchedulingTool = ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName);

        boolean toolRequiresApproval = toolRegistry.findTool(toolName)
                .map(tool -> tool.requiresManualApproval(params))
                .orElse(false);

        if ((roleRegistry.requiresApprovalForTools(resolvedRole) || toolRequiresApproval)
                && !isSchedulingTool
                && !AutonomyLevel.FULL.equals(WorkspaceContext.requireAutonomyOrSupervised())) {

            try {
                String paramsJson = objectMapper.writeValueAsString(params);
                return new GuardDecision.PauseForApproval(toolName, paramsJson);
            } catch (Exception e) {
                return new GuardDecision.Blocked(
                        "Tool blocked: failed to serialize parameters for approval.");
            }
        }

        return new GuardDecision.Allow();
    }

    public void clearSession(String sessionId) {
        sessionDedupeCache.remove(sessionId);
    }

    public void recordToolCall(String sessionId, String toolName, Map<String, Object> params, String observation) {
        Map<String, String> index = sessionDedupeCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        if (observation != null) {
            index.put(buildDedupeKey(toolName, params), observation);
        }
    }

    private String buildDedupeKey(String toolName, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return toolName + ":empty";
        }
        try {
            TreeMap<String, Object> sorted = new TreeMap<>(params);
            String normalizedJson = objectMapper.writeValueAsString(sorted);
            return toolName + ":" + normalizedJson.hashCode();
        } catch (Exception e) {
            return toolName + ":err:" + Objects.hash(params);
        }
    }
}
