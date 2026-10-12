package com.amsha.core.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.CronScheduleUtil;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;

import org.jboss.logging.Logger;

/** Handles cron-specific stale approval and failed-session cleanup. */
@ApplicationScoped
public class CronFailureCleanup {
    private static final Logger log = Logger.getLogger(CronFailureCleanup.class);

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    CronStorePort cronStore;

    @Inject
    CronRunStorePort cronRunStore;

    public void sweepOrphanedApprovals(Instant cutoff, long timeoutHours) {
        List<AgentCronDto> awaitingCrons = cronStore.findAllAwaitingApproval();
        for (AgentCronDto cron : awaitingCrons) {
            Instant reference = cron.updatedAt() != null ? cron.updatedAt() : cron.createdAt();
            if (reference == null || !reference.isBefore(cutoff)) {
                continue;
            }
            Optional<AgentCheckpoint> checkpoint = loadApprovalCheckpoint(cron);
            if (checkpoint.isPresent() && checkpoint.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                continue;
            }

            log.warnf(
                    "[HITL-GC] Orphaned cron %s has been awaiting approval for >%dh without active checkpoint - failing.",
                    cron.id(), timeoutHours);
            String reason = "Approval timed out after " + timeoutHours + " hours.";
            boolean failed = cronStore.markFailedIfStatus(cron.id(), CronStatus.AWAITING_APPROVAL.name(), reason);
            if (failed) {
                markRunFailed(cron.lastDeliveryId(), reason);
            }
        }
    }

    public void cleanupCronOnFailure(String sessionId, String reason) {
        Optional<CronSessionParts> parts = parseSessionId(sessionId);
        if (parts.isEmpty()) {
            return;
        }
        CronSessionParts cronSession = parts.get();
        if (cronSession.executionId() != null) {
            markRunFailed(cronSession.executionId(), reason);
        }

        try {
            AgentCronDto cron = cronStore.findById(cronSession.jobId()).orElse(null);
            if (cron == null) {
                return;
            }
            boolean runningOwner = CronStatus.RUNNING.name().equals(cron.status())
                    && (cronSession.executionId() == null
                            ? cron.lastDeliveryId() == null
                            : cronSession.executionId().equals(cron.lastDeliveryId()));
            boolean pending = CronStatus.AWAITING_APPROVAL.name().equals(cron.status())
                    || CronStatus.AWAITING_RETRY.name().equals(cron.status());
            boolean updated = transitionFailedCron(cron, cronSession.executionId(), reason, runningOwner, pending);
            if (!updated && (runningOwner || pending)) {
                log.infof("CRON-REAP-SKIP cronId=%s state changed before cleanup", cron.id());
            }
        } catch (Exception e) {
            log.warnf(
                    "Failed to update cron status on failure for job %s: %s",
                    cronSession.jobId(), LogUtil.rootMessage(e));
        }
    }

    private boolean transitionFailedCron(
            AgentCronDto cron, String executionId, String reason, boolean runningOwner, boolean pending) {
        if (runningOwner
                && !cron.runOnce()
                && cron.cronExpression() != null
                && !cron.cronExpression().isBlank()) {
            if (executionId == null) {
                return cronStore.markFailedIfStatus(cron.id(), CronStatus.RUNNING.name(), reason);
            }
            Instant next = CronScheduleUtil.computeNext(cron.cronExpression(), cron.timezone());
            boolean updated = cronStore.markFiredIfOwner(cron.id(), executionId, Instant.now(), next);
            log.infof("CRON-REAP-RETRY recurring cronId=%s failed/timed out (rescheduled for %s)", cron.id(), next);
            return updated;
        }
        if (runningOwner) {
            boolean updated = executionId == null
                    ? cronStore.markFailedIfStatus(cron.id(), CronStatus.RUNNING.name(), reason)
                    : cronStore.markFailedIfOwner(cron.id(), executionId, reason);
            log.warnf("CRON-REAP-FAILED one-shot cronId=%s failed/timed out", cron.id());
            return updated;
        }
        if (pending) {
            boolean updated = cronStore.markFailedIfStatus(cron.id(), cron.status(), reason);
            log.warnf("CRON-REAP-FAILED pending cronId=%s failed/timed out", cron.id());
            return updated;
        }
        log.infof(
                "CRON-REAP-SKIP cronId=%s is no longer owned by stale session executionId=%s", cron.id(), executionId);
        return false;
    }

    private Optional<AgentCheckpoint> loadApprovalCheckpoint(AgentCronDto cron) {
        String sessionId = "cron-" + cron.id();
        String deliveryId = cron.lastDeliveryId();
        if (deliveryId != null && !deliveryId.isBlank()) {
            sessionId += "-" + deliveryId;
        }
        String role = cron.executionRole();
        if (role == null || role.isBlank()) {
            role = AgentRoleEnum.COORDINATOR_ID;
        }
        return agentStore.load(sessionId, role);
    }

    private void markRunFailed(String runId, String reason) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        cronRunStore
                .findById(runId)
                .ifPresent(existing -> cronRunStore.save(new CronRunDto(
                        existing.id(),
                        existing.cronId(),
                        existing.sessionId(),
                        "FAILED",
                        existing.startedAt(),
                        Instant.now(),
                        reason,
                        existing.iterationsUsed(),
                        existing.inputTokens(),
                        existing.outputTokens(),
                        existing.usdCost(),
                        existing.createdAt())));
    }

    private Optional<CronSessionParts> parseSessionId(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("cron-")) {
            return Optional.empty();
        }
        String suffix = sessionId.substring("cron-".length());
        if (suffix.length() < 36) {
            return Optional.empty();
        }
        String jobId = suffix.substring(0, 36);
        if (suffix.length() == 36) {
            return Optional.of(new CronSessionParts(jobId, null));
        }
        if (suffix.charAt(36) != '-' || suffix.length() <= 37) {
            log.warnf("Malformed cron session id %s - skipping cron cleanup", sessionId);
            return Optional.empty();
        }
        return Optional.of(new CronSessionParts(jobId, suffix.substring(37)));
    }

    private record CronSessionParts(String jobId, String executionId) {}
}
