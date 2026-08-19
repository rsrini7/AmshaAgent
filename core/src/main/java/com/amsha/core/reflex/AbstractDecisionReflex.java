package com.amsha.core.reflex;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.channel.api.ChannelCommand;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.CheckpointResolutionException;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CronStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Base class for reflexes that make a decision on a pending tool (Approve or Deny).
 * Consolidates common logic for delegation, cron job resolution, and HITL ambiguity handling.
 */
public abstract class AbstractDecisionReflex implements AgentReflex {

    public static final String MSG_RESUMING_TASK = "Resuming task...";

    @Inject
    protected Logger log;

    @Inject
    protected StatefulAgentStore statefulAgentStore;

    @Inject
    protected CronStorePort cronStore;

    @Inject
    protected AuditService auditService;

    @Inject
    protected ApprovalNotificationService notificationService;

    @Inject
    protected ObjectMapper objectMapper;

    @Inject
    protected CheckpointResumeHandler checkpointResumeHandler;

    @Inject
    protected DelegateApprovalGuard delegateGuard;

    @Inject
    protected CheckpointApprovalService checkpointApprovalService;

    @Inject
    CronDecisionRouter cronDecisionRouter;

    @Inject
    ResumeResultHelper resumeResultHelper;

    protected abstract String getActionName();

    protected abstract String getAmbiguityTitle();

    protected abstract String getNoPendingMessage();

    protected abstract String getErrorMessage();

    protected abstract String getNotAuthorizedMessage();

    protected abstract String getTargetNotAwaitingMessage();

    protected abstract AgentCheckpoint applyDecisionToTool(String sessionId, String toolName, String reason);

    protected abstract AgentCheckpoint applyDecisionToAll(String sessionIdOrCronId, String reason);

    protected abstract void postProcessCronDecision(AgentCron cron, String reason, AgentResult result);

    protected abstract String getCronResumeGoal(String cronSessionId, String cronRole);

    protected abstract String getAlreadyProcessingMessage();

    protected abstract String getSingleCronFallbackMessage(AgentCron cron);

    protected abstract String getMultipleCronFallbackMessage(String list);

    protected abstract String getCronSuccessResponse(AgentResult result);

    protected abstract String getFailedRetryMessage();

    protected abstract String getPerToolSuccessResponse(String toolName);

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        ChannelCommand cmd = ChannelCommand.parse(input);
        DecisionParams d = extractDecisionParams(cmd);
        if (d == null) return ReflexResult.forward(context);

        String reason = d.reasonOpt.orElse("CLI " + getActionName().toLowerCase(Locale.ROOT));
        AmbiguityResolver ambiguityResolver = new AmbiguityResolver(getActionName(), getAmbiguityTitle());

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                if (d.targetId.isPresent()) {
                    return processExplicitTarget(
                            d.targetId.get(), sessionId, userId, chatId, reason, context, ambiguityResolver);
                }
                if (d.toolName.isPresent())
                    return handlePerToolDecision(d.toolName.get(), sessionId, userId, chatId, reason, context);

                Optional<AgentCheckpoint> latestPending = statefulAgentStore
                        .findLatestAwaitingApproval(sessionId)
                        .or(() -> statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(sessionId));
                List<AgentCron> pendingCrons = findPendingCrons(userId);

                String ambiguity = ambiguityResolver.detect(latestPending, pendingCrons);
                if (ambiguity != null) return ambiguity;

                String sessionDecision =
                        handleActiveSessionDecision(sessionId, userId, chatId, reason, context, latestPending);
                if (sessionDecision != null) return sessionDecision;

                String retryGuide = resumeResultHelper.handleFailedRetry(sessionId, getFailedRetryMessage());
                if (retryGuide != null) return retryGuide;

                if (!pendingCrons.isEmpty()) {
                    if (pendingCrons.size() == 1) return getSingleCronFallbackMessage(pendingCrons.getFirst());
                    String cronList = pendingCrons.stream()
                            .map(c -> "• `/%s %s` - %s"
                                    .formatted(getActionName().toLowerCase(Locale.ROOT), c.getId(), c.getGoal()))
                            .collect(Collectors.joining("\n"));
                    return getMultipleCronFallbackMessage(cronList);
                }

                if (!statefulAgentStore.isSessionActive(sessionId)) {
                    log.debugf("%sReflex: session %s already completed", getActionName(), sessionId);
                    return getNoPendingMessage();
                }

