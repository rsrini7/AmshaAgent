package com.amsha.core.agent.loop;

import java.time.Instant;
import java.util.Objects;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.util.CronScheduleUtil;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;

import org.jboss.logging.Logger;

/**
 * Utility for synchronizing cron status across resume and terminal decision boundaries.
 */
public final class CronSessionStatusSync {

    private static final Logger log = Logger.getLogger(CronSessionStatusSync.class);

    private CronSessionStatusSync() {}

    public static boolean syncRunning(String sessionId, CronStorePort cronStore) {
        if (sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            String sub = sessionId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
            if (sub.length() < 36) {
                log.warnf("Malformed cron session id %s - refusing resume", sessionId);
                WorkspaceManager.haltSession(sessionId);
                return false;
            }
            String jobId = sub.substring(0, 36);
            String executionId = extractExecutionId(sub);
            if (executionId == null || executionId.isBlank()) {
                log.warnf("Legacy or malformed cron session id %s - refusing resume", sessionId);
                WorkspaceManager.haltSession(sessionId);
                return false;
            }
            try {
                boolean resumed =
                        cronStore.claimForResume(jobId, executionId) || cronStore.renewLease(jobId, executionId);
                if (!resumed) {
                    log.warnf(
                            "Failed to claim cron %s for resume (deliveryId=%s) - lease lost or superseded",
                            jobId, executionId);
                    WorkspaceManager.haltSession(sessionId);
                    return false;
                }
            } catch (Exception e) {
                log.warnf("Failed to sync cron status for job %s: %s", jobId, LogUtil.rootMessage(e));
                WorkspaceManager.haltSession(sessionId);
                return false;
            }
        }
        return true;
    }

    public static boolean syncTerminal(
            String sessionId, AgentResult result, CronStorePort cronStore, CronRunStorePort cronRunStore) {
        if (sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            String sub = sessionId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
            if (sub.length() < 36) {
                log.warnf("Malformed cron session id %s - refusing terminal transition", sessionId);
                WorkspaceManager.haltSession(sessionId);
                return false;
            }
            String jobId = sub.substring(0, 36);
            String executionId = extractExecutionId(sub);
            if (executionId == null || executionId.isBlank()) {
                log.warnf("Legacy or malformed cron session id %s - refusing terminal transition", sessionId);
                WorkspaceManager.haltSession(sessionId);
                return false;
            }
            Objects.requireNonNull(cronRunStore, "cronRunStore");
            try {
                boolean transitioned = markTerminal(jobId, executionId, result, cronStore);
                if (!transitioned) {
                    log.warnf(
                            "Failed to transition cron %s terminal state (deliveryId=%s) - lease lost",
                            jobId, executionId);
                    WorkspaceManager.haltSession(sessionId);
                } else {
                    updateRun(executionId, result, cronRunStore);
                }
                return transitioned;
            } catch (Exception e) {
                log.warnf("Failed to sync terminal cron status for job %s: %s", jobId, LogUtil.rootMessage(e));
                WorkspaceManager.haltSession(sessionId);
                return false;
            }
        }
        return true;
    }

    private static void updateRun(String executionId, AgentResult result, CronRunStorePort cronRunStore) {
        if (result == null) {
            return;
        }
        try {
            cronRunStore
                    .findById(executionId)
                    .ifPresent(run -> cronRunStore.save(CronRunResultMapper.fromResult(run, result)));
        } catch (RuntimeException e) {
            log.warnf("Failed sync cron run status execution %s: %s", executionId, LogUtil.rootMessage(e));
        }
    }

    public static boolean markTerminal(String id, String deliveryId, AgentResult result, CronStorePort cronStore) {
        var dto = cronStore.findById(id).orElse(null);
        if (dto == null || result == null) return true;
        var cron = AgentCronRecord.fromDto(dto);
        return applyTerminal(cron, deliveryId, result, cronStore);
    }

    public static boolean applyTerminal(
            AgentCron cron, String deliveryId, AgentResult result, CronStorePort cronStore) {
        String id = cron.getId();
        switch (result) {
            case AgentResult.Finished _, AgentResult.StructuredFinished _ -> {
                if (cron.isRunOnce()) {
                    if (deliveryId != null) {
                        return cronStore.markCompletedIfOwner(id, deliveryId, Instant.now());
                    } else {
                        cronStore.markCompleted(id, Instant.now());
                        return true;
                    }
                } else {
                    Instant next = CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone());
                    if (deliveryId != null) {
                        return cronStore.markFiredIfOwner(id, deliveryId, Instant.now(), next);
                    } else {
                        cronStore.markFired(id, Instant.now(), next);
                        return true;
                    }
                }
            }
            case AgentResult.Failed f -> {
                if (deliveryId != null) {
                    return cronStore.markFailedIfOwner(id, deliveryId, f.reason());
                } else {
                    cronStore.markFailed(id, f.reason());
                    return true;
                }
            }
            case AgentResult.AwaitingApproval _ -> {
                if (deliveryId != null) {
                    return cronStore.markAwaitingApprovalIfOwner(id, deliveryId);
                } else {
                    cronStore.markAwaitingApproval(id);
                    return true;
                }
            }
            case AgentResult.AwaitingRetry _ -> {
                if (deliveryId != null) {
                    return cronStore.markAwaitingRetryIfOwner(id, deliveryId);
                }
                return false;
            }
            case AgentResult.LoopExceeded _ -> {
                if (deliveryId != null) {
                    return cronStore.markFailedIfOwner(id, deliveryId, "Loop exceeded");
                } else {
                    cronStore.markFailed(id, "Loop exceeded");
                    return true;
                }
            }
            default -> {
                return true;
            }
        }
    }

    static String extractExecutionId(String sub) {
        if (sub.length() == 36) {
            return null;
        }
        if (sub.length() <= 37 || sub.charAt(36) != '-') {
            return "";
        }
        String rest = sub.substring(37);
        int delIdx = rest.indexOf(AgentSentinels.DELEGATE_SESSION_SENTINEL);
        return delIdx >= 0 ? rest.substring(0, delIdx) : rest;
    }
}
