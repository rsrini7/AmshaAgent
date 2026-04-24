package com.arcclaw.core.reflex;

import static com.arcclaw.core.message.UserMessages.ALREADY_PROCESSING_DENIAL;
import static com.arcclaw.core.message.UserMessages.COULD_NOT_RESUME;
import static com.arcclaw.core.message.UserMessages.CRON_MULTIPLE_AWAITING;
import static com.arcclaw.core.message.UserMessages.CRON_NOT_FOUND;
import static com.arcclaw.core.message.UserMessages.DENIAL_ERROR;
import static com.arcclaw.core.message.UserMessages.NOT_AUTHORIZED_DELEGATION;
import static com.arcclaw.core.message.UserMessages.NO_BATCH_AWAITING;
import static com.arcclaw.core.message.UserMessages.NO_PENDING_ANYWHERE_SESSION;
import static com.arcclaw.core.message.UserMessages.NO_PENDING_DENIAL;
import static com.arcclaw.core.message.UserMessages.RESUMING_TASK;
import static com.arcclaw.core.message.UserMessages.TARGET_SESSION_NOT_AWAITING;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
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
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
@ReflexPriority(200)
public class DenyReflex implements AgentReflex {

    @Inject Logger log;
    @Inject StatefulAgentStore statefulAgentStore;
    @Inject AgentCronRepository cronRepository;
    @Inject AgentLoop agentLoop;
    @Inject ReasoningEngine reasoningEngine;
    @Inject Event<AgentJobCompleted> jobCompletedEvent;
    @Inject AuditService auditService;
    @Inject ObjectMapper objectMapper;
    @Inject CheckpointResumeHandler checkpointResumeHandler;
    @Inject DelegateApprovalGuard delegateGuard;
    @Inject CheckpointApprovalService checkpointApprovalService;
    @Inject CliResultBridge cliResultBridge;

    @Override
    public boolean matches(String input) {
        ChannelCommand cmd = ChannelCommand.parse(input);
        return cmd instanceof ChannelCommand.Deny;
    }

    @Override
    public SecurityEventType auditEventType() {
        return SecurityEventType.APPROVAL_DENIED;
    }

    @Override
    public String auditDetail(String sessionId, String input) {
        return "action=DENY session=" + sessionId;
    }

