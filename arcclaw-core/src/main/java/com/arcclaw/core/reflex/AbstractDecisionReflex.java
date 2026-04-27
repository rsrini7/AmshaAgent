package com.arcclaw.core.reflex;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.CheckpointResolutionException;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Base class for reflexes that make a decision on a pending tool (Approve or Deny).
 * Consolidates common logic for delegation, cron job resolution, and HITL ambiguity handling.
 */
public abstract class AbstractDecisionReflex implements AgentReflex {

    @Inject protected Logger log;
    @Inject protected StatefulAgentStore statefulAgentStore;
    @Inject protected AgentCronRepository cronRepository;
    @Inject protected AgentLoop agentLoop;
    @Inject protected ReasoningEngine reasoningEngine;
    @Inject protected Event<AgentJobCompleted> jobCompletedEvent;
    @Inject protected AuditService auditService;
    @Inject protected ObjectMapper objectMapper;
    @Inject protected CheckpointResumeHandler checkpointResumeHandler;
    @Inject protected DelegateApprovalGuard delegateGuard;
    @Inject protected CheckpointApprovalService checkpointApprovalService;
    @Inject protected CliResultBridge cliResultBridge;
    @Inject protected WorkspaceManager workspaceManager;

    protected abstract String getActionName();
    protected abstract String getAmbiguityTitle();
    protected abstract String getNoPendingMessage();
    protected abstract String getErrorMessage();
    protected abstract String getNotAuthorizedMessage();
    protected abstract String getTargetNotAwaitingMessage();

    protected abstract AgentCheckpoint applyDecisionToTool(String sessionId, String toolName, String reason);
    protected abstract AgentCheckpoint applyDecisionToAll(String sessionIdOrCronId, String reason);
    protected abstract void postProcessCronDecision(AgentCronEntity cron, String reason, AgentResult result);
    protected abstract String getCronResumeGoal(String cronSessionId, String cronRole);

    protected abstract String getAlreadyProcessingMessage();
    protected abstract String getSingleCronFallbackMessage(AgentCronEntity cron);
    protected abstract String getMultipleCronFallbackMessage(String list);
    protected abstract String getCronSuccessResponse(AgentResult result);
    protected abstract String getFailedRetryMessage();
    protected abstract String getPerToolSuccessResponse(String toolName);

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        ChannelCommand cmd = ChannelCommand.parse(input);
        DecisionParams d = extractDecisionParams(cmd);
        if (d == null) return ReflexResult.forward(context);

        String reason = d.reasonOpt.orElse("CLI " + getActionName().toLowerCase());

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                if (d.targetSid.isPresent()) return handleDelegatePath(d.targetSid.get(), userId, chatId, reason, context);
                if (d.cronId.isPresent()) return handleCronDecision(d.cronId.get(), userId, chatId, reason, context);
                if (d.toolName.isPresent()) return handlePerToolDecision(d.toolName.get(), sessionId, userId, chatId, reason, context);

                Optional<AgentCheckpoint> latestPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
                List<AgentCronEntity> pendingCrons = cronRepository.findAwaitingApprovalByUserId(userId);
                
                String ambiguity = handleAmbiguityDetection(latestPending, pendingCrons);
                if (ambiguity != null) return ambiguity;

                String sessionDecision = handleActiveSessionDecision(sessionId, userId, chatId, reason, context, latestPending);
                if (sessionDecision != null) return sessionDecision;

                String retryGuide = handleFailedCheckpointRetry(sessionId);
                if (retryGuide != null) return retryGuide;

                if (!pendingCrons.isEmpty()) return handleCronFallbackGuidance(pendingCrons);

                if (!statefulAgentStore.isSessionActive(sessionId)) {
                    log.debugf("%sReflex: session %s already completed", getActionName(), sessionId);
                    return getNoPendingMessage();
                }

