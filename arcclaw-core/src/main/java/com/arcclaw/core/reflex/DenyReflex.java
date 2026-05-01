package com.arcclaw.core.reflex;

import static com.arcclaw.core.message.UserMessages.*;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.util.AgentResultFormatter;

@ApplicationScoped
@ReflexPriority(200)
public class DenyReflex extends AbstractDecisionReflex {

    @Override public String getActionName() { return "DENY"; }
    @Override public String getAmbiguityTitle() { return "Ambiguous denial"; }
    @Override public String getNoPendingMessage() { return NO_PENDING_ANYWHERE_SESSION; }
    @Override public String getErrorMessage() { return DENIAL_ERROR; }
    @Override public String getNotAuthorizedMessage() { return NOT_AUTHORIZED_DELEGATION; }
    @Override public String getTargetNotAwaitingMessage() { return TARGET_SESSION_NOT_AWAITING; }

    @Override public String getAlreadyProcessingMessage() { return ALREADY_PROCESSING_DENIAL; }
    @Override protected String getSingleCronFallbackMessage(AgentCronEntity cron) { 
        return "⚠️ No active denial in this chat session.\n\nA background task is waiting:\n• " + cron.goal + 
               "\n\nUse: `/deny " + cron.id + "` to cancel it.";
    }
    @Override public String getMultipleCronFallbackMessage(String list) { return cronMultipleAwaiting(list); }
    @Override public String getCronSuccessResponse(AgentResult result) { return AgentResultFormatter.format(result); }
    @Override public String getFailedRetryMessage() { return null; }
    @Override public String getPerToolSuccessResponse(String toolName) { return UserMessages.toolDenied(toolName); }

    @Override
    public boolean matches(String input) {
        return ChannelCommand.parse(input) instanceof ChannelCommand.Deny;
    }

    @Override public SecurityEventType auditEventType() { return SecurityEventType.APPROVAL_DENIED; }
    @Override public String auditDetail(String sessionId, String input) { return "action=DENY session=" + sessionId; }

    @Override
    protected AgentCheckpoint applyDecisionToTool(String sessionId, String toolName, String reason) {
        return checkpointApprovalService.denyTool(sessionId, toolName, reason);
    }

    @Override
    protected AgentCheckpoint applyDecisionToAll(String idOrSid, String reason) {
        return checkpointApprovalService.denyAll(idOrSid, reason);
    }

    @Override
    protected String getCronResumeGoal(String cronSessionId, String cronRole) {
        return AgentSentinels.GOAL_DENY_APPROVED;
    }

    @Override
    protected void postProcessCronDecision(AgentCronEntity cron, String reason, AgentResult result) {
        // §Direct Cron Path: handleCronDecision runs synchronously. This preserves
        // the user's /deny <reason> text as the failure message — markTerminal()
        // would lose it since it uses AgentResult.Failed.reason() instead.
        if (result instanceof AgentResult.AwaitingRetry) {
            return; // No-op - retry handled by RateLimitRetryScheduler (#736)
        }
        cronRepository.markFailed(cron.id, reason != null ? reason : "User denied tool execution.");
    }
}
