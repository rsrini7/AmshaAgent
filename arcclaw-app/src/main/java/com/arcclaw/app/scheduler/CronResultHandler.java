package com.arcclaw.app.scheduler;

import java.time.Instant;

import org.jboss.logging.Logger;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.CronResultFormatter;
import com.arcclaw.core.util.CronScheduleUtil;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Handles the outcomes of cron job executions: updates database state,
 * records audit events, fires telemetry events, and manages workspace sessions.
 */
@ApplicationScoped
public class CronResultHandler {

    private static final Logger log = Logger.getLogger(CronResultHandler.class);

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AuditService auditService;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    Event<AgentJobCompleted> jobCompletedEvent;

    public void handleSuccess(AgentCronEntity job, String cronSessionId, String executionId, AgentResult result) {
        if (result instanceof AgentResult.AwaitingApproval a) {
            handleApprovalPause(job, cronSessionId, executionId, a);
        } else {
            handleNormalCompletion(job, cronSessionId, executionId, result);
        }
    }

    // Handles Finished, Failed, LoopExceeded — always terminates session
    private void handleNormalCompletion(AgentCronEntity job, String cronSessionId, String executionId, AgentResult result) {
        String answer = CronResultFormatter.format(result);
        String finalAnswer = answer.trim().isEmpty()
                ? "⚠️ Task executed, but the agent returned an empty response for goal: " + job.goal
                : answer;

        handleFinalResult(job, cronSessionId, result);

        // Fire delivery event BEFORE session teardown — observer needs session context
        fireCompletionEvent(job, executionId, finalAnswer, null);
        workspaceManager.terminateSession(cronSessionId);
    }

    // Handles AwaitingApproval — session must stay alive for ApproveReflex
    private void handleApprovalPause(AgentCronEntity job, String cronSessionId, String executionId, AgentResult.AwaitingApproval result) {
        cronRepository.markAwaitingApproval(job.id);
        log.infof("CRON-HITL cronId=%s awaiting user approval of tool=%s",
                job.id, result.toolName());

        // audit the HITL pause event
        auditService.record(cronSessionId, job.userId,
            AgentRoleEnum.COORDINATOR.toScopedName(),
            SecurityEventType.LOOP_AWAITING_APPROVAL,
            "cronId=" + job.id + " pendingTool=" + result.toolName());

        String answer = CronResultFormatter.formatCronHitl(job.id, job.goal, result.toolName());
        // Fire delivery event — session intentionally kept alive for approval
        fireCompletionEvent(job, executionId, answer, null);

        log.infof("[CronResultHandler] Session kept alive for cron=%s — AWAITING_APPROVAL.", cronSessionId);
    }

    public void handleFailure(AgentCronEntity job, String cronSessionId, String executionId, Throwable t) {
        cronRepository.markFailed(job.id, t.getMessage());
        
        auditService.record(cronSessionId, job.userId,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONFAILED,
                "cronId=" + job.id + " reason=" + t.getMessage());

        // Avoid log pollution for expected test failures
        if (t.getMessage() != null && t.getMessage().contains("[TEST-EXPECTED]")) {
            log.warnf("CRON-FAIL (EXPECTED) cronId=%s: %s", job.id, t.getMessage());
        } else {
            log.warnf(t, "CRON-FAIL cronId=%s", job.id);
        }
        
        String errorMessage = "❌ Scheduled task failed.\n\nReason: " + AgentErrorMessageUtil.sanitizeErrorMessage(t.getMessage());
        fireCompletionEvent(job, executionId, errorMessage, AgentResult.FailureCategory.SYSTEM_ERROR);

        workspaceManager.terminateSession(cronSessionId);
    }

    private void handleFinalResult(AgentCronEntity job, String cronSessionId, AgentResult result) {
        if (result instanceof AgentResult.Finished) {
            if (job.runOnce) {
                cronRepository.markCompleted(job.id, Instant.now());
                auditService.record(cronSessionId, job.userId,
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONFIRED,
                        "cronId=" + job.id + " runOnce=true status=COMPLETED");
                log.infof("CRON-COMPLETED one-shot cronId=%s", job.id);
            } else {
                Instant next = CronScheduleUtil.computeNext(job.cronExpression, job.timezone);
                cronRepository.markFired(job.id, Instant.now(), next);
                auditService.record(cronSessionId, job.userId,
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        SecurityEventType.CRONFIRED,
                        "cronId=" + job.id + " runOnce=false nextFire=" + next);
                log.infof("CRON-FIRED cronId=%s nextFire=%s", job.id, next);
            }
        } else {
            // LoopExceeded or Failed variants
            cronRepository.markFailed(job.id, CronResultFormatter.format(result));
            auditService.record(cronSessionId, job.userId,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    SecurityEventType.CRONFAILED,
                    "cronId=" + job.id + " status=FAILED result=" + result.getClass().getSimpleName());
        }
    }

    private void fireCompletionEvent(AgentCronEntity job, String executionId, String answer, AgentResult.FailureCategory category) {
        ChannelType chType = ChannelType.fromString(job.channelType);
        String platformId = job.channelPlatformId != null ? job.channelPlatformId : job.chatId;

        try {
            jobCompletedEvent.fire(new AgentJobCompleted(
                job.id, job.userId, chType, platformId, executionId,
                answer, category));
        } catch (Exception e) {
            log.warnf(e, "CRON-EVENT-FIRE-FAIL cronId=%s", job.id);
        }
    }
}
