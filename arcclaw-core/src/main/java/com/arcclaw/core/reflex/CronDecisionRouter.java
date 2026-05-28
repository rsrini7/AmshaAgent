package com.arcclaw.core.reflex;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.BiConsumer;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.domain.model.AgentCronRecord;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.persistence.api.CronStorePort;

@Dependent
public class CronDecisionRouter {

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CronStorePort cronStore;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    WorkspaceManager workspaceManager;

    public String execute(CronContext ctx) throws Exception {
        String cronId = ctx.cronId;

        if (statefulAgentStore.isSessionActive(cronId)) {
            return null;
        }

        String resolvedSessionId = resolveSessionId(cronId);

        AgentCheckpoint pendingCheckpoint = ctx.applyDecisionToAll().apply(resolvedSessionId, ctx.reason);
        String cronSessionId = pendingCheckpoint.getSessionId();
        String cronRole = pendingCheckpoint.getAgentRole();

        AgentCron cron = resolveCronJob(cronId, cronSessionId);
        if (cron == null) return "❌ Cron task not found.";

        if (!statefulAgentStore.claimForResume(cronSessionId, cronRole)) {
            return ctx.alreadyProcessingMsg().get();
        }

        AgentResult result = null;
        try {
            String goal = ctx.cronResumeGoal().get(cronSessionId, cronRole);
            ctx.context.setResumeSignal(true);
            ctx.context.setGoal(goal);

            int budget = pendingCheckpoint.getMaxIterations() != null
                    ? Math.max(3, pendingCheckpoint.getMaxIterations())
                    : 3;

            result = WorkspaceContext.run(
                    cronSessionId,
                    cron.getUserId(),
                    cron.getChatId(),
                    AutonomyLevel.SUPERVISED,
                    cronRole,
                    () -> agentLoop.run(
                            cronSessionId,
                            cronRole,
                            goal,
                            pendingCheckpoint.getSystemNote(),
                            reasoningEngine,
                            budget,
                            ctx.context));

            if (result == null) {
                return ctx.errorMsg().get();
            }

            ctx.postProcessCronDecision().accept(cron, ctx.reason, result);
            ctx.jobNotifier().accept(cron, result);
            return ctx.successResponse().get(result);
        } finally {
            statefulAgentStore.clearBatchResuming(cronSessionId);
            if (result != null && isTerminalResult(result)) {
                try {
                    workspaceManager.terminateSession(cronSessionId);
                } catch (Exception e) {
                    /* best effort */
                }
            }
        }
    }

    private boolean isTerminalResult(AgentResult result) {
        return result instanceof AgentResult.Finished
                || result instanceof AgentResult.StructuredFinished
                || result instanceof AgentResult.Failed
                || result instanceof AgentResult.LoopExceeded;
    }

    private String resolveSessionId(String cronId) {
        String segment = cronId;
        if (cronId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            segment = cronId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
        }
        if (segment.length() >= 36) {
            segment = segment.substring(0, 36);
        }
        Optional<AgentCheckpoint> match = statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(segment);
        return match.map(AgentCheckpoint::getSessionId).orElse(cronId);
    }

    private AgentCron resolveCronJob(String cronId, String cronSessionId) {
        String jobId = extractJobId(cronId);
        AgentCron cron = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        if (cron == null && cronSessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            jobId = extractJobId(cronSessionId);
            cron = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        }
        return cron;
    }

    private static String extractJobId(String id) {
        String segment = id;
        if (id.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            segment = id.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
        }
        return segment.length() >= 36 ? segment.substring(0, 36) : segment;
    }

    public record CronContext(
            String cronId,
            String userId,
            String chatId,
            String reason,
            MessageContext context,
            DecisionApplier applyDecisionToAll,
            MessageProvider alreadyProcessingMsg,
            GoalProvider cronResumeGoal,
            PostDecisionHandler postProcessCronDecision,
            ResultMessageProvider successResponse,
            MessageProvider errorMsg,
            BiConsumer<AgentCron, AgentResult> jobNotifier) {
        @FunctionalInterface
        public interface DecisionApplier {
            AgentCheckpoint apply(String sessionId, String reason);
        }

        @FunctionalInterface
        public interface MessageProvider {
            String get();
        }

        @FunctionalInterface
        public interface GoalProvider {
            String get(String cronSessionId, String cronRole);
        }

        @FunctionalInterface
        public interface PostDecisionHandler {
            void accept(AgentCron cron, String reason, AgentResult result);
        }

        @FunctionalInterface
        public interface ResultMessageProvider {
            String get(AgentResult result);
        }
    }
}
