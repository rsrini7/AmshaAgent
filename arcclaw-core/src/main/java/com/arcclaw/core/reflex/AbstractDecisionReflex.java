package com.arcclaw.core.reflex;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResolutionException;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.event.FlushQueuedNotifications;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Base class for reflexes that make a decision on a pending tool (Approve or Deny).
 * Consolidates common logic for delegation, cron job resolution, and HITL ambiguity handling.
 */
public abstract class AbstractDecisionReflex implements AgentReflex {

    public static final String MSG_RESUMING_TASK = "Resuming task...";

    @Inject protected Logger log;
    @Inject protected StatefulAgentStore statefulAgentStore;
    @Inject protected AgentCronRepository cronRepository;
    @Inject protected AgentLoop agentLoop;
    @Inject protected ReasoningEngine reasoningEngine;
    @Inject protected Event<AgentJobCompleted> jobCompletedEvent;
    @Inject protected Event<FlushQueuedNotifications> flushEvent;
    @Inject protected AuditService auditService;
    @Inject protected ObjectMapper objectMapper;
    @Inject protected CheckpointResumeHandler checkpointResumeHandler;
    @Inject protected DelegateApprovalGuard delegateGuard;
    @Inject protected CheckpointApprovalService checkpointApprovalService;
    @Inject protected CliResultBridge cliResultBridge;
    @Inject protected WorkspaceManager workspaceManager;
    @Inject CronDecisionRouter cronDecisionRouter;
    @Inject ResumeResultHelper resumeResultHelper;
    AmbiguityResolver ambiguityResolver;

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
        AmbiguityResolver ambiguityResolver = new AmbiguityResolver(getActionName(), getAmbiguityTitle());

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                if (d.targetId.isPresent()) {
                    return processExplicitTarget(d.targetId.get(), sessionId, userId, chatId, reason, context, ambiguityResolver);
                }
                if (d.toolName.isPresent()) return handlePerToolDecision(d.toolName.get(), sessionId, userId, chatId, reason, context);

                Optional<AgentCheckpoint> latestPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
                List<AgentCronEntity> pendingCrons = cronRepository.findAwaitingApprovalByUserId(userId);
                
                String ambiguity = ambiguityResolver.detect(latestPending, pendingCrons);
                if (ambiguity != null) return ambiguity;

                String sessionDecision = handleActiveSessionDecision(sessionId, userId, chatId, reason, context, latestPending);
                if (sessionDecision != null) return sessionDecision;

                String retryGuide = resumeResultHelper.handleFailedRetry(sessionId, getFailedRetryMessage());
                if (retryGuide != null) return retryGuide;

                if (!pendingCrons.isEmpty()) {
                    if (pendingCrons.size() == 1) return getSingleCronFallbackMessage(pendingCrons.getFirst());
                    String cronList = pendingCrons.stream()
                        .map(c -> "• `/%s %s` - %s".formatted(getActionName().toLowerCase(), c.id, c.goal))
                        .collect(Collectors.joining("\n"));
                    return getMultipleCronFallbackMessage(cronList);
                }

                if (!statefulAgentStore.isSessionActive(sessionId)) {
                    log.debugf("%sReflex: session %s already completed", getActionName(), sessionId);
                    return getNoPendingMessage();
                }

