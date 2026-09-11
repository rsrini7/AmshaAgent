package com.amsha.core.api.dto;

import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningStep;

/**
 * Status snapshot for a single session/role pair.
 * <p>
 * {@code activeGoal} and {@code activePhase} are populated only while the agent
 * loop is genuinely executing in memory. They let every UI (Web, CLI, Telegram)
 * show <em>what</em> the agent is working on instead of a bare status token, and
 * they stop a stale terminal checkpoint from hiding a turn that is still running.
 */
public record SessionStatusResponse(
        String sessionId,
        String status,
        List<ReasoningStep> steps,
        String failReason,
        String activeGoal,
        String activePhase) {

    /**
     * Backwards-compatible view with no live-execution detail attached.
     */
    public SessionStatusResponse(String sessionId, String status, List<ReasoningStep> steps, String failReason) {
        this(sessionId, status, steps, failReason, null, null);
    }
}
