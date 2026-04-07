package com.arcclaw.app.scheduler;

import java.time.Instant;
import java.util.Collections;
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
        // Background/cron sessions are intentionally left UNOWNED so they appear via
        // getUnownedSessionIds() for all authenticated UI users. Binding to job.userId
        // (Telegram user ID e.g. 945307929) would make them invisible because the UI
        // authenticates with an API key whose identity doesn't match the Telegram ID.
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

            String systemNote = 
                "[SYSTEM CONSTRAINT: CRON EXECUTION MODE. " +
                "Do NOT call any tools unless the goal explicitly requires data retrieval (github, memory_search). " +
                "For message delivery or greeting goals, use the FINISH action immediately with the message text as your answer. " +
                "The framework delivers your output — you do NOT need to call any tool to 'send' it. " +
                "NEVER output empty text. NEVER call schedule_task.]" +
                "\n\n## GitHub Tool Result Handling (CRON MODE)" +
                "\nWhen you call the github tool and receive a repository description:" +
                "\n- The tool may return rich text (Markdown or HTML) including headings, bold text, links, and badges." +
                "\n- DO NOT try to treat this description as JSON or as a function schema. It is plain text content." +
                "\n- If the tool call succeeds but the provider reports tool_use_failed with a failed_generation field that already contains the repository description:" +
                "\n  - Extract the human-readable description text from failed_generation." +
                "\n  - Summarize it into 1–3 concise bullet points for the user." +
                "\n  - Then FINISH with that summary as your answer." +
                "\n- Only treat a tool call as a hard failure if there is no description text and the error is a network/auth problem." +
                "\n- NEVER retry the same GitHub call more than once in CRON mode if description text is already present.";

            ChannelType type = ChannelType.fromString(job.channelType).orElse(ChannelType.TELEGRAM);

            String role = resolveRole(job);
            AgentResult result = WorkspaceContext.run(
                cronSessionId, 
                job.userId, 
                job.chatId, 
                AutonomyLevel.SUPERVISED,
                role, 
                type,
                () -> {
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
                }
            );

            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        }
    }

    private String resolveRole(AgentCronEntity job) {
        String normalized = job.goal.replaceFirst("^CRON\\s+", "").toLowerCase();
        
        // Output-only goals: unconditionally override to coordinator
        if (normalized.startsWith("output") || normalized.startsWith("say ")
                || normalized.startsWith("send ") || normalized.startsWith("tell ")) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        if (job.executionRole != null && !job.executionRole.isBlank()) {
            return job.executionRole;
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
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, "cron-" + job.id, Instant.now());
        return new MessageContext(job.chatId, user, msg);
    }
}