                return resumeResultHelper.pollBridge(sessionId);
            });
            try {
                if (flushEvent != null && !statefulAgentStore.hasPendingApprovalForChat(chatId)) {
                    flushEvent.fire(new FlushQueuedNotifications(chatId));
                }
            } catch (Exception ex) {
                log.warn("Failed to flush notifications", ex);
            }
            return response != null ? ReflexResult.respond(response, context) : ReflexResult.forward(context);
        } catch (Exception e) {
            log.errorf("Failed to process %s for session %s: %s", getActionName(), sessionId, ExceptionUtils.getRootCauseMessage(e));
            return ReflexResult.respond(getErrorMessage() + ": " + ExceptionUtils.getRootCauseMessage(e), context);
        }
    }

    private record DecisionParams(Optional<String> targetId, Optional<String> toolName, Optional<String> reasonOpt) {}

    private DecisionParams extractDecisionParams(ChannelCommand cmd) {
        if (cmd instanceof ChannelCommand.Approve a) {
            return new DecisionParams(a.targetId(), a.toolName(), a.reason());
        } else if (cmd instanceof ChannelCommand.Deny d) {
            return new DecisionParams(d.targetId(), d.toolName(), d.reason());
        }
        return null;
    }

    // handleDelegatePath removed — authorization gate is now inline in execute().
    // handleDelegateDecision is called directly after the delegate check passes.

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
                    return resumeResultHelper.runSync(sessionId, activeRole, userId, chatId);
                } else {
                    // §Resume-Fix: If applyDecisionToAll returned null (e.g. no batchToolDecisions),
                    // use the already-loaded checkpoint to trigger resume.
                    AgentCheckpoint resumeTarget = cp != null ? cp : latestPending.get();
                    log.infof("[%sReflex] Triggering resume for session %s (cp=%s, fallback=%s)",
                            getActionName(), sessionId, cp != null, cp == null);
                    checkpointApprovalService.triggerResume(resumeTarget);
                    return "";
                }
            } catch (CheckpointResolutionException e) {
                return getNoPendingMessage();
            }
        }
        return null;
    }

    private String handleDelegateDecision(String targetSid, String adminId, String adminChatId, String reason, MessageContext context) throws Exception {
        log.infof("[DELEGATE-%s] admin=%s decision for session=%s", getActionName(), adminId, targetSid);
        
        Optional<AgentCheckpoint> latestPending = statefulAgentStore.findLatestAwaitingApproval(targetSid);
        if (latestPending.isEmpty()) {
            return "Target session not found or has no pending approval.";
        }
        String activeRole = latestPending.get().getAgentRole();

        AgentCheckpoint cp = latestPending.get();
        if (cp.hasBatchToolDecisions()) {
            applyDecisionToAll(targetSid, reason);
        }

        String auditMsg = "Admin %s performed %s on session %s: %s".formatted(adminId, getActionName(), targetSid, reason);
        auditService.logSecurityEvent(SecurityEventType.DELEGATE_APPROVAL, adminId, auditMsg);

        checkpointResumeHandler.resumeAfterBatchDecision(targetSid, activeRole, adminId, adminChatId, context.getUser().channel().name());
        return MSG_RESUMING_TASK;
    }

    private String handlePerToolDecision(String toolName, String sessionId, String userId, String chatId, String reason, MessageContext context) throws Exception {
        Optional<AgentCheckpoint> latestPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
        String activeRole = latestPending.filter(AgentCheckpoint::hasBatchToolDecisions).map(AgentCheckpoint::getAgentRole).orElse(null);

        if (activeRole == null) return "⚠️ No parallel batch awaiting decision.";

        try {
            AgentCheckpoint cp = applyDecisionToTool(sessionId, toolName, reason);
            if (BatchDecisionUtil.pendingToolNames(cp, objectMapper).isEmpty()) {
                checkpointApprovalService.triggerResume(cp);
            }
            return getPerToolSuccessResponse(toolName);
        } catch (CheckpointResolutionException e) {
            return getNoPendingMessage();
        }
    }

    private String processExplicitTarget(String targetId, String sessionId, String userId, String chatId, String reason, MessageContext context, AmbiguityResolver ambiguityResolver) throws Exception {
        // Resolve numeric disambiguation index
        if (targetId.matches("\\d+")) {
            int idx = Integer.parseInt(targetId);
            Optional<AgentCheckpoint> selfPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
            List<AgentCronEntity> pendingCrons = cronRepository.findAwaitingApprovalByUserId(userId);
            
            String resolvedTargetId = null;
            if (selfPending.isPresent()) {
                if (idx == 1) {
                    resolvedTargetId = sessionId;
                } else if (idx >= 2 && idx - 2 < pendingCrons.size()) {
                    resolvedTargetId = pendingCrons.get(idx - 2).id;
                }
            } else {
                if (idx >= 1 && idx - 1 < pendingCrons.size()) {
                    resolvedTargetId = pendingCrons.get(idx - 1).id;
                }
            }
            if (resolvedTargetId != null) {
                targetId = resolvedTargetId;
            }
        }

        // Resolve prefix match for cron job IDs
        if (targetId.length() >= 4 && !targetId.contains("-")) {
            List<AgentCronEntity> pendingCrons = cronRepository.findAwaitingApprovalByUserId(userId);
            String prefix = targetId.toLowerCase();
            Optional<AgentCronEntity> matchedCron = pendingCrons.stream()
                .filter(c -> c.id.toLowerCase().startsWith(prefix))
                .findFirst();
            if (matchedCron.isPresent()) {
                targetId = matchedCron.get().id;
            }
        }

        // §Self-Session Fast-Path: if the user provided their own session ID,
        // treat it identically to a bare /approve — no delegate check needed.
        if (targetId.equals(sessionId)) {
            log.debugf("[%sReflex] targetId==sessionId (%s) — routing to own-session path",
                    getActionName(), sessionId);
            Optional<AgentCheckpoint> selfPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
            List<AgentCronEntity> pendingCrons = cronRepository.findAwaitingApprovalByUserId(userId);
            String ambiguity = ambiguityResolver.detect(selfPending, pendingCrons);
            if (ambiguity != null) return ambiguity;
            String selfDecision = handleActiveSessionDecision(sessionId, userId, chatId, reason, context, selfPending);
            if (selfDecision != null) return selfDecision;

            String retryGuide = resumeResultHelper.handleFailedRetry(sessionId, getFailedRetryMessage());
            if (retryGuide != null) return retryGuide;
            if (!pendingCrons.isEmpty()) {
                if (pendingCrons.size() == 1) return getSingleCronFallbackMessage(pendingCrons.getFirst());
                String cronList = pendingCrons.stream()
                    .map(c -> "• `/%s %s` - %s".formatted(getActionName().toLowerCase(), c.id, c.goal))
                    .collect(Collectors.joining("\n"));
                return getMultipleCronFallbackMessage(cronList);
            }

            // If we are in the self-session path but no pending session/cron/retry was found,
            // since an ID was explicitly provided, we should return a specific "not found" 
            // error rather than the generic "no pending in this chat" message.
            if (selfPending.isEmpty()) {
                 if (statefulAgentStore.isSessionActive(sessionId)) return getTargetNotAwaitingMessage();
                 return "Target session not found or has no pending approval.";
            }

            return getNoPendingMessage();
        }

        // Runtime disambiguation: probe session first, then cron.
        // 1. Session pending? → requires delegate role for cross-session approval
        Optional<AgentCheckpoint> targetPending = statefulAgentStore.findLatestAwaitingApproval(targetId);
        if (targetPending.isPresent()) {
            if (!delegateGuard.isDelegate(userId)) {
                log.warnf("[%s-DELEGATE-DENIED] user=%s attempted session decision for id=%s",
                        getActionName(), userId, targetId);
                return getNotAuthorizedMessage();
            }
            return handleDelegateDecision(targetId, userId, chatId, reason, context);
        }

        // 2. Not a session — try as cron task (owner can approve their own tasks)
        try {
            var cronCtx = new CronDecisionRouter.CronContext(
                targetId, userId, chatId, reason, context,
                (sid, rsn) -> applyDecisionToAll(sid, rsn),
                () -> getAlreadyProcessingMessage(),
                (cronSid, cronRole) -> getCronResumeGoal(cronSid, cronRole),
                (cron, rsn, result) -> postProcessCronDecision(cron, rsn, result),
                result -> getCronSuccessResponse(result),
                () -> getErrorMessage()
            );
            String cronRes = cronDecisionRouter.execute(cronCtx);
            if (cronRes != null) return cronRes;
        } catch (Exception e) {
            log.debugf("Cron resolution failed for %s: %s", targetId, e.getMessage());
        }

        // 3. Nothing found
        if (statefulAgentStore.isSessionActive(targetId)) return getTargetNotAwaitingMessage();
        return "Target session not found or has no pending approval.";
    }
}
