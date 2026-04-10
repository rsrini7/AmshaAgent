package com.arcclaw.core.reflex;

import java.time.Instant;
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
import com.arcclaw.core.lifecycle.WorkspaceManager;
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
import com.arcclaw.core.util.CronScheduleUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

// NOTE: AgentRoleEnum import intentionally removed — all three enum loops replaced
// by statefulAgentStore.findLatestAwaitingApproval() to fix stale-row ambiguity.
// When load() returned the oldest row for a (sessionId, agentRole) pair, a
// COMPLETED checkpoint shadowed a newer AWAITING_APPROVAL one, causing
// "/approve → No pending tool executions require approval" silently.

@ApplicationScoped
public class ApproveReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    Event<AgentJobCompleted> jobCompletedEvent;

    @Inject
    AuditService auditService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    DelegateApprovalGuard delegateGuard;

    @Inject
    CheckpointApprovalService checkpointApprovalService;

    /**
     * Resolves the original user goal from a paused checkpoint so
     * {@code RagContextEnricher} embeds the real user intent on resume
     * instead of the synthetic {@link AgentSentinels#GOAL_RESUME_APPROVED} string.
     */
    @Inject
    CheckpointResumeHandler checkpointResumeHandler;

    @Inject
    CliResultBridge cliResultBridge;

    @Override
    public boolean matches(String input) {
        ChannelCommand cmd = ChannelCommand.parse(input);
        return cmd instanceof ChannelCommand.Approve;
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
    @ActivateRequestContext
    public ReflexResult execute(String sessionId, String userId, String chatId,
                                String input, MessageContext context) {
        ChannelCommand.Approve approve = (ChannelCommand.Approve) ChannelCommand.parse(input);
        Optional<String> cronId = approve.cronId();

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {

                // 1. SessionId explicit — delegation §7.33
                if (approve.targetSessionId().isPresent()) {
                    String targetSid = approve.targetSessionId().get();
                    if (!delegateGuard.isDelegate(userId)) {
                        log.warnf("[DELEGATE-DENIED] admin=%s attempted to approve session=%s",
                                  userId, targetSid);
                        return "❌ You are not authorized to use approval delegation.";
                    }
                    return handleDelegateApproval(targetSid, userId, chatId, context);
                }

                // 2. CronId explicit — always wins
                if (cronId.isPresent()) {
                    return handleCronApproval(cronId.get(), userId, chatId, context);
                }

                // Per-tool approval: `approve github` where toolName.isPresent()
                if (approve.toolName().isPresent()) {
                    return handlePerToolApproval(
                        approve.toolName().get(), sessionId, userId, chatId, context);
                }

                // 3. Session checkpoint — interactive approval.
                // replaced enum loop with findLatestAwaitingApproval() to avoid
                // stale-row ambiguity. When multiple rows exist for the same
                // (sessionId, agentRole), the old loop called load() which returned
                // the first (oldest = COMPLETED) row, never finding the newer
                // AWAITING_APPROVAL checkpoint.
                Optional<AgentCheckpoint> latestPending =
                    statefulAgentStore.findLatestAwaitingApproval(sessionId);
                String activeRole = latestPending
                    .map(AgentCheckpoint::getAgentRole)
                    .orElse(null);

                if (activeRole != null) {
                    try {
                        List<String> pending = BatchDecisionUtil.pendingToolNames(latestPending.get(), objectMapper);
                        AgentCheckpoint cp = null;
                        if (pending.size() == 1) {
                            cp = checkpointApprovalService.approveTool(sessionId, pending.get(0), "CLI approve");
                        } else {
                            cp = checkpointApprovalService.approveAll(sessionId, "CLI approve");
                        }
                        if (ChannelType.CLI.id().equalsIgnoreCase(context.getUser().channel().id())) {
                            // Register interest first to avoid race with concurrent REST resume
                            cliResultBridge.register(sessionId);
                            try {
                                AgentResult result = checkpointResumeHandler.resumeAfterBatchDecisionSync(
                                    sessionId, activeRole, userId, chatId, ChannelType.CLI.id());
                                
                                if (result == null) {
                                    // Session is already resuming (likely via Browser/REST). 
                                    // Wait for it to finish and signal us through the bridge.
                                    result = cliResultBridge.waitForResult(sessionId, 10000);
                                }
                                return result != null ? AgentResultFormatter.format(result) : "⚠️ Could not resume — session may already be processing.";
                            } finally {
                                cliResultBridge.cancel(sessionId);
                            }
                        } else {
                            checkpointApprovalService.triggerResume(cp);
                            return "";
                        }
                    } catch (CheckpointResolutionException e) {
                        return "No pending approval found for this session.";
                    }
                }

                // 4. No session checkpoint — guide user to pending cron tasks
                List<AgentCronEntity> pending =
                    cronRepository.findAwaitingApprovalByUserId(userId);
                if (pending.size() == 1) {
                    AgentCronEntity cron = pending.getFirst();
                    return "⚠️ No active approval in this chat session.\n\n" +
                           "A background task is waiting for your approval:\n" +
                           "• " + cron.goal + "\n\n" +
                           "Use: `/approve " + cron.id + "` to approve, " +
                           "or `/deny " + cron.id + "` to cancel.";
                } else if (pending.size() > 1) {
                    String list = pending.stream()
                            .map(c -> "• `/approve " + c.id + "` — " + c.goal)
                            .collect(Collectors.joining("\n"));
                    return "⚠️ Multiple background tasks awaiting approval:\n\n" + list;
                }

                // 5. Check if it was JUST completed via another channel (signal bridge)
                if (!statefulAgentStore.isSessionActive(sessionId)) {
                    log.debugf("ApproveReflex: ignoring approval for already-completed session %s", sessionId);
                    return "No pending tool executions require approval.";
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
                return "No pending tool executions require approval.";
            });
            return ReflexResult.respond(response, context);
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.errorf("Failed to process approval for session %s: %s", sessionId, errMsg);
            log.debug("Full approval processing failure stack trace:", e);
            return ReflexResult.respond(
                "❌ **Approval Error**: An internal fault occurred while resuming the task.",
                context);
        }
    }

    private String handlePerToolApproval(
            String toolName, String sessionId, String userId,
            String chatId, MessageContext context) throws Exception {

        // replaced enum loop with findLatestAwaitingApproval() for same stale-row reason.
        // Additionally filter for hasBatchToolDecisions() since per-tool approval
        // only applies to parallel batch checkpoints.
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
            AgentCheckpoint cp = checkpointApprovalService.approveTool(sessionId, toolName, "CLI approve");
            checkpointApprovalService.triggerResume(cp);
            return "";
        } catch (CheckpointResolutionException e) {
            return "No pending approval found for this session.";
        }
    }

    // PHASE 80-A12: bulk path now delegates to CheckpointApprovalService.
    // This method retained for handleCronApproval usage. Remove in Phase 81
    // when cron approval is also migrated to CheckpointApprovalService.

    private void autoApproveAllPending(String sessionId, String agentRole) {
        AgentCheckpoint cp =
            statefulAgentStore.load(sessionId, agentRole).orElseThrow();
        BatchDecisionUtil.pendingToolNames(cp, objectMapper).forEach(toolName ->
            statefulAgentStore.updateBatchToolDecision(
                sessionId, agentRole, toolName,
                AgentSentinels.TOOL_DECISION_APPROVED));
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

    private String handleCronApproval(
        String cronId, String userId, String chatId,
        MessageContext context) throws Exception {

        AgentCronEntity cron = cronRepository.findByUserId(userId).stream()
                .filter(c -> c.id.equals(cronId))
                .findFirst()
                .orElse(null);

        if (cron == null) {
            return "❌ Cron task not found or not yours.";
        }

        if (!CronStatus.AWAITING_APPROVAL.equals(cron.status)) {
            return "❌ This task is not awaiting approval (status=" + cron.status + ").";
        }

        var pendingCheckpoint = statefulAgentStore
                .findLatestAwaitingApprovalForCron(cronId)
                .orElse(null);

        if (pendingCheckpoint == null) {
            return "❌ No pending checkpoint found for this task. " +
                "It may have already been processed.";
        }

        String cronSessionId = pendingCheckpoint.getSessionId();
        String cronRole = pendingCheckpoint.getAgentRole();

        if (!statefulAgentStore.markBatchResuming(cronSessionId)) {
            log.infof("[BATCH-RESUME-GUARD] cronSession=%s already resuming — " +
                    "ignoring duplicate trigger.", cronSessionId);
            return "⏳ Already processing your approval. Please wait.";
        }

        try {
            final String resumeGoal =
                checkpointResumeHandler.resolveResumeGoal(cronSessionId, cronRole);

            context.setResumeSignal(true);
            context.setGoal(resumeGoal);

            int cronBudget = pendingCheckpoint.getMaxIterations() != null
                    ? Math.max(3, pendingCheckpoint.getMaxIterations())
                    : 3;

            AgentResult result = WorkspaceContext.run(
                    cronSessionId, cron.userId, cron.chatId,
                    AutonomyLevel.BACKGROUND, cronRole,
                    () -> agentLoop.run(
                        cronSessionId, cronRole, resumeGoal,
                        pendingCheckpoint.getSystemNote(),
                        reasoningEngine, cronBudget, context));

            switch (result) {
                case AgentResult.Finished _ -> {
                    if (cron.runOnce)
                        cronRepository.markCompleted(cron.id, Instant.now());
                    else
                        cronRepository.markFired(cron.id, Instant.now(),
                            CronScheduleUtil.computeNext(cron.cronExpression, cron.timezone));
                }
                case AgentResult.StructuredFinished _ -> {
                    if (cron.runOnce)
                        cronRepository.markCompleted(cron.id, Instant.now());
                    else
                        cronRepository.markFired(cron.id, Instant.now(),
                            CronScheduleUtil.computeNext(cron.cronExpression, cron.timezone));
                }
                case AgentResult.Failed f  -> cronRepository.markFailed(cron.id, f.reason());
                case AgentResult.AwaitingApproval _ -> cronRepository.markAwaitingApproval(cron.id);
                case AgentResult.AwaitingRetry _ -> {
                    // No-op: retry handled by RateLimitRetryScheduler §736
                }
                case AgentResult.LoopExceeded _     -> cronRepository.markFailed(cron.id, "Loop exceeded");
            }

            ChannelType chType =
                ChannelType.fromString(cron.channelType).orElse(ChannelType.TELEGRAM);
            String platformId =
                cron.channelPlatformId != null ? cron.channelPlatformId : cron.chatId;

            jobCompletedEvent.fire(new AgentJobCompleted(
                    cron.id, cron.userId, chType, platformId, cron.lastDeliveryId,
                    AgentResultFormatter.format(result),
                    result instanceof AgentResult.Failed f ? f.category() : null));

            // Clear the resume lock BEFORE termination — the agent loop is done,
            // so the F12 guard in terminateSession no longer needs to protect us.
            statefulAgentStore.clearBatchResuming(cronSessionId);

            try {
                workspaceManager.terminateSession(cronSessionId);
            } catch (Exception e) {
                log.warnf("Failed to terminate cron session %s after approval: %s",
                        cronSessionId,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }

            return AgentResultFormatter.format(result);
        } finally {
            // Defensive: ensure lock is always released even on exceptions.
            statefulAgentStore.clearBatchResuming(cronSessionId);
        }
    }

    private String handleDelegateApproval(
            String targetSid, String adminId, String adminChatId,
            MessageContext context) throws Exception {

        log.infof("[DELEGATE-APPROVE] admin=%s approving session=%s", adminId, targetSid);

        // replaced enum loop with findLatestAwaitingApproval() for same stale-row reason.
        Optional<AgentCheckpoint> latestPending =
            statefulAgentStore.findLatestAwaitingApproval(targetSid);
        String activeRole = latestPending
            .map(AgentCheckpoint::getAgentRole)
            .orElse(null);

        if (activeRole == null) {
            return "❌ Target session not found or not awaiting approval.";
        }

        AgentCheckpoint cp = latestPending.orElseThrow(() ->
            new IllegalStateException(
                "Checkpoint disappeared between lookup and load for session " + targetSid));

        // Auto-approve all tools in batch if parallel (§7.21, §7.33)
        if (cp.hasBatchToolDecisions()) {
            autoApproveAllPending(targetSid, activeRole);
        }

        // §7.33: Log mandatory DELEGATE_APPROVAL audit event before triggering action
        auditService.logSecurityEvent(
            SecurityEventType.DELEGATE_APPROVAL,
            adminId,
            "Admin %s approved session %s".formatted(adminId, targetSid));

        return triggerBatchResume(targetSid, adminId, adminChatId, activeRole, context);
    }
}
