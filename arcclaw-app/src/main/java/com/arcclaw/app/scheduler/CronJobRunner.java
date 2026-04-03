package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Orchestrates the execution of a single cron job, managing the workspace context,
 * secret seeding, and hand-off to AgentLoop.
 */
@ApplicationScoped
public class CronJobRunner {

    @Inject
    AgentLoop agentLoop;

    @Inject
    LangChainReasoningEngine engine;

    @Inject
    CronResultHandler resultHandler;

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @ConfigProperty(name = "arcclaw.cron.max-iterations", defaultValue = "3")
    int cronMaxIterations;

    public void execute(AgentCronEntity job) {
        String executionId = UUID.randomUUID().toString();
        
        // ORDERING CRITICAL: set delivery ID before workspace context is created.
        // ApproveReflex.handleCronApproval() uses findByUserId() + deliveryId to locate
        // the checkpoint for this execution. If this is moved after WorkspaceContext.run(),
        // approval routing will break if setup fails mid-execution.
        cronRepository.setDeliveryId(job.id, executionId);

        String cronSessionId = "cron-" + job.id + "-" + executionId;

        // Register session in heartbeat tracker so it is visible in the UI Approvals tab.
        // Background jobs use BACKGROUND autonomy as a starting point.
        heartbeatTracker.recordHeartbeat(cronSessionId, AutonomyLevel.BACKGROUND);
        if (job.userId != null) {
            heartbeatTracker.tryBindOwner(cronSessionId, job.userId);
        }
        
        try {
            String effectiveGoal = job.goal + 
                "\n[SYSTEM CONSTRAINT: CRON EXECUTION MODE. " +
                "Do NOT call any tools unless the goal explicitly requires data retrieval (github, memory_search). " +
                "For message delivery or greeting goals, use the FINISH action immediately with the message text as your answer. " +
                "The framework delivers your output — you do NOT need to call any tool to 'send' it. " +
                "NEVER output empty text. NEVER call schedule_task.]";

            ChannelType type = ChannelType.fromString(job.channelType).orElse(ChannelType.TELEGRAM);

            AgentResult result = WorkspaceContext.run(
                cronSessionId, 
                job.userId, 
                job.chatId, 
                AutonomyLevel.BACKGROUND, 
                AgentRoleEnum.COORDINATOR.toScopedName(), 
                type,
                () -> {
                    MessageContext context = createCronContext(job, cronSessionId, executionId, effectiveGoal, type);
                    return agentLoop.run(
                        cronSessionId,
                        AgentRoleEnum.COORDINATOR.toScopedName(),
                        effectiveGoal,
                        engine,
                        cronMaxIterations,
                        context
                    );
                }
            );

            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        }
    }

    private MessageContext createCronContext(AgentCronEntity job, String sessionId, String executionId, String goal, ChannelType channelType) {
        ChannelUser user = new ChannelUser(job.userId, job.userId, "Cron User", channelType);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, "cron-" + job.id, Instant.now());
        return new MessageContext(job.chatId, user, msg);
    }
}
