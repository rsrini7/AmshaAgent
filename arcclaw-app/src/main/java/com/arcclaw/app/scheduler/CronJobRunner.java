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
import org.jboss.logging.Logger;

/**
 * Orchestrates the execution of a single cron job, managing the workspace context,
 * secret seeding, and hand-off to AgentLoop.
 */
@ApplicationScoped
public class CronJobRunner {

    private static final Logger log = Logger.getLogger(CronJobRunner.class);

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
            
            // §Bug2: Short-circuit pure announcements to direct send, bypassing agent loop
            if (isPureAnnouncement(stripped)) {
                log.infof("CRON-SHORT-CIRCUIT cronId=%s goal='%s' (pure announcement)", job.id, stripped);
                AgentResult result = AgentResult.finished(stripped, Collections.emptyList());
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

    private boolean isPureAnnouncement(String goal) {
        if (goal == null || goal.isBlank()) return false;
        String normalized = goal.toLowerCase().trim();
        
        // Explicit announcement prefixes
        if (normalized.startsWith("output") || normalized.startsWith("say ")
                || normalized.startsWith("send ") || normalized.startsWith("tell ")) {
            return true;
        }

        // Check for lack of tool-like verbs (heuristic for simple statements)
        // Announcements typically don't have "fetch", "search", "check", "monitor", "run", "execute"
        boolean hasActionVerb = normalized.contains("fetch") || normalized.contains("search") 
                || normalized.contains("check") || normalized.contains("monitor") 
                || normalized.contains("run") || normalized.contains("execute")
                || normalized.contains("investigate") || normalized.contains("analyze");
        
        return !hasActionVerb && goal.length() < 200; // Small messages without verbs are likely announcements
    }

    private String resolveRole(AgentCronEntity job) {
        // Priority 0 — Explicit stored role always wins
        if (job.executionRole != null && !job.executionRole.isBlank()) {
            return job.executionRole;
        }

        String normalized = job.goal != null ? job.goal.replaceFirst("^CRON\\s+", "").toLowerCase() : "";
        
        // Output-only goals: unconditionally override to coordinator
        if (normalized.startsWith("output") || normalized.startsWith("say ")
                || normalized.startsWith("send ") || normalized.startsWith("tell ")) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        // Priority 1 — Coordinator (Meta-scheduling / goal orchestration)
        if (normalized.contains("coordinator task") || normalized.contains("meta-schedule") || normalized.contains("schedule next")) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }
        
        // Priority 2 — Specific agent hints
        if (normalized.contains("researcher task") || normalized.contains("fetch") || normalized.contains("search") || normalized.contains("discovery")) {
            return AgentRoleEnum.RESEARCHER.toScopedName();
        } else if (normalized.contains("coder task") || normalized.contains("write code") || normalized.contains("fix bug") || normalized.contains("refactor")) {
            return AgentRoleEnum.CODER.toScopedName();
        }
        
        // Default to researcher for cron tasks as most involve retrieval/monitoring
        return AgentRoleEnum.RESEARCHER.toScopedName();
    }

    private MessageContext createCronContext(AgentCronEntity job, String sessionId, String executionId, String goal, ChannelType channelType) {
        ChannelUser user = new ChannelUser(job.userId, job.userId, "Cron User", channelType);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, AgentSentinels.CRON_SESSION_PREFIX + job.id, Instant.now());
        return new MessageContext(job.chatId, user, msg);
    }
}