                return pollForBridgeResult(sessionId);
            });
            return ReflexResult.respond(response, context);
        } catch (Exception e) {
            log.errorf("Failed to process %s for session %s: %s", getActionName(), sessionId, ExceptionUtils.getRootCauseMessage(e));
            return ReflexResult.respond(getErrorMessage() + ": " + ExceptionUtils.getRootCauseMessage(e), context);
        }
    }

    private record DecisionParams(Optional<String> cronId, Optional<String> targetSid, Optional<String> toolName, Optional<String> reasonOpt) {}

    private DecisionParams extractDecisionParams(ChannelCommand cmd) {
        if (cmd instanceof ChannelCommand.Approve a) {
            return new DecisionParams(a.cronId(), a.targetSessionId(), a.toolName(), a.reason());
        } else if (cmd instanceof ChannelCommand.Deny d) {
            return new DecisionParams(d.cronId(), d.targetSessionId(), d.toolName(), d.reason());
        }
        return null;
    }

    private String handleDelegatePath(String targetSid, String userId, String chatId, String reason, MessageContext context) throws Exception {
        if (!delegateGuard.isDelegate(userId)) {
            log.warnf("[%s-DELEGATE-DENIED] admin=%s attempted decision for session=%s",
                    getActionName(), userId, targetSid);
            return getNotAuthorizedMessage();
        }
        return handleDelegateDecision(targetSid, userId, chatId, reason, context);
    }

    private String handleAmbiguityDetection(Optional<AgentCheckpoint> latestPending, List<AgentCronEntity> pendingCrons) {
        if (latestPending.isPresent() && !pendingCrons.isEmpty()) {
            StringBuilder sb = new StringBuilder("⚠️ ").append(getAmbiguityTitle()).append(". You have multiple tasks waiting:\n\n");
            sb.append("• `/%s` - (This Chat) ".formatted(getActionName().toLowerCase()))
              .append(latestPending.get().getOriginalGoal()).append("\n");
            for (AgentCronEntity c : pendingCrons) {
                sb.append("• `/%s %s` - (Background) ".formatted(getActionName().toLowerCase(), c.id))
                  .append(c.goal).append("\n");
            }
            sb.append("\nPlease reply with the specific reflex.");
            return sb.toString();
        }
        return null;
    }

    private String handleActiveSessionDecision(String sessionId, String userId, String chatId, String reason, MessageContext context, Optional<AgentCheckpoint> latestPending) {
        String activeRole = latestPending.map(AgentCheckpoint::getAgentRole).orElse(null);
        if (activeRole != null) {
            try {
                log.infof("[%sReflex] User processed pending decision for session: %s", getActionName(), sessionId);
                List<String> pending = BatchDecisionUtil.pendingToolNames(latestPending.get(), objectMapper);
                AgentCheckpoint cp = (pending.size() == 1) 
                        ? applyDecisionToTool(sessionId, pending.get(0), reason)
                        : applyDecisionToAll(sessionId, reason);
                
                if (ChannelType.CLI.id().equalsIgnoreCase(context.getUser().channel().id())) {
                    return runSynchronousDecision(sessionId, activeRole, userId, chatId);
                } else {
                    checkpointApprovalService.triggerResume(cp);
                    return "";
                }
            } catch (CheckpointResolutionException e) {
                return getNoPendingMessage();
            }
        }
        return null;
    }

    private String handleFailedCheckpointRetry(String sessionId) {
        String failedRetryMsg = getFailedRetryMessage();
        if (failedRetryMsg != null) {
            Optional<AgentCheckpoint> lastCp = statefulAgentStore.findLatestCheckpoint(sessionId);
            if (lastCp.isPresent() && lastCp.get().getStatus() == CheckpointStatus.FAILED) {
                log.debugf("%sReflex: last checkpoint is FAILED for session %s - guiding user to retry", 
                        getActionName(), sessionId);
                return failedRetryMsg;
            }
        }
        return null;
    }

    private String handleCronFallbackGuidance(List<AgentCronEntity> pendingCrons) {
        if (pendingCrons.size() == 1) {
            return getSingleCronFallbackMessage(pendingCrons.getFirst());
        }
        String list = pendingCrons.stream()
                .map(c -> "• `/%s %s` - %s".formatted(getActionName().toLowerCase(), c.id, c.goal))
                .collect(Collectors.joining("\n"));
        return getMultipleCronFallbackMessage(list);
    }

    private String handleDelegateDecision(String targetSid, String adminId, String adminChatId, String reason, MessageContext context) throws Exception {
        log.infof("[DELEGATE-%s] admin=%s decision for session=%s", getActionName(), adminId, targetSid);
        
        Optional<AgentCheckpoint> latestPending = statefulAgentStore.findLatestAwaitingApproval(targetSid);
        String activeRole = latestPending.map(AgentCheckpoint::getAgentRole).orElse(null);

        if (activeRole == null) return getTargetNotAwaitingMessage();

        AgentCheckpoint cp = latestPending.get();
        if (cp.hasBatchToolDecisions()) {
            applyDecisionToAll(targetSid, reason);
        }

        String auditMsg = "Admin %s performed %s on session %s: %s".formatted(adminId, getActionName(), targetSid, reason);
        auditService.logSecurityEvent(SecurityEventType.DELEGATE_APPROVAL, adminId, auditMsg);

        checkpointResumeHandler.resumeAfterBatchDecision(targetSid, activeRole, adminId, adminChatId, context.getUser().channel().name());
        return "Resuming task...";
    }

    private String handleCronDecision(String cronId, String userId, String chatId, String reason, MessageContext context) throws Exception {
        AgentCheckpoint pendingCheckpoint = applyDecisionToAll(cronId, reason);
        String cronSessionId = pendingCheckpoint.getSessionId();
        String cronRole = pendingCheckpoint.getAgentRole();

        String resolvedJobId = cronId;
        if (cronId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            String suffix = cronId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
            if (suffix.length() >= 36) resolvedJobId = suffix.substring(0, 36);
        }

        AgentCronEntity cron = cronRepository.findById(resolvedJobId);
        if (cron == null) return "❌ Cron task not found.";

        if (!statefulAgentStore.markBatchResuming(cronSessionId, cronRole)) return getAlreadyProcessingMessage();

        AgentResult result = null;
        try {
            String goal = getCronResumeGoal(cronSessionId, cronRole);
            context.setResumeSignal(true);
            context.setGoal(goal);

            int budget = pendingCheckpoint.getMaxIterations() != null ? Math.max(3, pendingCheckpoint.getMaxIterations()) : 3;

            result = WorkspaceContext.run(cronSessionId, cron.userId, cron.chatId, AutonomyLevel.BACKGROUND, cronRole,
                    () -> agentLoop.run(cronSessionId, cronRole, goal, pendingCheckpoint.getSystemNote(), reasoningEngine, budget, context));

            if (result == null) {
                log.warnf("[handleCronDecision] Agent loop returned null result for cron session %s", cronSessionId);
                return getErrorMessage();
            }

            postProcessCronDecision(cron, reason, result);
            notifyJobCompleted(cron, result);

            return getCronSuccessResponse(result);
        } finally {
            statefulAgentStore.clearBatchResuming(cronSessionId);
            if (result != null && isTerminalResult(result)) {
                try {
                    workspaceManager.terminateSession(cronSessionId);
                } catch (Exception e) {
                    log.warn("Termination failed: " + ExceptionUtils.getRootCauseMessage(e));
                }
            }
        }
    }

    private String handlePerToolDecision(String toolName, String sessionId, String userId, String chatId, String reason, MessageContext context) throws Exception {
        Optional<AgentCheckpoint> latestPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
        String activeRole = latestPending.filter(AgentCheckpoint::hasBatchToolDecisions).map(AgentCheckpoint::getAgentRole).orElse(null);

        if (activeRole == null) return "⚠️ No parallel batch awaiting decision.";

        try {
            AgentCheckpoint cp = applyDecisionToTool(sessionId, toolName, reason);
            checkpointApprovalService.triggerResume(cp);
            return getPerToolSuccessResponse(toolName);
        } catch (CheckpointResolutionException e) {
            return getNoPendingMessage();
        }
    }

    private String runSynchronousDecision(String sessionId, String activeRole, String userId, String chatId) {
        cliResultBridge.register(sessionId);
        try {
            AgentResult result = checkpointResumeHandler.resumeAfterBatchDecisionSync(sessionId, activeRole, userId, chatId, "cli");
            if (result == null) result = cliResultBridge.waitForResult(sessionId, 10000);
            return result != null ? AgentResultFormatter.format(result) : "⏱ Timed out waiting for session.";
        } catch (Exception e) {
            return "❌ Resume failed: " + e.getMessage();
        } finally {
            cliResultBridge.cancel(sessionId);
        }
    }

    private String pollForBridgeResult(String sessionId) {
        cliResultBridge.register(sessionId);
        try {
            AgentResult bridgeResult = cliResultBridge.waitForResult(sessionId, 200);
            return bridgeResult != null ? AgentResultFormatter.format(bridgeResult) : getNoPendingMessage();
        } finally {
            cliResultBridge.cancel(sessionId);
        }
    }

    private void notifyJobCompleted(AgentCronEntity cron, AgentResult result) {
        ChannelType chType = ChannelType.fromString(cron.channelType).orElse(ChannelType.TELEGRAM);
        String platformId = cron.channelPlatformId != null ? cron.channelPlatformId : cron.chatId;

        jobCompletedEvent.fire(new AgentJobCompleted(
                cron.id, cron.userId, chType, platformId, cron.lastDeliveryId,
                AgentResultFormatter.format(result),
                result instanceof AgentResult.Failed f ? f.category() : null));
    }

    private boolean isTerminalResult(AgentResult result) {
        return result instanceof AgentResult.Finished || result instanceof AgentResult.StructuredFinished 
            || result instanceof AgentResult.Failed || result instanceof AgentResult.LoopExceeded;
    }
}
