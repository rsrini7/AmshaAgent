package com.arcclaw.core.reflex;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
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
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DenyReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    Event<AgentJobCompleted> jobCompletedEvent;

    @Inject
    AuditService auditService;

    @Inject
    ObjectMapper objectMapper;
    
    @Inject
    CheckpointResumeHandler checkpointResumeHandler;

    @Inject
    DelegateApprovalGuard delegateGuard;

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
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        ChannelCommand.Deny deny = (ChannelCommand.Deny) ChannelCommand.parse(input);
        Optional<String> cronId = deny.cronId();

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                // 1. SessionId explicit — delegation §7.33
                // Prioritize session ID over cron ID because session IDs (numeric-hyphen-numeric)
                // would otherwise match the cronId pattern §7.33
                if (deny.targetSessionId().isPresent()) {
                    String targetSid = deny.targetSessionId().get();
                    if (!delegateGuard.isDelegate(userId)) {
                        log.warnf("[DELEGATE-DENIED] admin=%s attempted to deny session=%s", userId, targetSid);
                        return "❌ You are not authorized to use approval delegation.";
                    }
                    return handleDelegateDenial(targetSid, userId, chatId, context);
                }

                // 2. CronId explicit — always wins (Fix 3)
                if (cronId.isPresent()) {
                    return handleCronDenial(cronId.get(), userId, chatId, context);
                }

                if (deny.toolName().isPresent()) {
                    return handlePerToolDenial(
                        deny.toolName().get(), sessionId, userId, chatId, context);
                }

                // 2. Session checkpoint — interactive approval
                String activeRole = null;
                AgentCheckpoint activeCheckpoint = null;
                for (AgentRoleEnum role : AgentRoleEnum.values()) {
                    Optional<AgentCheckpoint> cpOpt = statefulAgentStore.load(sessionId, role.toScopedName());
                    if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                        activeRole = role.toScopedName();
                        activeCheckpoint = cpOpt.get();
                        break;
                    }
                }

                if (activeRole != null) {
                    if (deny.toolName().isEmpty() && activeCheckpoint.hasBatchToolDecisions()) {
                        autoDenyAllPending(sessionId, activeRole);
                        return triggerBatchResume(sessionId, userId, chatId, activeRole, context);
                    }

                    log.infof("[DenyReflex] User denied pending tool execution for session: %s", sessionId);
                    AgentCheckpoint cp = statefulAgentStore.load(sessionId, activeRole).orElseThrow();
                    cp.setStatus(CheckpointStatus.FAILED);
                    cp.setFailReason(AgentSentinels.DENY_SENTINEL);
                    statefulAgentStore.save(cp);
                    return "🚫 Execution denied. The agent's task has been terminated.";
                }

                // 3. No session checkpoint — Fix 1 guidance
                List<AgentCronEntity> pending = cronRepository.findAwaitingApprovalByUserId(userId);
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

                // 4. Nothing pending anywhere
                return "No pending tool executions require approval for this session.";
            });
            return ReflexResult.respond(response, context);
        } catch (Exception e) {
            log.errorf("Failed to process denial for session %s: %s", sessionId, e.getMessage());
            log.debug("Full denial processing failure stack trace:", e);
            return ReflexResult.respond("❌ **Denial Error**: An internal fault occurred while terminating the task.", context);
        }
    }

    private String handlePerToolDenial(
            String toolName, String sessionId, String userId,
            String chatId, MessageContext context) throws Exception {

        String activeRole = null;
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            Optional<AgentCheckpoint> cpOpt =
                statefulAgentStore.load(sessionId, role.toScopedName());
            if (cpOpt.isPresent()
                    && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL
                    && cpOpt.get().hasBatchToolDecisions()) {
                activeRole = role.toScopedName();
                break;
            }
        }

        if (activeRole == null) {
            return "No parallel batch awaiting per-tool approval for this session.";
        }

        boolean updated = statefulAgentStore.updateBatchToolDecision(
            sessionId, activeRole, toolName, AgentSentinels.TOOL_DECISION_DENIED);

        if (!updated) {
            return String.format(
                "Tool '%s' is not part of the pending batch, " +
                "or was already decided. Use `deny` to deny all.",
                toolName);
        }

        if (statefulAgentStore.isAllBatchToolsDecided(sessionId, activeRole)) {
            return triggerBatchResume(sessionId, userId, chatId, activeRole, context);
        }

        AgentCheckpoint cp =
            statefulAgentStore.load(sessionId, activeRole).orElseThrow();
        List<String> stillPending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        return String.format(
            "🚫 Denied `%s`. Still waiting for: %s\n" +
            "Type `approve <toolName>` or `deny <toolName>` to decide remaining.",
            toolName,
            stillPending.stream()
                .map(t -> "`" + t + "`")
                .collect(Collectors.joining(", ")));
    }



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

        // §7.29: Prevent double-resume from concurrent callback taps.
        if (!statefulAgentStore.markBatchResuming(sessionId)) {
            log.infof("[BATCH-RESUME-GUARD] session=%s already resuming — " +
                      "ignoring duplicate trigger.", sessionId);
            return "⏳ Already processing your approval. Please wait.";
        }

        try {
            final String resumeGoal =
                checkpointResumeHandler.resolveResumeGoal(sessionId, agentRole);
            context.setResumeSignal(true);
            context.setGoal(resumeGoal);
            AgentResult result = WorkspaceContext.runWithFullAutonomy(
                sessionId, userId, chatId, agentRole,
                () -> agentLoop.run(
                    sessionId, agentRole, resumeGoal, reasoningEngine, 5, context));
            return AgentResultFormatter.format(result);
        } finally {
            // Always clear — success or exception.
            statefulAgentStore.clearBatchResuming(sessionId);
        }
    }

    private String handleCronDenial(String cronId, String userId, String chatId, MessageContext context) throws Exception {
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
            return "❌ No pending checkpoint found for this task. It may have already been processed.";
        }
        String cronSessionId = pendingCheckpoint.getSessionId();

        // §7.29: Prevent double-resume from concurrent callback taps.
        if (!statefulAgentStore.markBatchResuming(cronSessionId)) {
            log.infof("[BATCH-RESUME-GUARD] cronSession=%s already resuming — ignoring duplicate trigger.", cronSessionId);
            return "⏳ Already processing your approval. Please wait.";
        }

        try {
            context.setResumeSignal(true);
            context.setGoal(AgentSentinels.GOAL_DENY_APPROVED);

            // "denial" keyword no longer needed as trigger, we use resumeSignal
            AgentResult result = WorkspaceContext.run(
                    cronSessionId, cron.userId, cron.chatId,
                    AutonomyLevel.BACKGROUND, AgentRoleEnum.COORDINATOR.toScopedName(),
                    () -> agentLoop.run(
                            cronSessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                            AgentSentinels.GOAL_DENY_APPROVED,
                            reasoningEngine, 1, context) // maxIterations=1 — denial returns immediately
            );

            cronRepository.markFailed(cron.id, "User denied tool execution.");

            // Fallback to TELEGRAM for legacy rows missing channelType
            ChannelType chType = ChannelType.fromString(cron.channelType).orElse(ChannelType.TELEGRAM);

            String platformId = cron.channelPlatformId != null ? cron.channelPlatformId : cron.chatId;

            jobCompletedEvent.fire(new AgentJobCompleted(
                    cron.id, cron.userId, chType, platformId, cron.lastDeliveryId,
                    AgentResultFormatter.format(result),
                    null));

            return "🚫 Cron task denied and cancelled.";
        } finally {
            statefulAgentStore.clearBatchResuming(cronSessionId);
        }
    }

    private String handleDelegateDenial(
            String targetSid, String adminId, String adminChatId, MessageContext context) throws Exception {

        log.infof("[DELEGATE-DENY] admin=%s denying session=%s", adminId, targetSid);

        String activeRole = null;
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            Optional<AgentCheckpoint> cpOpt =
                statefulAgentStore.load(targetSid, role.toScopedName());
            if (cpOpt.isPresent()
                    && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                activeRole = role.toScopedName();
                break;
            }
        }

        if (activeRole == null) {
            return "❌ Target session not found or not awaiting approval.";
        }

        AgentCheckpoint cp = statefulAgentStore.load(targetSid, activeRole).orElseThrow(() -> new IllegalStateException(
                        "Checkpoint disappeared between lookup and load for session " + targetSid));

        // Auto-deny all tools in batch if parallel (§7.21, §7.33)
        if (cp.hasBatchToolDecisions()) {
            autoDenyAllPending(targetSid, activeRole);
        }

        // Log the delegated action §7.33
        auditService.logSecurityEvent(
            SecurityEventType.DELEGATE_APPROVAL,
            adminId,
            "Admin %s denied session %s".formatted(adminId, targetSid));

        // Trigger resume into the admin's chat context §7.33
        return triggerBatchResume(targetSid, adminId, adminChatId, activeRole, context);
    }
}
