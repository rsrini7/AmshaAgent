package com.arcclaw.core.reflex;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.CronResultFormatter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Override
    public boolean matches(String input) {
        ChannelCommand cmd = ChannelCommand.parse(input);
        return cmd instanceof ChannelCommand.Deny;
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
        ChannelCommand.Deny deny = (ChannelCommand.Deny) ChannelCommand.parse(input);
        Optional<String> cronId = deny.cronId();

        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                // 1. CronId explicit — always wins (Fix 3)
                if (cronId.isPresent()) {
                    return handleCronDenial(cronId.get(), userId, chatId);
                }

                // 2. Session checkpoint — interactive approval
                String activeRole = null;
                for (com.arcclaw.core.agent.AgentRoleEnum role : com.arcclaw.core.agent.AgentRoleEnum.values()) {
                    Optional<AgentCheckpoint> cpOpt = statefulAgentStore.load(sessionId, role.toScopedName());
                    if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                        activeRole = role.toScopedName();
                        break;
                    }
                }

                if (activeRole != null) {
                    log.infof("[DenyReflex] User denied pending tool execution for session: %s", sessionId);
                    AgentCheckpoint cp = statefulAgentStore.load(sessionId, activeRole).orElseThrow();
                    cp.setStatus(CheckpointStatus.FAILED);
                    cp.setFailReason("Human denied the tool execution.");
                    statefulAgentStore.save(cp);
                    return "🚫 Execution denied. The agent's task has been terminated.";
                }

                // 3. No session checkpoint — Fix 1 guidance
                List<AgentCronEntity> pending = cronRepository.findAwaitingApprovalByUserId(userId);
                if (pending.size() == 1) {
                    AgentCronEntity cron = pending.get(0);
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
        } catch (Exception e) {
            log.errorf("Failed to process denial for session %s: %s", sessionId, e.getMessage(), e);
            return "❌ **Denial Error**: An internal fault occurred while terminating the task.";
        }
    }

    private String handleCronDenial(String cronId, String userId, String chatId) throws Exception {
        AgentCronEntity cron = cronRepository.findByUserId(userId).stream()
                .filter(c -> c.id.equals(cronId))
                .findFirst()
                .orElse(null);

        if (cron == null) {
            return "❌ Cron task not found or not yours.";
        }

        if (!"AWAITING_APPROVAL".equals(cron.status)) {
            return "❌ This task is not awaiting approval (status=" + cron.status + ").";
        }

        String cronSessionId = "cron-" + cron.id + "-" + cron.lastDeliveryId;

        // "denial" keyword triggers isDenial branch in handleResumeHook
        AgentResult result = WorkspaceContext.run(
                cronSessionId, cron.userId, cron.chatId,
                AutonomyLevel.BACKGROUND, AgentRoleEnum.COORDINATOR.toScopedName(),
                () -> agentLoop.run(
                        cronSessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                        "Resuming task after denial.",  // triggers isDenial in handleResumeHook
                        reasoningEngine, 1) // maxIterations=1 — denial returns immediately
        );

        cronRepository.markFailed(cron.id, "User denied tool execution.");

        ChannelType chType = ChannelType.valueOf(cron.channelType != null ? cron.channelType : "TELEGRAM");
        String platformId = cron.channelPlatformId != null ? cron.channelPlatformId : cron.chatId;

        jobCompletedEvent.fire(new AgentJobCompleted(
                cron.id, cron.userId, chType, platformId, cron.lastDeliveryId,
                CronResultFormatter.format(result),
                null));

        return "🚫 Cron task denied and cancelled.";
    }
}
