package com.amsha.app.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.CronRunResultMapper;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.event.FlushQueuedNotifications;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.core.util.CronScheduleUtil;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;

import org.jboss.logging.Logger;

/**
 * Handles the outcomes of cron job executions: updates database state,
 * records audit events, fires telemetry events, and manages workspace sessions.
 */
@ApplicationScoped
@SuppressWarnings("java:S1192")
public class CronResultHandler {

    private static final Logger log = Logger.getLogger(CronResultHandler.class);

    private final CronStorePort cronStore;
    private final CronRunStorePort cronRunStore;
    private final AuditService auditService;
    private final WorkspaceManager workspaceManager;
    private final Event<AgentJobCompleted> jobCompletedEvent;
    private final StatefulAgentStore statefulAgentStore;

    protected CronResultHandler() {
        this.cronStore = null;
        this.cronRunStore = null;
        this.auditService = null;
        this.workspaceManager = null;
        this.jobCompletedEvent = null;
        this.statefulAgentStore = null;
    }

    @Inject
    public CronResultHandler(
            CronStorePort cronStore,
            CronRunStorePort cronRunStore,
            AuditService auditService,
            WorkspaceManager workspaceManager,
            Event<AgentJobCompleted> jobCompletedEvent,
            StatefulAgentStore statefulAgentStore) {
        this.cronStore = cronStore;
        this.cronRunStore = cronRunStore;
        this.auditService = auditService;
        this.workspaceManager = workspaceManager;
        this.jobCompletedEvent = jobCompletedEvent;
        this.statefulAgentStore = statefulAgentStore;
    }

    @ActivateRequestContext
    public void handleSuccess(AgentCron job, String cronSessionId, String executionId, AgentResult result) {
        if (result instanceof AgentResult.AwaitingApproval a) {
            handleApprovalPause(job, cronSessionId, executionId, a);
        } else if (result instanceof AgentResult.AwaitingRetry) {
            boolean marked = cronStore.markAwaitingRetryIfOwner(job.getId(), executionId);
            if (!marked) {
                log.warnf("CronResultHandler: Failed to mark cron %s as AWAITING_RETRY - lease lost", job.getId());
                updateRunStatus(
                        executionId,
                        CronRunResultMapper.STATUS_LOST,
                        "Lease lost before transitioning to retry backoff",
                        result);
                workspaceManager.terminateSession(cronSessionId);
            } else {
                updateRunStatus(executionId, CronRunResultMapper.STATUS_AWAITING_RETRY, null, result);
                log.infof(
                        "CronResultHandler: Session kept alive for cron=%s AWAITING_RETRY (rate limit).",
                        cronSessionId);
            }
        } else {
            handleNormalCompletion(job, cronSessionId, executionId, result);
        }
    }

    private void updateRunStatus(String executionId, String status, String errorReason, AgentResult result) {
        cronRunStore.findById(executionId).ifPresent(existing -> {
            CronRunDto updated = CronRunResultMapper.withStatus(existing, status, errorReason, result);
            cronRunStore.save(updated);
        });
    }

    // Handles Finished, Failed, LoopExceeded - always terminates session
    private void handleNormalCompletion(AgentCron job, String cronSessionId, String executionId, AgentResult result) {
        boolean finalized = handleFinalResult(job, cronSessionId, executionId, result);
        if (!finalized) {
            log.warnf(
                    "CRON-LEASE-LOST cronId=%s deliveryId=%s lease lost or stolen during completion - skipping notification",
                    job.getId(), executionId);
            updateRunStatus(
                    executionId, CronRunResultMapper.STATUS_LOST, "Lease lost or stolen by another execution", result);
            workspaceManager.terminateSession(cronSessionId);
            return;
        }

        String answer = AgentResultFormatter.format(result);
        String finalAnswer = answer.trim().isEmpty()
                ? "⚠️ Task executed, but the agent returned an empty response for goal: " + job.getGoal()
                : answer;

        if (result instanceof AgentResult.Finished || result instanceof AgentResult.StructuredFinished) {
            updateRunStatus(executionId, CronRunResultMapper.STATUS_COMPLETED, null, result);
        } else {
            updateRunStatus(executionId, CronRunResultMapper.STATUS_FAILED, answer, result);
        }

        fireCompletionEvent(job, executionId, finalAnswer, null, cronSessionId, null);
        workspaceManager.terminateSession(cronSessionId);
    }

    // NOTE: In-memory queue for serializing CRON HITL alerts against live approvals.
    // This is not durable across server restarts, but old approvals are eventually
    // auto-reaped by the 24h background task cleanup.
    public static final ConcurrentHashMap<String, List<Runnable>> queuedNotifications = new ConcurrentHashMap<>();

    public static void flushQueuedNotifications(String chatId) {
        if (chatId == null) return;
        List<Runnable> runnables = queuedNotifications.remove(chatId);
        if (runnables != null) {
            for (Runnable r : runnables) {
                try {
                    r.run();
                } catch (Exception e) {
                    log.errorf("Failed to run queued notification for chatId=%s:\n%s", chatId, LogUtil.trimmedStack(e));
                }
            }
        }
    }

