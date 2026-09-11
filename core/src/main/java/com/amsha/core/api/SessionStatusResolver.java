package com.amsha.core.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.api.dto.SessionStatusResponse;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.security.InputSanitizer;
import com.amsha.core.state.ActiveWork;
import com.amsha.core.state.SessionTree;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.AgentSentinels;

/**
 * Resolves the status snapshot served by {@code GET /sessions/{id}/status/{role}}
 * (Web UI, REST clients, cockpit).
 * <p>
 * Kept out of {@link AgentSessionService} purely as a code-size seam; the logic
 * is a pure function of {@link StatefulAgentStore}.
 */
final class SessionStatusResolver {

    private SessionStatusResolver() {}

    static ServiceResult<Object> resolve(StatefulAgentStore agentStore, String sessionId, String canonicalRole) {
        return resolve(agentStore, sessionId, canonicalRole, null);
    }

    /**
     * Builds the status response, preferring live in-memory execution state over a
     * persisted checkpoint row that may already have gone terminal.
     */
    static ServiceResult<Object> resolve(
            StatefulAgentStore agentStore, String sessionId, String canonicalRole, InputSanitizer piiSanitizer) {
        var checkpointOpt = loadStatusCheckpoint(agentStore, sessionId, canonicalRole);
        CheckpointStatus persisted =
                checkpointOpt.map(AgentCheckpoint::getStatus).orElse(null);
        String status = persisted == null ? CheckpointStatus.NOT_STARTED.name() : persisted.name();
        List<ReasoningStep> steps =
                checkpointOpt.map(cp -> agentStore.loadSteps(cp.getId())).orElseGet(List::of);
        String failReason = checkpointOpt.map(AgentCheckpoint::getFailReason).orElse(null);

        ActiveWork work = ActiveWork.EMPTY;
        if (isLiveExecution(agentStore, sessionId, persisted)) {
            status = CheckpointStatus.IN_PROGRESS.name();
            String fallbackGoal =
                    checkpointOpt.map(AgentCheckpoint::getOriginalGoal).orElse(null);
            ActiveWork resolved = ActiveWork.resolve(
                    agentStore.getActiveWork(sessionId),
                    agentStore.getActiveGoal(sessionId),
                    agentStore.getActivePhase(sessionId),
                    fallbackGoal);
            work = ActiveWork.of(
                    sanitizeGoal(piiSanitizer, resolved.goal()), sanitizePhase(piiSanitizer, resolved.phase()));
        }

        if (work.isEmpty() && CheckpointStatus.NOT_STARTED.name().equals(status)) {
            return ServiceResult.success(Map.of("sessionId", sessionId, "status", status));
        }
        return ServiceResult.success(
                new SessionStatusResponse(sessionId, status, steps, failReason, work.goal(), work.phase()));
    }

    private static String sanitizeGoal(InputSanitizer piiSanitizer, String goal) {
        return piiSanitizer != null && goal != null ? piiSanitizer.sanitize(goal) : goal;
    }

    private static String sanitizePhase(InputSanitizer piiSanitizer, String phase) {
        if (phase == null || phase.isBlank()) return phase;
        return piiSanitizer != null ? piiSanitizer.sanitize(phase) : phase;
    }

    private static Optional<AgentCheckpoint> loadStatusCheckpoint(
            StatefulAgentStore agentStore, String sessionId, String canonicalRole) {
        Optional<AgentCheckpoint> rootCheckpoint =
                sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)
                        ? agentStore.findLatestNonArchivedCheckpoint(sessionId)
                        : agentStore.load(sessionId, canonicalRole);

        if (rootCheckpoint.isPresent() && rootCheckpoint.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
            return rootCheckpoint;
        }

        Optional<AgentCheckpoint> delegatedApprovalOpt = agentStore
                .findLatestAwaitingApprovalBySessionContaining(sessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL)
                .filter(cp -> SessionTree.contains(sessionId, cp.getSessionId()));
        if (delegatedApprovalOpt.isPresent()) {
            return delegatedApprovalOpt;
        }

        return rootCheckpoint;
    }

    /**
     * A turn can be executing in memory without an up-to-date checkpoint row: the
     * previous turn's row may already be {@code COMPLETED}, or the new turn may
     * not have written one yet. The in-memory resume mark is authoritative in that
     * case, so a UI polling the status endpoint stops reporting a stale terminal
     * status while the agent is still working.
     * <p>
     * <strong>Bounded TTL and Crash Recovery Boundary:</strong>
     * The in-memory resume mark is bounded by {@code amsha.resumption.stale-mark-seconds}
     * (and {@code playwright-stale-mark-seconds}) in {@link com.amsha.core.state.ResumeCoordinationGuard},
     * refreshed via {@code touch()} on active reasoning/tool steps. If the process crashes
     * or halts abruptly without writing a terminal checkpoint, the in-memory mark auto-expires,
     * ensuring status queries safely fall back to the persisted checkpoint row rather than
     * reporting indeterminate in-progress status indefinitely.
     * <p>
     * Human-parked states are excluded: while the session is genuinely waiting on
     * an approval or a retry delay, that is the status the user must see.
     */
    private static boolean isLiveExecution(
            StatefulAgentStore agentStore, String sessionId, CheckpointStatus persisted) {
        if (persisted == CheckpointStatus.AWAITING_APPROVAL || persisted == CheckpointStatus.AWAITING_RETRY) {
            return false;
        }
        if (agentStore.isBatchResuming(sessionId)) {
            return true;
        }
        return agentStore
                .findLatestActiveDelegatedCheckpoint(sessionId)
                .filter(cp -> !StatefulAgentStore.isStaleActiveCheckpoint(cp))
                .map(AgentCheckpoint::getStatus)
                .filter(s -> s == CheckpointStatus.IN_PROGRESS || s == CheckpointStatus.RESUMING)
                .isPresent();
    }
}
