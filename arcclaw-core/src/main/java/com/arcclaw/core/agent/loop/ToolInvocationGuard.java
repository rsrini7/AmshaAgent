package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Subclass;
import jakarta.annotation.PostConstruct;
import org.jboss.logging.Logger;

/**
 * Validates tool calls before invocation.
 * Handles deduplication, security intent guards, and pre-invocation
 * human-in-the-loop (HITL) approvals.
 */
@ApplicationScoped
public class ToolInvocationGuard {
    private static final Logger log = Logger.getLogger(ToolInvocationGuard.class);

    @Inject public RoleRegistry roleRegistry;
    @Inject public ObjectMapper objectMapper;
    @Inject public ToolRegistry toolRegistry;
    @Inject public Instance<DynamicToolLookup> dynamicToolLookup;
    @Inject public Instance<ToolGuardInterceptor> interceptors;
    
    // Session-scoped deduplication cache: SessionId -> (DedupeKey -> Observation)
    private final Map<String, Map<String, String>> sessionDedupeCache = new ConcurrentHashMap<>();

    private List<ToolGuardInterceptor> sortedInterceptors;

    @PostConstruct
    void init() {
        this.sortedInterceptors = java.util.stream.StreamSupport.stream(interceptors.spliterator(), false)
            .sorted(java.util.Comparator.comparingInt(this::getPriority))
            .toList();
        log.infof("ToolInvocationGuard initialized with %d sorted interceptors.", sortedInterceptors.size());
    }

    private int getPriority(ToolGuardInterceptor interceptor) {
        Class<?> clazz = interceptor.getClass();
        // Handle CDI proxies
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

    /**
     * Evaluates whether a tool call should proceed, be blocked, be deduplicated, or
     * pause for approval.
     */
    public GuardDecision evaluate(String sessionId, String agentRole,
            String toolName, Map<String, Object> params,
            List<ReasoningStep> history, String goal, List<CompiledSkillEntry> skillSnapshot) {

        // 0. RUN REGISTERED INTERCEPTORS (SPI) - now sorted by priority
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

        // 1. DEDUPLICATION CHECK (O(1) with stable hash/JSON key)
        Map<String, String> dedupeIndex = sessionDedupeCache.computeIfAbsent(sessionId, k -> {
            Map<String, String> index = new ConcurrentHashMap<>();
            // Build index from history on first access in this turn
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
            log.warnf("[DEDUP-GUARD] Duplicate tool call '%s' detected for session=%s — returning cached observation.",
                    toolName, sessionId);
            String cachedObs = pastObservation + "\n\n[NOTE: This result was retrieved from " +
                    "the current session cache. The tool was not re-executed.]";
            return new GuardDecision.Deduplicate(cachedObs);
        }

        // 1.5 TOOL EXISTENCE CHECK (Internal and Dynamic)
        boolean isInternalTool = toolRegistry.findTool(toolName).isPresent();
        boolean isDynamicAllowed = !isInternalTool
                && dynamicToolLookup != null && !dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName);

        if (!isInternalTool && !isDynamicAllowed) {
            log.warnf("[GUARD] Unknown tool call attempt: %s in session %s", toolName, sessionId);
            return new GuardDecision.Blocked("Tool '" + toolName + "' is not recognized or permitted.");
        }

        // 3. HITL PRE-APPROVAL CHECK
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
                log.errorf("Failed to serialize params for HITL in session %s — blocking tool for safety: %s", sessionId, e.getMessage());
                log.debug("Full parameter serialization failure stack trace:", e);
                return new GuardDecision.Blocked(
                        "Tool blocked: failed to serialize parameters for approval.");
            }
        }

        return new GuardDecision.Allow();
    }
    /**
     * Clears cached deduplication data for a terminated session.
     */
    public void clearSession(String sessionId) {
        sessionDedupeCache.remove(sessionId);
    }

    /**
     * Records a new tool call in the deduplication cache so subsequent or parallel calls
     * with identical parameters can be skipped.
     */
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
            // Normalize params: sort keys for consistent JSON representation
            TreeMap<String, Object> sorted = new TreeMap<>(params);
            String normalizedJson = objectMapper.writeValueAsString(sorted);
            // Use stable hash suffix to keep keys manageable but deterministic
            return toolName + ":" + normalizedJson.hashCode();
        } catch (Exception e) {
            // Fallback for non-serializable content (unlikely in ToolInvocationGuard)
            return toolName + ":err:" + Objects.hash(params);
        }
    }
}

