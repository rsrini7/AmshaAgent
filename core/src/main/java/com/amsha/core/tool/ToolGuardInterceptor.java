package com.amsha.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.loop.ToolInvocationGuard;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.skill.CompiledSkillEntry;

/**
 * SPI for providing tool-specific guard logic (intent checks, deep dedup)
 * without modifying the core ToolInvocationGuard.
 */
public interface ToolGuardInterceptor {
    /**
     * Tool names this interceptor handles.
     * Empty = handles all (proceed with caution).
     */
    Set<String> handledToolNames();

    /**
     * Evaluate guard for this specific tool.
     * Return Optional.empty() to pass through to the next interceptor.
     * Return Optional.of(decision) to halt and use this decision.
     */
    Optional<ToolInvocationGuard.GuardDecision> evaluate(
            String sessionId,
            String agentRole,
            AutonomyLevel autonomyLevel,
            String toolName,
            Map<String, Object> params,
            List<ReasoningStep> history,
            String goal,
            List<CompiledSkillEntry> skillSnapshot);
}
