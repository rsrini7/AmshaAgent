package com.arcclaw.core.reflex;

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
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
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
    @Inject com.arcclaw.core.agent.loop.CliResultBridge cliResultBridge;

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

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                // 1. SessionId explicit — delegation §7.33
                if (deny.targetSessionId().isPresent()) {
                    String targetSid = deny.targetSessionId().get();
                    if (!delegateGuard.isDelegate(userId)) {
                        log.warnf("[DELEGATE-DENIED] admin=%s attempted to deny session=%s",
                                  userId, targetSid);
                        return "❌ You are not authorized to use approval delegation.";
                    }
                    return handleDelegateDenial(targetSid, userId, chatId, context);
                }

                // 2. CronId explicit — always wins
                if (cronId.isPresent()) {
                    return handleCronDenial(cronId.get(), userId, chatId, context);
                }

                // 3. Per-tool denial: `deny github`
                if (deny.toolName().isPresent()) {
                    return handlePerToolDenial(
                        deny.toolName().get(), sessionId, userId, chatId, context);
                }

                // 4. Session checkpoint — interactive denial
                // NOTE: deny.toolName() is always empty here — we returned above if present.
                Optional<AgentCheckpoint> latestPending =
                    statefulAgentStore.findLatestAwaitingApproval(sessionId);
                String activeRole = latestPending
                    .map(AgentCheckpoint::getAgentRole)
                    .orElse(null);

                if (activeRole != null) {
                    try {
                        log.infof("[DenyReflex] User denied pending tool execution for session: %s", sessionId);
                        List<String> pending = BatchDecisionUtil.pendingToolNames(latestPending.get(), objectMapper);
                        AgentCheckpoint cp = null;
                        if (pending.size() == 1) {
                            cp = checkpointApprovalService.denyTool(sessionId, pending.get(0), "CLI deny");
                        } else {
                            cp = checkpointApprovalService.denyAll(sessionId, "CLI deny");
                        }
                        if (ChannelType.CLI.id().equalsIgnoreCase(context.getUser().channel().id())) {
                            // Register interest first to avoid race with concurrent REST deny
                            cliResultBridge.register(sessionId);
                            try {
                                AgentResult result = checkpointResumeHandler.resumeAfterBatchDecisionSync(
                                    sessionId, activeRole, userId, chatId, "cli");
                                
                                if (result == null) {
                                    // Session is already resuming/cancelling.
                                    // Wait for it to finish and signal us through the bridge.
                                    result = cliResultBridge.waitForResult(sessionId, 10000);
                                }
                                return result != null ? AgentResultFormatter.format(result) : "⚠️ Could not deny — session may already be processing.";
                            } finally {
                                cliResultBridge.cancel(sessionId);
                            }
                        } else {
                            checkpointApprovalService.triggerResume(cp);
                            return "";
                        }
                    } catch (CheckpointResolutionException e) {
                        return "No pending denial found for this session.";
                    }
                }

                // 2026-04-07: Bridge check for late-arriving results from other channels
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

                // 5. No session checkpoint — guide user to pending cron tasks
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
                    return "⚠️ Multiple background tasks awaiting approval:\n\n" + list;
                }

                // 6. Nothing pending anywhere
                return "No pending tool executions require approval for this session.";
            });
            return ReflexResult.respond(response, context);
        } catch (Exception e) {
            // Fix 1: e.getMessage() can be null for bare exception construction
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.errorf("Failed to process denial for session %s: %s", sessionId, errMsg);
            log.debug("Full denial processing failure stack trace:", e);
            return ReflexResult.respond(
                "❌ **Denial Error**: An internal fault occurred while terminating the task.",
                context);
        }
    }

    private String handlePerToolDenial(
            String toolName, String sessionId, String userId,
            String chatId, MessageContext context) throws Exception {

        Optional<AgentCheckpoint> latestPending =
            statefulAgentStore.findLatestAwaitingApproval(sessionId);
        String activeRole = latestPending
            .filter(AgentCheckpoint::hasBatchToolDecisions)
            .map(AgentCheckpoint::getAgentRole)
            .orElse(null);

        if (activeRole == null) {
            return "No parallel batch awaiting per-tool approval for this session.";
        }

        try {
            AgentCheckpoint cp = checkpointApprovalService.denyTool(sessionId, toolName, "CLI deny");
            checkpointApprovalService.triggerResume(cp);
            return "🚫 Tool '" + toolName + "' denied.";
        } catch (CheckpointResolutionException e) {
            return "No pending denial found for this session.";
        }
    }


    // PHASE 80-A12: bulk path now delegates to CheckpointApprovalService.
    // This method retained for handleCronApproval usage. Remove in Phase 81
    // when cron approval is also migrated to CheckpointApprovalService.
    private void autoDenyAllPending(String sessionId, String agentRole) {
        AgentCheckpoint cp =
            statefulAgentStore.load(sessionId, agentRole).orElseThrow();
        BatchDecisionUtil.pendingToolNames(cp, objectMapper).forEach(toolName ->
            statefulAgentStore.updateBatchToolDecision(
                sessionId, agentRole, toolName,
                AgentSentinels.TOOL_DECISION_DENIED));
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
        return "✅ Resuming task...";
    }

    private String handleCronDenial(
            String cronId, String userId, String chatId,
            MessageContext context) throws Exception {

        AgentCronEntity cron = cronRepository.findByUserId(userId).stream()
                .filter(c -> c.id.equals(cronId))
                .findFirst()
                .orElse(null);

        if (cron == null) {
            return "❌ Cron task not found or not yours.";
        }

        if (cron.status != CronStatus.AWAITING_APPROVAL) {
            return "❌ This task is not awaiting approval (status=" + cron.status.name() + ").";
        }

        var pendingCheckpoint = statefulAgentStore
                .findLatestAwaitingApprovalForCron(cronId)
                .orElse(null);

        if (pendingCheckpoint == null) {
            return "❌ No pending checkpoint found for this task. " +
                   "It may have already been processed.";
        }

        String cronSessionId = pendingCheckpoint.getSessionId();
        // Fix 2: use the actual agent role stored in the checkpoint.
        // Hardcoding COORDINATOR caused resolveResumeGoal() to look up the wrong
        // checkpoint row. Consistent with the same fix applied to ApproveReflex.
        String cronRole = pendingCheckpoint.getAgentRole();

        if (!statefulAgentStore.markBatchResuming(cronSessionId)) {
            log.infof("[BATCH-RESUME-GUARD] cronSession=%s already resuming — " +
                      "ignoring duplicate trigger.", cronSessionId);
            return "⏳ Already processing your denial. Please wait.";
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

            cronRepository.markFailed(cron.id, "User denied tool execution.");

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
            MessageContext context) throws Exception {

        log.infof("[DELEGATE-DENY] admin=%s denying session=%s", adminId, targetSid);

        Optional<AgentCheckpoint> latestPending =
            statefulAgentStore.findLatestAwaitingApproval(targetSid);
        String activeRole = latestPending
            .map(AgentCheckpoint::getAgentRole)
            .orElse(null);

        if (activeRole == null) {
            return "❌ Target session not found or not awaiting approval.";
        }

        AgentCheckpoint cp = statefulAgentStore.load(targetSid, activeRole)
                .orElseThrow(() -> new IllegalStateException(
                    "Checkpoint disappeared between lookup and load for session " + targetSid));

        if (cp.hasBatchToolDecisions()) {
            autoDenyAllPending(targetSid, activeRole);
        }

        auditService.logSecurityEvent(
            SecurityEventType.DELEGATE_APPROVAL,
            adminId,
            "Admin %s denied session %s".formatted(adminId, targetSid));

        return triggerBatchResume(targetSid, adminId, adminChatId, activeRole, context);
    }
}
