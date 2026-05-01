package com.arcclaw.core.reflex;

import static com.arcclaw.core.message.UserMessages.*;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
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
    @Override public String getSingleCronFallbackMessage(AgentCronEntity cron) { return cronSingleAwaitingGuide(cron.goal, cron.id); }
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
    protected void postProcessCronDecision(AgentCronEntity cron, String reason, AgentResult result) {
        // §Direct Cron Path: handleCronDecision runs synchronously and never reaches
        // CheckpointResumeHandler.dispatchResumeResult(). This is the only place
        // terminal state is set for direct /approve <cronId> commands.
        // syncCronTerminalStatus in CheckpointResumeHandler covers the async delegation path.
        switch (result) {
            case AgentResult.Finished _ -> {
                if (cron.runOnce) cronRepository.markCompleted(cron.id, Instant.now());
                else cronRepository.markFired(cron.id, Instant.now(), CronScheduleUtil.computeNext(cron.cronExpression, cron.timezone));
            }
            case AgentResult.StructuredFinished _ -> {
                if (cron.runOnce) cronRepository.markCompleted(cron.id, Instant.now());
                else cronRepository.markFired(cron.id, Instant.now(), CronScheduleUtil.computeNext(cron.cronExpression, cron.timezone));
            }
            case AgentResult.Failed f  -> cronRepository.markFailed(cron.id, f.reason());
            case AgentResult.AwaitingApproval _ -> cronRepository.markAwaitingApproval(cron.id);
            case AgentResult.AwaitingRetry _    -> {} // No-op - retry handled by RateLimitRetryScheduler
            case AgentResult.LoopExceeded _     -> cronRepository.markFailed(cron.id, "Loop exceeded");
            default -> {}
        }
    }
}