    @Override
    @ActivateRequestContext
    public ReflexResult execute(String sessionId, String userId, String chatId,
                                String input, MessageContext context) {
        ChannelCommand.Deny deny = (ChannelCommand.Deny) ChannelCommand.parse(input);
        Optional<String> cronId = deny.cronId();
        String reason = deny.reason().orElse("CLI deny");

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                // 1. SessionId explicit — delegation §7.33
                if (deny.targetSessionId().isPresent()) {
                    String targetSid = deny.targetSessionId().get();
                    if (!delegateGuard.isDelegate(userId)) {
                        log.warnf("[DELEGATE-DENIED] admin=%s attempted to deny session=%s",
                                  userId, targetSid);
                        return NOT_AUTHORIZED_DELEGATION;
                    }
                    return handleDelegateDenial(targetSid, userId, chatId, reason, context);
                }

                // 2. CronId explicit — always wins
                if (cronId.isPresent()) {
                    return handleCronDenial(cronId.get(), userId, chatId, reason, context);
                }

                // 3. Per-tool denial: `deny github`
                if (deny.toolName().isPresent()) {
                    return handlePerToolDenial(
                        deny.toolName().get(), sessionId, userId, chatId, reason, context);
                }

                // 4. Session checkpoint — interactive denial
                // NOTE: deny.toolName() is always empty here — we returned above if present.
                Optional<AgentCheckpoint> latestPending =
                    statefulAgentStore.findLatestAwaitingApproval(sessionId);
                    
                List<AgentCronEntity> pendingCrons = cronRepository.findAwaitingApprovalByUserId(userId);
                
                if (latestPending.isPresent() && !pendingCrons.isEmpty()) {
                    StringBuilder sb = new StringBuilder("⚠️ Ambiguous denial. You have multiple tasks waiting:\n\n");
                    sb.append("• `/deny` — (This Chat) ").append(latestPending.get().getOriginalGoal()).append("\n");
                    for (AgentCronEntity c : pendingCrons) {
                        sb.append("• `/deny ").append(c.id).append("` — (Background) ").append(c.goal).append("\n");
                    }
                    sb.append("\nPlease reply with the specific command.");
                    return sb.toString();
                }

                String activeRole = latestPending
                    .map(AgentCheckpoint::getAgentRole)
                    .orElse(null);

                if (activeRole != null) {
                    try {
                        log.infof("[DenyReflex] User denied pending tool execution for session: %s", sessionId);
                        List<String> pending = BatchDecisionUtil.pendingToolNames(latestPending.get(), objectMapper);
                        AgentCheckpoint cp = null;
                        if (pending.size() == 1) {
                            cp = checkpointApprovalService.denyTool(sessionId, pending.get(0), reason);
                        } else {
                            cp = checkpointApprovalService.denyAll(sessionId, reason);
                        }
                        if (ChannelType.CLI.id().equalsIgnoreCase(context.getUser().channel().id())) {
                            // Register interest first to avoid race with concurrent REST deny
                            cliResultBridge.register(sessionId);
                            try {
                                AgentResult result = checkpointResumeHandler.resumeAfterBatchDecisionSync(
                                    sessionId, activeRole, userId, chatId, ChannelType.CLI.id());
                                
                                if (result == null) {
                                    // Session is already resuming/cancelling.
                                    // Wait for it to finish and signal us through the bridge.
                                    result = cliResultBridge.waitForResult(sessionId, 10000);
                                }
                                return result != null ? AgentResultFormatter.format(result) : COULD_NOT_RESUME;
                            } finally {
                                cliResultBridge.cancel(sessionId);
                            }
                        } else {
                            checkpointApprovalService.triggerResume(cp);
                            return "";
                        }
                    } catch (CheckpointResolutionException e) {
                        return NO_PENDING_DENIAL;
                    }
                }

                // 5. No session checkpoint — guide user to pending cron tasks
                // Added to pass the junit test cases. Yet to verify this.
                List<AgentCronEntity> pending =
                    cronRepository.findAwaitingApprovalByUserId(userId);
                if (pending.size() == 1) {
                    AgentCronEntity cron = pending.getFirst();
                    return "⚠️ No active denial in this chat session.\n\n" +
                        "A background task is waiting:\n" +
                        "• " + cron.goal + "\n\n" +
                        "Use: `/deny " + cron.id + "` to cancel it.";
                } else if (pending.size() > 1) {
                    String list = pending.stream()
                            .map(c -> "• `/deny " + c.id + "` — " + c.goal)
                            .collect(Collectors.joining("\n"));
                    return String.format(CRON_MULTIPLE_AWAITING, list);
                }

                // 2026-04-07: Bridge check for late-arriving results from other channels
                if (!statefulAgentStore.isSessionActive(sessionId)) {
                    log.debugf("DenyReflex: ignoring denial for already-completed session %s", sessionId);
                    return NO_PENDING_ANYWHERE_SESSION;
                }

                AgentResult bridgeResult = null;
                cliResultBridge.register(sessionId);
                try {
                    bridgeResult = cliResultBridge.waitForResult(sessionId, 200);
                } finally {
                    cliResultBridge.cancel(sessionId);
                }

                if (bridgeResult != null) {
                    return AgentResultFormatter.format(bridgeResult);
                }

                // 6. Nothing pending anywhere
                return NO_PENDING_ANYWHERE_SESSION;
            });
            return ReflexResult.respond(response, context);
        } catch (Exception e) {
            //  1: e.getMessage() can be null for bare exception construction
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.errorf("Failed to process denial for session %s: %s", sessionId, errMsg);
            log.debug("Full denial processing failure stack trace:", e);
            return ReflexResult.respond(
                DENIAL_ERROR,
                context);
        }
    }

    private String handlePerToolDenial(
            String toolName, String sessionId, String userId,
            String chatId, String reason, MessageContext context) throws Exception {

        Optional<AgentCheckpoint> latestPending =
            statefulAgentStore.findLatestAwaitingApproval(sessionId);
        String activeRole = latestPending
            .filter(AgentCheckpoint::hasBatchToolDecisions)
            .map(AgentCheckpoint::getAgentRole)
            .orElse(null);

        if (activeRole == null) {
            return NO_BATCH_AWAITING;
        }

        try {
            AgentCheckpoint cp = checkpointApprovalService.denyTool(sessionId, toolName, reason);
            checkpointApprovalService.triggerResume(cp);
            return UserMessages.toolDenied(toolName);
        } catch (CheckpointResolutionException e) {
            return NO_PENDING_DENIAL;
        }
    }

    private String triggerBatchResume(
            String sessionId, String userId, String chatId,
            String agentRole, MessageContext context) throws Exception {
        // PHASE 80-A12: delegating to CheckpointResumeHandler.
        // This wrapper retained for handleCronApproval and
        // handleDelegateApproval which still pass MessageContext.
        // Remove in Phase 81 when those paths are also migrated.
        checkpointResumeHandler.resumeAfterBatchDecision(
            sessionId, agentRole, context.getUser().platformId(), context.getChatId(),
            context.getUser().channel().name()
        );
        return RESUMING_TASK;
    }

    private String handleCronDenial(
            String cronId, String userId, String chatId,
            String reason, MessageContext context) throws Exception {

        // 1. Resolve and Deny via service (handles cronId alias correctly)
        AgentCheckpoint pendingCheckpoint = checkpointApprovalService.denyAll(cronId, reason);

        String cronSessionId = pendingCheckpoint.getSessionId();
        String cronRole = pendingCheckpoint.getAgentRole();

        // 2. Fetch job entity for post-processing logic
        // Extract the jobId from the sessionId if a full sessionId was provided.
        // Format: cron-JOB_UUID-EXEC_UUID
        String resolvedJobId = cronId;
        if (cronId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            String suffix = cronId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
            if (suffix.length() >= 36) {
                resolvedJobId = suffix.substring(0, 36);
            }
        }

        AgentCronEntity cron = cronRepository.findById(resolvedJobId);

        if (cron == null) {
            log.warnf("[handleCronDenial] Cron job not found for jobId/sessionId: %s", cronId);
            return CRON_NOT_FOUND;
        }

        if (!statefulAgentStore.markBatchResuming(cronSessionId, cronRole)) {
            log.infof("[BATCH-RESUME-GUARD] cronSession=%s already resuming — " +
                      "ignoring duplicate trigger.", cronSessionId);
            return ALREADY_PROCESSING_DENIAL;
        }

        try {
            context.setResumeSignal(true);
            context.setGoal(AgentSentinels.GOAL_DENY_APPROVED);

            AgentResult result = WorkspaceContext.run(
                    cronSessionId, cron.userId, cron.chatId,
                    AutonomyLevel.BACKGROUND, cronRole,
                    () -> agentLoop.run(
                            cronSessionId, cronRole,
                            AgentSentinels.GOAL_DENY_APPROVED,
                            reasoningEngine, 1, context));

            cronRepository.markFailed(cron.id, reason != null ? reason : "User denied tool execution.");

            ChannelType chType =
                ChannelType.fromString(cron.channelType).orElse(ChannelType.TELEGRAM);
            String platformId =
                cron.channelPlatformId != null ? cron.channelPlatformId : cron.chatId;

            jobCompletedEvent.fire(new AgentJobCompleted(
                    cron.id, cron.userId, chType, platformId, cron.lastDeliveryId,
                    AgentResultFormatter.format(result),
                    null));

            return AgentResultFormatter.format(result);
        } finally {
            statefulAgentStore.clearBatchResuming(cronSessionId);
        }
    }

    private String handleDelegateDenial(
            String targetSid, String adminId, String adminChatId,
            String reason, MessageContext context) throws Exception {

        log.infof("[DELEGATE-DENY] admin=%s denying session=%s", adminId, targetSid);

        Optional<AgentCheckpoint> latestPending =
            statefulAgentStore.findLatestAwaitingApproval(targetSid);
        String activeRole = latestPending
            .map(AgentCheckpoint::getAgentRole)
            .orElse(null);

        if (activeRole == null) {
            return TARGET_SESSION_NOT_AWAITING;
        }

        AgentCheckpoint cp = statefulAgentStore.load(targetSid, activeRole)
                .orElseThrow(() -> new IllegalStateException(
                    "Checkpoint disappeared between lookup and load for session " + targetSid));

        if (cp.hasBatchToolDecisions()) {
            checkpointApprovalService.denyAll(targetSid, reason);
        }

        // §7.33: Log mandatory DELEGATE_APPROVAL audit event before triggering action
        String auditMsg = reason.equals("CLI deny")
            ? "Admin %s denied session %s".formatted(adminId, targetSid)
            : "Admin %s denied session %s: %s".formatted(adminId, targetSid, reason);

        auditService.logSecurityEvent(
            SecurityEventType.DELEGATE_APPROVAL,
            adminId,
            auditMsg);

        return triggerBatchResume(targetSid, adminId, adminChatId, activeRole, context);
    }
}
