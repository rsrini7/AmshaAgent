package com.amsha.core.api;

import java.util.List;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.CheckpointResolutionException;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.AgentSentinels;

import org.jboss.logging.Logger;

/**
 * Cascades a parent session's HITL decision into its delegated sub-sessions.
 *
 * <p>When an agent delegates work (coordinator {@code garuda} researcher), the delegate runs
 * under {@code parentSessionId + ":delegate:" + agent + ":" + timestamp}. If the delegate
 * pauses for approval, the parent re-pauses with no undecided tools of its own, so a REST
 * approve/deny on the parent resolves nothing and {@code approveAll}/{@code denyAll} throw
 * {@code alreadyDecided}. Without this cascade the operator's decision cannot reach the
 * parked delegate checkpoint and the flow stalls until session cleanup.
 *
 * <p>Only direct awaiting descendants of the approved parent are resolved; cron sessions are
 * never matched (their ids never carry this parent's prefix). Concurrently-resolved
 * delegates are skipped, never failed.
 */
public final class DelegateApprovalCascade {

    private static final Logger LOG = Logger.getLogger(DelegateApprovalCascade.class);

    private final StatefulAgentStore agentStore;
    private final CheckpointApprovalService approvalService;
    private final AuditService auditService;

    public DelegateApprovalCascade(
            StatefulAgentStore agentStore, CheckpointApprovalService approvalService, AuditService auditService) {
        this.agentStore = agentStore;
        this.approvalService = approvalService;
        this.auditService = auditService;
    }

    /**
     * Approves every awaiting delegate descendant of {@code parentSessionId}, resuming each.
     *
     * @return the number of delegate checkpoints resolved
     */
    public int approveAwaitingDelegates(String parentSessionId, String userId, String actorRole)
            throws CheckpointResolutionException {
        return resolveAwaitingDelegates(parentSessionId, userId, actorRole, true);
    }

    /**
     * Denies every awaiting delegate descendant of {@code parentSessionId}, resuming each.
     *
     * @return the number of delegate checkpoints resolved
     */
    public int denyAwaitingDelegates(String parentSessionId, String userId, String actorRole)
            throws CheckpointResolutionException {
        return resolveAwaitingDelegates(parentSessionId, userId, actorRole, false);
    }

    /** Resolves the parent approval, falling back to awaiting delegates when already resolved. */
    public void approveOrCascade(String sessionId, String userId, String actorRole)
            throws CheckpointResolutionException {
        try {
            AgentCheckpoint checkpoint = approvalService.approveAll(sessionId, "Human approved via REST");
            approvalService.triggerResume(checkpoint);
        } catch (CheckpointResolutionException alreadyResolved) {
            if (!isAlreadyResolved(alreadyResolved)) throw alreadyResolved;
            if (approveAwaitingDelegates(sessionId, userId, actorRole) == 0) {
                throw alreadyResolved;
            }
            agentStore.findLatestAwaitingApproval(sessionId).ifPresent(approvalService::triggerResume);
        }
    }

    /** Denies the parent approval, falling back to awaiting delegates when already resolved. */
    public void denyOrCascade(String sessionId, String userId, String actorRole) throws CheckpointResolutionException {
        try {
            AgentCheckpoint checkpoint = approvalService.denyAll(sessionId, "Human denied via REST");
            approvalService.triggerResume(checkpoint);
        } catch (CheckpointResolutionException alreadyResolved) {
            if (!isAlreadyResolved(alreadyResolved)) throw alreadyResolved;
            if (denyAwaitingDelegates(sessionId, userId, actorRole) == 0) {
                throw alreadyResolved;
            }
            agentStore.findLatestAwaitingApproval(sessionId).ifPresent(approvalService::triggerResume);
        }
    }

    private int resolveAwaitingDelegates(String parentSessionId, String userId, String actorRole, boolean approve)
            throws CheckpointResolutionException {
        if (parentSessionId == null
                || parentSessionId.isBlank()
                || parentSessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            return 0;
        }
        String prefix = parentSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL;
        List<AgentCheckpoint> delegates;
        try {
            delegates = agentStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL).stream()
                    .filter(cp -> cp != null
                            && cp.getSessionId() != null
                            && cp.getSessionId().startsWith(prefix)
                            && cp.getSessionId().length() > prefix.length()
                            && cp.getAgentRole() != null
                            && !cp.getAgentRole().isBlank()
                            && userId != null
                            && userId.equals(cp.getChannelPlatformId()))
                    .toList();
        } catch (RuntimeException e) {
            LOG.warnf(
                    "Delegate approval scan failed for session %s: %s",
                    parentSessionId, e.getClass().getSimpleName());
            throw new IllegalStateException("Delegate approval scan unavailable", e);
        }
        int resolved = 0;
        for (AgentCheckpoint delegate : delegates) {
            try {
                String reason =
                        (approve ? "Human approved via REST (delegate of " : "Human denied via REST (delegate of ")
                                + parentSessionId
                                + ")";
                AgentCheckpoint resolvedCp = approve
                        ? approvalService.approveAll(delegate.getSessionId(), reason)
                        : approvalService.denyAll(delegate.getSessionId(), reason);
                auditService.recordMandatory(
                        delegate.getSessionId(),
                        userId,
                        delegate.getAgentRole() != null ? delegate.getAgentRole() : actorRole,
                        approve ? SecurityEventType.APPROVAL_GRANTED : SecurityEventType.APPROVAL_DENIED,
                        "via=REST parent=" + parentSessionId);
                approvalService.triggerResume(resolvedCp);
                resolved++;
            } catch (CheckpointResolutionException raced) {
                if (isResolutionRace(raced)) {
                    LOG.debugf("Delegate %s resolved concurrently, skipping", delegate.getSessionId());
                } else {
                    throw raced;
                }
            }
        }
        return resolved;
    }

    private static boolean isResolutionRace(CheckpointResolutionException exception) {
        return exception.getStatusCode() == 404 || isAlreadyResolved(exception);
    }

    private static boolean isAlreadyResolved(CheckpointResolutionException exception) {
        return exception.getStatusCode() == 409
                && exception.getMessage() != null
                && exception.getMessage().startsWith("Approval decision already resolved");
    }
}