    public void onFlushNotifications(@Observes FlushQueuedNotifications event) {
        flushQueuedNotifications(event.chatId());
    }

    // Handles AwaitingApproval - session must stay alive for ApproveReflex
    private void handleApprovalPause(
            AgentCron job, String cronSessionId, String executionId, AgentResult.AwaitingApproval result) {
        boolean paused = cronStore.markAwaitingApprovalIfOwner(job.getId(), executionId);
        if (!paused) {
            log.warnf(
                    "CRON-LEASE-LOST cronId=%s deliveryId=%s lease lost or stolen during approval pause - aborting",
                    job.getId(), executionId);
            updateRunStatus(
                    executionId, CronRunResultMapper.STATUS_LOST, "Lease lost or stolen before approval pause", null);
            workspaceManager.terminateSession(cronSessionId);
            return;
        }

        updateRunStatus(executionId, CronRunResultMapper.STATUS_AWAITING_APPROVAL, null, null);
        log.infof("CRON-HITL cronId=%s awaiting user approval of tool=%s", job.getId(), result.toolName());

        auditService.record(
                cronSessionId,
                job.getUserId(),
                auditRole(job),
                SecurityEventType.LOOP_AWAITING_APPROVAL,
                "cronId=" + job.getId() + " pendingTool=" + result.toolName());

        String displayGoal = job.getGoal() != null ? job.getGoal().replaceFirst("^CRON\\s+", "") : "";
        // The parameters must reach the approval prompt. The 3-arg overload discards them, so the
        // operator was asked to approve a call without seeing its arguments — for a delegation that
        // hides both the target agent and the goal, leaving an uninformed approval. paramsJson() is
        // already the redacted display projection, so this adds no payload exposure.
        String answer =
                AgentResultFormatter.formatCronHitl(job.getId(), displayGoal, result.toolName(), result.paramsJson());

        Runnable notifyTask = () -> {
            fireCompletionEvent(job, executionId, answer, null, cronSessionId, result.toolName(), result.paramsJson());
            log.infof(
                    "[CronResultHandler] Session kept alive for cron=%s - AWAITING_APPROVAL. (Notification handled here)",
                    cronSessionId);
        };

        boolean queueForInteractiveChat = !ChannelType.WEBSOCKET.id().equalsIgnoreCase(job.getChannelType())
                && statefulAgentStore != null
                && statefulAgentStore.hasPendingApprovalForChat(job.getChatId());
        if (queueForInteractiveChat) {
            queuedNotifications
                    .computeIfAbsent(job.getChatId(), k -> new CopyOnWriteArrayList<>())
                    .add(notifyTask);
            log.infof("CRON-HITL: chat %s busy with live session approval, queuing CRON approval", job.getChatId());
        } else {
            notifyTask.run();
        }
    }

    @ActivateRequestContext
    public void handleFailure(AgentCron job, String cronSessionId, String executionId, Throwable t) {
        boolean failed = cronStore.markFailedIfOwner(job.getId(), executionId, t.getMessage());
        if (!failed) {
            log.warnf(
                    "CRON-LEASE-LOST cronId=%s deliveryId=%s lease lost or stolen during failure handling - aborting",
                    job.getId(), executionId);
            updateRunStatus(
                    executionId, CronRunResultMapper.STATUS_LOST, "Lease lost or stolen before failure handling", null);
            workspaceManager.terminateSession(cronSessionId);
            return;
        }

        updateRunStatus(executionId, CronRunResultMapper.STATUS_FAILED, t.getMessage(), null);

        auditService.record(
                cronSessionId,
                job.getUserId(),
                auditRole(job),
                SecurityEventType.CRONFAILED,
                "cronId=" + job.getId() + " reason=" + t.getMessage());

        if (t.getMessage() != null && t.getMessage().contains("[TEST-EXPECTED]")) {
            log.warnf("CRON-FAIL (EXPECTED) cronId=%s: %s", job.getId(), LogUtil.rootMessage(t));
        } else {
            log.warnf(t, "CRON-FAIL cronId=%s", job.getId());
        }

        String errorMessage =
                "❌ Scheduled task failed.\n\nReason: " + AgentErrorMessageUtil.sanitizeErrorMessage(t.getMessage());
        fireCompletionEvent(job, executionId, errorMessage, AgentResult.FailureCategory.SYSTEM_ERROR);

        workspaceManager.terminateSession(cronSessionId);
    }

