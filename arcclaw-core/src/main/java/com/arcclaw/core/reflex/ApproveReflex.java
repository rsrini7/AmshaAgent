package com.arcclaw.core.reflex;

import static com.arcclaw.core.message.UserMessages.*;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.util.CronScheduleUtil;

@ApplicationScoped
@ReflexPriority(100)
public class ApproveReflex extends AbstractDecisionReflex {

    @Override public String getActionName() { return "APPROVE"; }
    @Override public String getAmbiguityTitle() { return "Ambiguous approval"; }
    @Override public String getNoPendingMessage() { return NO_PENDING_ANYWHERE; }
    @Override public String getErrorMessage() { return APPROVAL_ERROR; }
    @Override public String getNotAuthorizedMessage() { return NOT_AUTHORIZED_DELEGATION; }
    @Override public String getTargetNotAwaitingMessage() { return TARGET_SESSION_NOT_AWAITING; }

    @Override public String getAlreadyProcessingMessage() { return ALREADY_PROCESSING_APPROVAL; }
    @Override public String getSingleCronFallbackMessage(AgentCron cron) { return cronSingleAwaitingGuide(cron.getGoal(), cron.getId()); }
    @Override public String getMultipleCronFallbackMessage(String list) { return cronMultipleAwaiting(list); }
    @Override public String getCronSuccessResponse(AgentResult result) { return ""; }
    @Override public String getFailedRetryMessage() {
        return "My last attempt failed before it could be approved (likely due to a rate limit). "
             + "Please resend your original message to retry - I'll ask for approval again.";
    }
    @Override public String getPerToolSuccessResponse(String toolName) { return ""; }

    @Override
    public boolean matches(String input) {
        return ChannelCommand.parse(input) instanceof ChannelCommand.Approve;
    }

    @Override public SecurityEventType auditEventType() { return SecurityEventType.APPROVAL_GRANTED; }
    @Override public String auditDetail(String sessionId, String input) { return "action=APPROVE session=" + sessionId; }

    @Override
    protected AgentCheckpoint applyDecisionToTool(String sessionId, String toolName, String reason) {
        return checkpointApprovalService.approveTool(sessionId, toolName, reason);
    }

    @Override
    protected AgentCheckpoint applyDecisionToAll(String idOrSid, String reason) {
        return checkpointApprovalService.approveAll(idOrSid, reason);
    }

    @Override
    protected String getCronResumeGoal(String cronSessionId, String cronRole) {
        return checkpointResumeHandler.resolveResumeGoal(cronSessionId, cronRole);
    }

    @Override
    protected void postProcessCronDecision(AgentCron cron, String reason, AgentResult result) {
        // §Direct Cron Path: handleCronDecision runs synchronously and never reaches
        // CheckpointResumeHandler.dispatchResumeResult(). This is the only place
        // terminal state is set for direct /approve <cronId> commands.
        // syncCronTerminalStatus in CheckpointResumeHandler covers the async delegation path.
        switch (result) {
            case AgentResult.Finished _ -> {
                if (cron.isRunOnce()) cronStore.markCompleted(cron.getId(), Instant.now());
                else cronStore.markFired(cron.getId(), Instant.now(), CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.StructuredFinished _ -> {
                if (cron.isRunOnce()) cronStore.markCompleted(cron.getId(), Instant.now());
                else cronStore.markFired(cron.getId(), Instant.now(), CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.Failed f  -> cronStore.markFailed(cron.getId(), f.reason());
            case AgentResult.AwaitingApproval _ -> cronStore.markAwaitingApproval(cron.getId());
            case AgentResult.AwaitingRetry _    -> {} // No-op - retry handled by RateLimitRetryScheduler
            case AgentResult.LoopExceeded _ -> cronStore.markFailed(cron.getId(), "Loop exceeded");
            default -> {}
        }
    }
}
