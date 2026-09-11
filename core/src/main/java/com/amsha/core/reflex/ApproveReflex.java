package com.amsha.core.reflex;

import static com.amsha.core.message.UserMessages.*;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.ChannelCommand;
import com.amsha.core.agent.loop.CronSessionStatusSync;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCron;

@ApplicationScoped
@ReflexPriority(100)
public class ApproveReflex extends AbstractDecisionReflex {

    @Override
    public String getActionName() {
        return "APPROVE";
    }

    @Override
    public String getAmbiguityTitle() {
        return "Ambiguous approval";
    }

    @Override
    public String getNoPendingMessage() {
        return NO_PENDING_ANYWHERE;
    }

    @Override
    public String getErrorMessage() {
        return APPROVAL_ERROR;
    }

    @Override
    public String getNotAuthorizedMessage() {
        return NOT_AUTHORIZED_DELEGATION;
    }

    @Override
    public String getTargetNotAwaitingMessage() {
        return TARGET_SESSION_NOT_AWAITING;
    }

    @Override
    public String getAlreadyProcessingMessage() {
        return ALREADY_PROCESSING_APPROVAL;
    }

    @Override
    public String getSingleCronFallbackMessage(AgentCron cron) {
        return cronSingleAwaitingGuide(cron.getGoal(), cron.getId());
    }

    @Override
    public String getMultipleCronFallbackMessage(String list) {
        return cronMultipleAwaiting(list);
    }

    /**
     * Returns an empty string because {@link CronDecisionRouter} already dispatches the execution result
     * to the user's channel via {@code ctx.jobNotifier().accept(cron, result)} (publishing an
     * {@code AgentJobCompleted} event consumed by {@code TelegramJobNotifier}).
     *
     * <p>Returning a non-empty formatted message here causes {@code CommandDispatchHandler} to send a
     * duplicate message to the user for the same approval. Blank responses are safely dropped by channel
     * senders (e.g. {@code TelegramChannelSender}). <b>DO NOT change this to return {@code AgentResultFormatter.format(result)}.</b>
     */
    @Override
    public String getCronSuccessResponse(AgentResult result) {
        return "";
    }

    @Override
    public String getFailedRetryMessage() {
        return "My last attempt failed before it could be approved (likely due to a rate limit). "
                + "Please resend your original message to retry - I'll ask for approval again.";
    }

    @Override
    public String getPerToolSuccessResponse(String toolName) {
        return toolApproved(toolName);
    }

    @Override
    public boolean matches(String input) {
        return ChannelCommand.parse(input) instanceof ChannelCommand.Approve;
    }

    @Override
    public SecurityEventType auditEventType() {
        return SecurityEventType.APPROVAL_GRANTED;
    }

    @Override
    public String auditDetail(String sessionId, String input) {
        return "action=APPROVE session=" + sessionId;
    }

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
        CronSessionStatusSync.applyTerminal(cron, cron.getLastDeliveryId(), result, cronStore);
    }
}
