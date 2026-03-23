package com.arcclaw.core.reflex;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.CronResultFormatter;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.util.CronScheduleUtil;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

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
    DevSecretSeeder devSeeder;

    @Inject
    WorkspaceManager workspaceManager;

    @Override
    public boolean matches(String input) {
        ChannelCommand cmd = ChannelCommand.parse(input);
        return cmd instanceof ChannelCommand.Approve;
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        ChannelCommand.Approve approve = (ChannelCommand.Approve) ChannelCommand.parse(input);
        Optional<String> cronId = approve.cronId();

        try {
            String response = WorkspaceScopeHolder.call(sessionId, () -> {
                // Seed development secrets for the approval thread
                devSeeder.seedDevSecrets(); // no-op when auto-seed-secrets=false

                // 1. CronId explicit — always wins (Fix 3)
                if (cronId.isPresent()) {
                    return handleCronApproval(cronId.get(), userId, chatId, context);
                }

                // 2. Session checkpoint — interactive approval
                String activeRole = null;
                for (AgentRoleEnum role : AgentRoleEnum.values()) {
                    Optional<AgentCheckpoint> cpOpt = statefulAgentStore.load(sessionId, role.toScopedName());
                    if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                        activeRole = role.toScopedName();
                        break;
                    }
                }

                if (activeRole != null) {
                    log.infof("[ApproveReflex] User approved pending tool execution for session: %s", sessionId);
                    
                    context.setResumeSignal(true);
                    context.setGoal(AgentSentinels.GOAL_RESUME_APPROVED);

                    final String finalRole = activeRole;
                    AgentResult result = WorkspaceContext.runWithFullAutonomy(
                            sessionId, userId, chatId, finalRole,
                            () -> agentLoop.run(sessionId, finalRole, AgentSentinels.GOAL_RESUME_APPROVED, reasoningEngine, 5, context)
                    );
                    return CronResultFormatter.format(result);
                }

                // 3. No session checkpoint — Fix 1 guidance
                List<AgentCronEntity> pending = cronRepository.findAwaitingApprovalByUserId(userId);
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

                // 4. Nothing pending anywhere
                return "No pending tool executions require approval.";
            });
            return ReflexResult.respond(response, context);
        } catch (Exception e) {
            log.errorf("Failed to process approval for session %s: %s", sessionId, e.getMessage(), e);
            return ReflexResult.respond("❌ **Approval Error**: An internal fault occurred while resuming the task.", context);
        }
    }

    private String handleCronApproval(String cronId, String userId, String chatId, MessageContext context) throws Exception {
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

        // Look up the actual checkpoint to get the real cronSessionId
        // instead of reconstructing it from cron.lastDeliveryId which may be stale
        var pendingCheckpoint = statefulAgentStore
                .findLatestAwaitingApprovalForCron(cronId)
                .orElse(null);

        if (pendingCheckpoint == null) {
            return "❌ No pending checkpoint found for this task. It may have already been processed.";
        }

        String cronSessionId = pendingCheckpoint.getSessionId(); // ← actual sessionId

        context.setResumeSignal(true);
        context.setGoal(AgentSentinels.GOAL_RESUME_APPROVED);

        AgentResult result = WorkspaceContext.run(
                cronSessionId, cron.userId, cron.chatId,
                AutonomyLevel.BACKGROUND, AgentRoleEnum.COORDINATOR.toScopedName(),
                () -> agentLoop.run(
                        cronSessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                        AgentSentinels.GOAL_RESUME_APPROVED, reasoningEngine, 5, context)
        );

        // Exhaustive status transition
        switch (result) {
            case AgentResult.Finished _ -> {
                if (cron.runOnce) cronRepository.markCompleted(cron.id, Instant.now());
                else cronRepository.markFired(cron.id, Instant.now(), CronScheduleUtil.computeNext(cron.cronExpression, cron.timezone));
            }
            case AgentResult.Failed f -> cronRepository.markFailed(cron.id, f.reason());
            case AgentResult.AwaitingApproval _ -> cronRepository.markAwaitingApproval(cron.id);
            case AgentResult.LoopExceeded _ -> cronRepository.markFailed(cron.id, "Loop exceeded");
        }

        // Fallback to TELEGRAM for legacy rows missing channelType
        ChannelType chType = ChannelType.fromString(cron.channelType).orElse(ChannelType.TELEGRAM);

        String platformId = cron.channelPlatformId != null ? cron.channelPlatformId : cron.chatId;

        jobCompletedEvent.fire(new AgentJobCompleted(
                cron.id, cron.userId, chType, platformId, cron.lastDeliveryId,
                CronResultFormatter.format(result),
                result instanceof AgentResult.Failed f ? f.category() : null));

        try {
            workspaceManager.terminateSession(cronSessionId);
        } catch (Exception e) {
            log.warnf("Failed to terminate cron session %s after approval: %s",
                    cronSessionId, e.getMessage());
        }                

        return "";
    }
}