    private boolean handleFinalResult(AgentCron job, String cronSessionId, String executionId, AgentResult result) {
        if (result instanceof AgentResult.Finished || result instanceof AgentResult.StructuredFinished) {
            if (job.isRunOnce()) {
                boolean ok = cronStore.markCompletedIfOwner(job.getId(), executionId, Instant.now());
                if (!ok) return false;
                auditService.record(
                        cronSessionId,
                        job.getUserId(),
                        auditRole(job),
                        SecurityEventType.CRONFIRED,
                        "cronId=" + job.getId() + " runOnce=true status=COMPLETED");
                log.infof("CRON-COMPLETED one-shot cronId=%s", job.getId());
                return true;
            } else {
                Instant next = CronScheduleUtil.computeNext(job.getCronExpression(), job.getTimezone());
                boolean ok = cronStore.markFiredIfOwner(job.getId(), executionId, Instant.now(), next);
                if (!ok) return false;
                auditService.record(
                        cronSessionId,
                        job.getUserId(),
                        auditRole(job),
                        SecurityEventType.CRONFIRED,
                        "cronId=" + job.getId() + " runOnce=false nextFire=" + next);
                log.infof("CRON-FIRED cronId=%s nextFire=%s", job.getId(), next);
                return true;
            }
        } else {
            if (!job.isRunOnce()
                    && job.getCronExpression() != null
                    && !job.getCronExpression().isBlank()) {
                // Recurring job failed — reschedule instead of permanent failure.
                // Free-tier LLMs fail intermittently; marking permanently FAILED
                // would kill the recurring schedule after the first transient error.
                Instant next = CronScheduleUtil.computeNext(job.getCronExpression(), job.getTimezone());
                boolean ok = cronStore.markFiredIfOwner(job.getId(), executionId, Instant.now(), next);
                if (!ok) return false;
                log.warnf(
                        "CRON-RETRY recurring cronId=%s failed (will retry at %s): %s",
                        job.getId(), next, AgentResultFormatter.format(result));
                auditService.record(
                        cronSessionId,
                        job.getUserId(),
                        auditRole(job),
                        SecurityEventType.CRONFAILED,
                        "cronId=" + job.getId() + " status=RETRY nextFire=" + next + " result="
                                + result.getClass().getSimpleName());
                return true;
            } else {
                boolean ok = cronStore.markFailedIfOwner(job.getId(), executionId, AgentResultFormatter.format(result));
                if (!ok) return false;
                auditService.record(
                        cronSessionId,
                        job.getUserId(),
                        auditRole(job),
                        SecurityEventType.CRONFAILED,
                        "cronId=" + job.getId() + " status=FAILED result="
                                + result.getClass().getSimpleName());
                return true;
            }
        }
    }

    private String auditRole(AgentCron job) {
        String executionRole = job.getExecutionRole();
        return executionRole == null || executionRole.isBlank()
                ? AgentRoleEnum.COORDINATOR.toScopedName()
                : executionRole;
    }

    private void fireCompletionEvent(
            AgentCron job, String executionId, String answer, AgentResult.FailureCategory category) {
        fireCompletionEvent(job, executionId, answer, category, null, null);
    }

    private void fireCompletionEvent(
            AgentCron job,
            String executionId,
            String answer,
            AgentResult.FailureCategory category,
            String cronSessionId,
            String toolName) {
        fireCompletionEvent(job, executionId, answer, category, cronSessionId, toolName, null);
    }

    private void fireCompletionEvent(
            AgentCron job,
            String executionId,
            String answer,
            AgentResult.FailureCategory category,
            String cronSessionId,
            String toolName,
            String displayToolParamsJson) {
        Optional<ChannelType> resolvedType = ChannelType.fromString(job.getChannelType());
        if (resolvedType.isEmpty()) {
            log.warnf(
                    "CRON-NOTIFY cronId=%s has null/unknown channelType='%s'. "
                            + "Cannot notify - see Architecture_Invariants.md §1.4. "
                            + "Run migration: UPDATE agent_cron SET channel_type='TELEGRAM' "
                            + "WHERE channel_type IS NULL;",
                    job.getId(), job.getChannelType());
            String legacySessionId = AgentSentinels.CRON_SESSION_PREFIX + job.getId();
            auditService.record(
                    legacySessionId,
                    job.getUserId(),
                    auditRole(job),
                    SecurityEventType.LEGACY_CHANNEL_FALLBACK,
                    "cronId=" + job.getId() + " channelType=" + job.getChannelType());
            return;
        }
        ChannelType chType = resolvedType.get();
        String platformId = job.getChannelPlatformId();
        if (platformId == null || platformId.isBlank()) {
            platformId = job.getChatId();
        }
        if (platformId == null || platformId.isBlank()) {
            log.warnf(
                    "CRON-NOTIFY cronId=%s has no channel platform or chat ID; skipping notification safely",
                    job.getId());
            return;
        }

        try {
            jobCompletedEvent.fire(new AgentJobCompleted(
                    job.getId(),
                    job.getUserId(),
                    chType,
                    platformId,
                    executionId,
                    answer,
                    category,
                    cronSessionId,
                    toolName,
                    displayToolParamsJson));
        } catch (Exception e) {
            log.warnf("CRON-EVENT-FIRE-FAIL cronId=%s:\n%s", job.getId(), LogUtil.trimmedStack(e));
        }
    }
}
