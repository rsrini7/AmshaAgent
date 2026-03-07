package com.arcclaw.app.scheduler;

import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.LangChainReasoningEngine;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
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
    DevSecretSeeder devSeeder;

    @Inject
    AgentCronRepository cronRepository;

    @ConfigProperty(name = "arcclaw.cron.max-iterations", defaultValue = "2")
    int cronMaxIterations;

    public void execute(AgentCronEntity job) {
        String executionId = UUID.randomUUID().toString();
        
        // CRITICAL: set delivery ID first so ApproveReflex can find the session if setup fails later
        cronRepository.setDeliveryId(job.id, executionId);

        String cronSessionId = "cron-" + job.id + "-" + executionId;
        
        try {
            // Seed development secrets for this isolated background thread
            devSeeder.seedDevSecrets(); // no-op when auto-seed-secrets=false

            String effectiveGoal = job.goal + 
                "\n[SYSTEM CONSTRAINT: Do NOT call scheduletask. Execute the goal directly.]";

            AgentResult result = WorkspaceContext.run(
                cronSessionId, 
                job.userId, 
                job.chatId, 
                AutonomyLevel.BACKGROUND, 
                AgentRoleEnum.COORDINATOR.toScopedName(), 
                () -> agentLoop.run(
                    cronSessionId,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    effectiveGoal,
                    engine,
                    cronMaxIterations
                )
            );

            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        }
    }
}
