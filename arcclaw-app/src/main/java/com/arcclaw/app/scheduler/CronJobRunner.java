package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.AgentSentinels;
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
        
        cronRepository.setDeliveryId(job.id, executionId);

        String cronSessionId = AgentSentinels.CRON_SESSION_PREFIX + job.id + "-" + executionId;

        heartbeatTracker.recordHeartbeat(cronSessionId, AutonomyLevel.BACKGROUND);
        
        try {
            String stripped = job.goal != null ? job.goal.replaceFirst("^CRON\\s+", "").trim() : "";
            String normalized = stripped.toLowerCase();
            
            if (normalized.startsWith("output the following message:") || normalized.startsWith("say ") 
                    || normalized.startsWith("tell ") || normalized.startsWith("send message:") || normalized.startsWith("send text:")) {
                String text = stripped;
                if (normalized.startsWith("output the following message:")) {
                    text = stripped.substring("output the following message:".length()).trim();
                } else if (normalized.startsWith("say ")) {
                    text = stripped.substring("say ".length()).trim();
                } else if (normalized.startsWith("tell ")) {
                    text = stripped.substring("tell ".length()).trim();
                } else if (normalized.startsWith("send message:")) {
                    text = stripped.substring("send message:".length()).trim();
                } else if (normalized.startsWith("send text:")) {
                    text = stripped.substring("send text:".length()).trim();
                }
                
                AgentResult result = AgentResult.finished(text, Collections.emptyList());
                resultHandler.handleSuccess(job, cronSessionId, executionId, result);
                return;
            }

            String systemNote = "CRON MODE";

            ChannelType type = ChannelType.fromString(job.channelType).orElse(ChannelType.TELEGRAM);
            String role = resolveRole(job);
            String channel = type.name();

            Callable<AgentResult> op = () -> {
                MessageContext context = createCronContext(job, cronSessionId, executionId, job.goal, type);
                return agentLoop.run(
                    cronSessionId,
                    role,
                    job.goal,
                    systemNote,
                    engine,
                    cronMaxIterations,
                    context
                );
            };

            AgentResult result = WorkspaceContext.<AgentResult>run(
                cronSessionId, job.userId, job.chatId, AutonomyLevel.SUPERVISED, role, channel, op
            );

            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        }
    }

    private String resolveRole(AgentCronEntity job) {
        return AgentRoleEnum.RESEARCHER.toScopedName();
    }

    private MessageContext createCronContext(AgentCronEntity job, String sessionId, String executionId, String goal, ChannelType channelType) {
        ChannelUser user = new ChannelUser(job.userId, job.userId, "Cron User", channelType);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, AgentSentinels.CRON_SESSION_PREFIX + job.id, Instant.now());
        return new MessageContext(job.chatId, user, msg);
    }
}