                return resumeResultHelper.pollBridge(sessionId);
            });
            notificationService.flushPendingNotifications(chatId);
            return response != null ? ReflexResult.respond(response, context) : ReflexResult.forward(context);
        } catch (Exception e) {
            log.errorf("Failed to process %s for session %s: %s", getActionName(), sessionId, LogUtil.rootMessage(e));
            return ReflexResult.respond(getErrorMessage() + ": " + LogUtil.rootMessage(e), context);
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

    private String handleActiveSessionDecision(
            String sessionId,
            String userId,
            String chatId,
            String reason,
            MessageContext context,
            Optional<AgentCheckpoint> latestPending) {
        if (latestPending.isEmpty()) return null;
        AgentCheckpoint checkpoint = latestPending.get();
        String targetSessionId = checkpoint.getSessionId() != null ? checkpoint.getSessionId() : sessionId;
        String activeRole = checkpoint.getAgentRole();
        if (activeRole != null) {
            try {
                log.infof(
                        "[%sReflex] User processed pending decision for session: %s", getActionName(), targetSessionId);
                List<String> pending = BatchDecisionUtil.pendingToolNames(checkpoint, objectMapper);
                AgentCheckpoint cp = (pending.size() == 1)
                        ? applyDecisionToTool(targetSessionId, pending.get(0), reason)
                        : applyDecisionToAll(targetSessionId, reason);

                if (ChannelType.CLI
                        .id()
                        .equalsIgnoreCase(context.getUser().channel().id())) {
                    return resumeResultHelper.runSync(targetSessionId, activeRole, userId, chatId);
                } else {
                    // §Resume-Fix: If applyDecisionToAll returned null (e.g. no batchToolDecisions),
                    // use the already-loaded checkpoint to trigger resume.
                    AgentCheckpoint resumeTarget = cp != null ? cp : checkpoint;
                    log.infof(
                            "[%sReflex] Triggering resume for session %s (cp=%s, fallback=%s)",
                            getActionName(), targetSessionId, cp != null, cp == null);
                    checkpointApprovalService.triggerResume(resumeTarget);
                    return "";
                }
            } catch (CheckpointResolutionException e) {
                return getNoPendingMessage();
            }
        }
        return null;
    }

    private String handleDelegateDecision(
            String targetSid, String adminId, String adminChatId, String reason, MessageContext context)
            throws Exception {
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

        String auditMsg =
                "Admin %s performed %s on session %s: %s".formatted(adminId, getActionName(), targetSid, reason);
        auditService.recordMandatory(targetSid, adminId, activeRole, SecurityEventType.DELEGATE_APPROVAL, auditMsg);

        checkpointResumeHandler.resumeAfterBatchDecision(
                targetSid,
                activeRole,
                adminId,
                adminChatId,
                context.getUser().channel().name());
        return MSG_RESUMING_TASK;
    }

    private String handlePerToolDecision(
            String toolName, String sessionId, String _userId, String _chatId, String reason, MessageContext _context)
            throws Exception {
        Optional<AgentCheckpoint> latestPending = statefulAgentStore
                .findLatestAwaitingApproval(sessionId)
                .or(() -> statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(sessionId));
        if (latestPending.isEmpty()) return "⚠️ No parallel batch awaiting decision.";
        AgentCheckpoint checkpoint = latestPending.get();
        String targetSessionId = checkpoint.getSessionId() != null ? checkpoint.getSessionId() : sessionId;
        String activeRole = checkpoint.getAgentRole();

        if (activeRole == null || !checkpoint.hasBatchToolDecisions()) return "⚠️ No parallel batch awaiting decision.";

        try {
            AgentCheckpoint cp = applyDecisionToTool(targetSessionId, toolName, reason);
            if (BatchDecisionUtil.pendingToolNames(cp, objectMapper).isEmpty()) {
                checkpointApprovalService.triggerResume(cp);
            }
            return getPerToolSuccessResponse(toolName);
        } catch (CheckpointResolutionException e) {
            return getNoPendingMessage();
        }
    }

    private String processExplicitTarget(
            String targetId,
            String sessionId,
            String userId,
            String chatId,
            String reason,
            MessageContext context,
            AmbiguityResolver ambiguityResolver)
            throws Exception {
        String resolvedId = resolveNumericTargetIndex(targetId, sessionId, userId);

        // Resolve prefix match for cron job IDs
        String prefix = resolvedId.toLowerCase(Locale.ROOT);
        if (prefix.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            prefix = prefix.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
        }
        if (prefix.length() >= 4 && !prefix.contains("-")) {
            List<AgentCron> pendingCrons = findPendingCrons(userId);
            String finalPrefix = prefix;
            Optional<AgentCron> matchedCron = pendingCrons.stream()
                    .filter(c -> c.getId().toLowerCase(Locale.ROOT).startsWith(finalPrefix))
                    .findFirst();
            if (matchedCron.isPresent()) {
                resolvedId = matchedCron.get().getId();
            }
        }

        final String finalTargetId = resolvedId;

        // §Self-Session Fast-Path: if the user provided their own session ID or descendant ID,
        // treat it identically to a bare /approve — no delegate check needed.
        boolean isOwnSessionOrDescendant = finalTargetId.equals(sessionId)
                || finalTargetId.startsWith(sessionId + ":")
                || finalTargetId.startsWith(sessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL);
        if (isOwnSessionOrDescendant) {
            log.debugf(
                    "[%sReflex] targetId==sessionId or descendant (%s) — routing to own-session path",
                    getActionName(), finalTargetId);
            Optional<AgentCheckpoint> selfPending = statefulAgentStore
                    .findLatestAwaitingApproval(finalTargetId)
                    .or(() -> statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(finalTargetId));
            List<AgentCron> pendingCrons = findPendingCrons(userId);
            String ambiguity = ambiguityResolver.detect(selfPending, pendingCrons);
            if (ambiguity != null) return ambiguity;
            String selfDecision =
                    handleActiveSessionDecision(finalTargetId, userId, chatId, reason, context, selfPending);
            if (selfDecision != null) return selfDecision;

            String retryGuide = resumeResultHelper.handleFailedRetry(finalTargetId, getFailedRetryMessage());
            if (retryGuide != null) return retryGuide;
            if (!pendingCrons.isEmpty()) {
                if (pendingCrons.size() == 1) return getSingleCronFallbackMessage(pendingCrons.getFirst());
                String cronList = pendingCrons.stream()
                        .map(c -> "• `/%s %s` - %s"
                                .formatted(getActionName().toLowerCase(Locale.ROOT), c.getId(), c.getGoal()))
                        .collect(Collectors.joining("\n"));
                return getMultipleCronFallbackMessage(cronList);
            }

            // If we are in the self-session path but no pending session/cron/retry was found,
            // since an ID was explicitly provided, we should return a specific "not found"
            // error rather than the generic "no pending in this chat" message.
            if (selfPending.isEmpty()) {
                if (statefulAgentStore.isSessionActive(finalTargetId)) return getTargetNotAwaitingMessage();
                return "Target session not found or has no pending approval.";
            }

            return getNoPendingMessage();
        }

        // Runtime disambiguation: probe session first, then cron.
        // 1. Session pending? → requires delegate role for cross-session approval
        Optional<AgentCheckpoint> targetPending = statefulAgentStore.findLatestAwaitingApproval(finalTargetId);
        if (targetPending.isPresent()) {
            // §Cron-Owner Fast-Path: if the target is a cron-run session owned by this user,
            // allow approval without delegate role.
            String targetSessionId = targetPending.get().getSessionId();
            if (targetSessionId != null && targetSessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
                String cronId = targetSessionId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
                if (cronId.length() >= 36) cronId = cronId.substring(0, 36);
                Optional<AgentCron> cron = cronStore.findById(cronId).map(AgentCronRecord::fromDto);
                if (cron.isPresent() && cron.get().getUserId().equals(userId)) {
                    return handleDelegateDecision(finalTargetId, userId, chatId, reason, context);
                }
            }
            if (targetSessionId != null
                    && (targetSessionId.startsWith(sessionId + ":") || targetSessionId.contains(sessionId))) {
                return handleDelegateDecision(finalTargetId, userId, chatId, reason, context);
            }
            if (!delegateGuard.isDelegate(userId)) {
                log.warnf(
                        "[%s-DELEGATE-DENIED] user=%s attempted session decision for id=%s",
                        getActionName(), userId, finalTargetId);
                return getNotAuthorizedMessage();
            }
            return handleDelegateDecision(finalTargetId, userId, chatId, reason, context);
        }

        // 2. Not a session — try as cron task (owner can approve their own tasks)
        try {
            var cronCtx = new CronDecisionRouter.CronContext(
                    finalTargetId,
                    userId,
                    chatId,
                    reason,
                    context,
                    (sid, rsn) -> applyDecisionToAll(sid, rsn),
                    () -> getAlreadyProcessingMessage(),
                    (cronSid, cronRole) -> getCronResumeGoal(cronSid, cronRole),
                    (cron, rsn, result) -> postProcessCronDecision(cron, rsn, result),
                    result -> getCronSuccessResponse(result),
                    () -> getErrorMessage(),
                    notificationService.createJobNotifier());
            String cronRes = cronDecisionRouter.execute(cronCtx);
            if (cronRes != null) return cronRes;
        } catch (Exception e) {
            log.debugf("Cron resolution failed for %s: %s", finalTargetId, LogUtil.rootMessage(e));
        }

        // 3. Nothing found
        if (statefulAgentStore.isSessionActive(finalTargetId)) return getTargetNotAwaitingMessage();
        return "Target session not found or has no pending approval.";
    }

    private String resolveNumericTargetIndex(String targetId, String sessionId, String userId) {
        if (!targetId.matches("\\d+")) return targetId;
        int idx = Integer.parseInt(targetId);
        Optional<AgentCheckpoint> selfPending = statefulAgentStore.findLatestAwaitingApproval(sessionId);
        List<AgentCron> pendingCrons = findPendingCrons(userId);
        String resolvedTargetId = null;
        if (selfPending.isPresent()) {
            if (idx == 1) {
                resolvedTargetId = sessionId;
            } else if (idx >= 2 && idx - 2 < pendingCrons.size()) {
                resolvedTargetId = pendingCrons.get(idx - 2).getId();
            }
        } else {
            if (idx >= 1 && idx - 1 < pendingCrons.size()) {
                resolvedTargetId = pendingCrons.get(idx - 1).getId();
            }
        }
        return resolvedTargetId != null ? resolvedTargetId : targetId;
    }

    private List<AgentCron> findPendingCrons(String userId) {
        if (cronStore == null || userId == null) return List.of();
        var dtos = cronStore.findAwaitingApprovalByUserId(userId);
        return dtos == null
                ? List.of()
                : dtos.stream().<AgentCron>map(AgentCronRecord::fromDto).toList();
    }
}
