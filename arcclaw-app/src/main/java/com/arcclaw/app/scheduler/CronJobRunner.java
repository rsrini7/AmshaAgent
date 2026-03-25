package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    @ConfigProperty(name = "arcclaw.cron.max-iterations", defaultValue = "2")
    int cronMaxIterations;

    public void execute(AgentCronEntity job) {
        String executionId = UUID.randomUUID().toString();
        
        // ORDERING CRITICAL: set delivery ID before workspace context is created.
        // ApproveReflex.handleCronApproval() uses findByUserId() + deliveryId to locate
        // the checkpoint for this execution. If this is moved after WorkspaceContext.run(),
        // approval routing will break if setup fails mid-execution.
        cronRepository.setDeliveryId(job.id, executionId);

        String cronSessionId = "cron-" + job.id + "-" + executionId;
        
        try {
            String effectiveGoal = job.goal + 
                "\n[SYSTEM CONSTRAINT: Do NOT call schedule_task. Execute the goal directly.]";

            AgentResult result = WorkspaceContext.run(
                cronSessionId, 
                job.userId, 
                job.chatId, 
                AutonomyLevel.BACKGROUND, 
                AgentRoleEnum.COORDINATOR.toScopedName(), 
                () -> {
                    MessageContext context = createCronContext(job, cronSessionId, executionId, effectiveGoal);
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

    private MessageContext createCronContext(AgentCronEntity job, String sessionId, String executionId, String goal) {
        ChannelUser user = new ChannelUser(job.userId, job.userId, "Cron User", ChannelType.REST_API); // Cron uses REST-like fallback for identity
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, "cron-" + job.id, Instant.now());
        ChannelSender noOpSender = new ChannelSender() {
            @Override public ChannelType supports() { return ChannelType.REST_API; }
            @Override public void send(ChannelUser recipient, List<ReplyNode> nodes) { }
            @Override public void sendRaw(ChannelUser recipient, String text) { }
        };
        return new MessageContext(job.chatId, user, msg, noOpSender);
    }
}
