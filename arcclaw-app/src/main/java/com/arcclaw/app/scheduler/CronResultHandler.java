package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.event.FlushQueuedNotifications;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.core.util.CronScheduleUtil;
import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.CronRunStorePort;
import com.arcclaw.persistence.api.CronStorePort;
import org.jboss.logging.Logger;

/**
 * Handles the outcomes of cron job executions: updates database state,
 * records audit events, fires telemetry events, and manages workspace sessions.
 */
@ApplicationScoped
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
    public CronResultHandler(CronStorePort cronStore,
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

    public void handleSuccess(AgentCron job, String cronSessionId, String executionId, AgentResult result) {
        if (result instanceof AgentResult.AwaitingApproval a) {
            handleApprovalPause(job, cronSessionId, executionId, a);
        } else if (result instanceof AgentResult.AwaitingRetry) {
            updateRunStatus(executionId, "AWAITING_RETRY", null, result);
            log.infof("CronResultHandler: Session kept alive for cron=%s AWAITING_RETRY (rate limit).", cronSessionId);
        } else {
            handleNormalCompletion(job, cronSessionId, executionId, result);
        }
    }

    private void updateRunStatus(String executionId, String status, String errorReason, AgentResult result) {
        cronRunStore.findById(executionId).ifPresent(existing -> {
            int inputTokens = 0;
            int outputTokens = 0;
            if (result != null) {
                inputTokens = result.totalInputTokens();
                outputTokens = result.totalOutputTokens();
            }
            CronRunDto updated = new CronRunDto(
                existing.id(), existing.cronId(), existing.sessionId(), status,
                existing.startedAt(), Instant.now(), errorReason,
                existing.iterationsUsed(), inputTokens, outputTokens,
                existing.usdCost(), existing.createdAt()
            );
            cronRunStore.save(updated);
        });
    }

    // Handles Finished, Failed, LoopExceeded - always terminates session
    private void handleNormalCompletion(AgentCron job, String cronSessionId, String executionId, AgentResult result) {
        String answer = AgentResultFormatter.format(result);
        String finalAnswer = answer.trim().isEmpty()
                ? "⚠️ Task executed, but the agent returned an empty response for goal: " + job.getGoal()
                : answer;

        if (result instanceof AgentResult.Finished) {
            updateRunStatus(executionId, "COMPLETED", null, result);
        } else {
            updateRunStatus(executionId, "FAILED", answer, result);
        }

        handleFinalResult(job, cronSessionId, result);

        fireCompletionEvent(job, executionId, finalAnswer, null);
        workspaceManager.terminateSession(cronSessionId);
    }

    // NOTE: In-memory queue for serializing CRON HITL alerts against live approvals.
    // This is not durable across server restarts, but old approvals are eventually
    // auto-reaped by the 24h background task cleanup.
    public static final ConcurrentHashMap<String, List<Runnable>> queuedNotifications =
        new ConcurrentHashMap<>();

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
    private void handleApprovalPause(AgentCron job, String cronSessionId, String executionId, AgentResult.AwaitingApproval result) {
        updateRunStatus(executionId, "AWAITING_APPROVAL", null, null);
        cronStore.markAwaitingApproval(job.getId());
        log.infof("CRON-HITL cronId=%s awaiting user approval of tool=%s",
                job.getId(), result.toolName());

        auditService.record(cronSessionId, job.getUserId(),
            AgentRoleEnum.COORDINATOR.toScopedName(),
            SecurityEventType.LOOP_AWAITING_APPROVAL,
            "cronId=" + job.getId() + " pendingTool=" + result.toolName());

        String displayGoal = job.getGoal() != null ? job.getGoal().replaceFirst("^CRON\\s+", "") : "";
        String answer = AgentResultFormatter.formatCronHitl(job.getId(), displayGoal, result.toolName());
        
        Runnable notifyTask = () -> {
            fireCompletionEvent(job, executionId, answer, null, cronSessionId, result.toolName());
            log.infof("[CronResultHandler] Session kept alive for cron=%s - AWAITING_APPROVAL. (Notification handled here)", cronSessionId);
        };

        if (statefulAgentStore != null && statefulAgentStore.hasPendingApprovalForChat(job.getChatId())) {
            queuedNotifications.computeIfAbsent(job.getChatId(), k -> new CopyOnWriteArrayList<>()).add(notifyTask);
            log.infof("CRON-HITL: chat %s busy with live session approval, queuing CRON approval", job.getChatId());
        } else {
            notifyTask.run();
        }
    }

    public void handleFailure(AgentCron job, String cronSessionId, String executionId, Throwable t) {
        updateRunStatus(executionId, "FAILED", t.getMessage(), null);
        cronStore.markFailed(job.getId(), t.getMessage());
        
        auditService.record(cronSessionId, job.getUserId(),
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONFAILED,
                "cronId=" + job.getId() + " reason=" + t.getMessage());

        if (t.getMessage() != null && t.getMessage().contains("[TEST-EXPECTED]")) {
            log.warnf("CRON-FAIL (EXPECTED) cronId=%s: %s", job.getId(), LogUtil.rootMessage(t));
        } else {
            log.warnf(t, "CRON-FAIL cronId=%s", job.getId());
        }
        
        String errorMessage = "❌ Scheduled task failed.\n\nReason: " + AgentErrorMessageUtil.sanitizeErrorMessage(t.getMessage());
        fireCompletionEvent(job, executionId, errorMessage, AgentResult.FailureCategory.SYSTEM_ERROR);

        workspaceManager.terminateSession(cronSessionId);
    }

    private void handleFinalResult(AgentCron job, String cronSessionId, AgentResult result) {
        if (result instanceof AgentResult.Finished) {
            if (job.isRunOnce()) {
                cronStore.markCompleted(job.getId(), Instant.now());
                auditService.record(cronSessionId, job.getUserId(),
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONFIRED,
                        "cronId=" + job.getId() + " runOnce=true status=COMPLETED");
                log.infof("CRON-COMPLETED one-shot cronId=%s", job.getId());
            } else {
                Instant next = CronScheduleUtil.computeNext(job.getCronExpression(), job.getTimezone());
                cronStore.markFired(job.getId(), Instant.now(), next);
                auditService.record(cronSessionId, job.getUserId(),
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONFIRED,
                        "cronId=" + job.getId() + " runOnce=false nextFire=" + next);
                log.infof("CRON-FIRED cronId=%s nextFire=%s", job.getId(), next);
            }
        } else {
            if (!job.isRunOnce() && job.getCronExpression() != null && !job.getCronExpression().isBlank()) {
                // Recurring job failed — reschedule instead of permanent failure.
                // Free-tier LLMs fail intermittently; marking permanently FAILED
                // would kill the recurring schedule after the first transient error.
                Instant next = CronScheduleUtil.computeNext(job.getCronExpression(), job.getTimezone());
                cronStore.markFired(job.getId(), Instant.now(), next);
                log.warnf("CRON-RETRY recurring cronId=%s failed (will retry at %s): %s",
                        job.getId(), next, AgentResultFormatter.format(result));
                auditService.record(cronSessionId, job.getUserId(),
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONFAILED,
                        "cronId=" + job.getId() + " status=RETRY nextFire=" + next
                        + " result=" + result.getClass().getSimpleName());
            } else {
                cronStore.markFailed(job.getId(), AgentResultFormatter.format(result));
                auditService.record(cronSessionId, job.getUserId(),
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONFAILED,
                        "cronId=" + job.getId() + " status=FAILED result=" + result.getClass().getSimpleName());
            }
        }
    }

    private void fireCompletionEvent(AgentCron job, String executionId, String answer, AgentResult.FailureCategory category) {
        fireCompletionEvent(job, executionId, answer, category, null, null);
    }

    private void fireCompletionEvent(AgentCron job, String executionId, String answer, AgentResult.FailureCategory category, String cronSessionId, String toolName) {
        Optional<ChannelType> resolvedType = ChannelType.fromString(job.getChannelType());
        if (resolvedType.isEmpty()) {
            log.warnf("CRON-NOTIFY cronId=%s has null/unknown channelType='%s'. " +
                       "Cannot notify - see Architecture_Invariants.md §1.4. " +
                       "Run migration: UPDATE agent_cron SET channel_type='TELEGRAM' " +
                       "WHERE channel_type IS NULL;",
                       job.getId(), job.getChannelType());
            String legacySessionId = AgentSentinels.CRON_SESSION_PREFIX + job.getId();
            auditService.record(legacySessionId, job.getUserId(),
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.LEGACY_CHANNEL_FALLBACK,
                "cronId=" + job.getId() + " channelType=" + job.getChannelType());
            return;
        }
        ChannelType chType = resolvedType.get();
        String platformId = job.getChannelPlatformId() != null ? job.getChannelPlatformId() : job.getChatId();

        try {
            jobCompletedEvent.fire(new AgentJobCompleted(
                job.getId(), job.getUserId(), chType, platformId, executionId,
                answer, category, cronSessionId, toolName));
        } catch (Exception e) {
            log.warnf("CRON-EVENT-FIRE-FAIL cronId=%s:\n%s", job.getId(), LogUtil.trimmedStack(e));
        }
    }
}
