package com.amsha.core.reflex;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.CronRunResultMapper;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;

@Dependent
public class CronDecisionRouter {

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CronStorePort cronStore;

    @Inject
    CronRunStorePort cronRunStore;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    ObjectMapper objectMapper;

    public String execute(CronContext ctx) throws Exception {
        String cronId = ctx.cronId;

        if (statefulAgentStore.isSessionActive(cronId)) {
            return null;
        }

        String resolvedSessionId = resolveSessionId(cronId);

        AgentCron cron = resolveCronJob(cronId, resolvedSessionId);
        if (cron == null) return "❌ Cron task not found.";

        String cronRole = resolveSessionRole(resolvedSessionId, cron);

        String deliveryId = cron.getLastDeliveryId();
        if (deliveryId == null || deliveryId.isBlank()) {
            deliveryId = UUID.randomUUID().toString();
            cron.setLastDeliveryId(deliveryId);
        }
        if (!cronStore.claimForResume(cron.getId(), deliveryId)) {
            return ctx.alreadyProcessingMsg().get();
        }

        AgentCheckpoint pendingCheckpoint;
        try {
            pendingCheckpoint = ctx.applyDecisionToAll().apply(resolvedSessionId, ctx.reason);
        } catch (Exception e) {
            cronStore.markAwaitingApprovalIfOwner(cron.getId(), deliveryId);
            throw e;
        }
        if (pendingCheckpoint == null || BatchDecisionUtil.hasPendingToolNames(pendingCheckpoint, objectMapper)) {
            cronStore.markAwaitingApprovalIfOwner(cron.getId(), deliveryId);
            return ctx.errorMsg().get();
        }
        String cronSessionId = pendingCheckpoint.getSessionId();
        String finalRole = pendingCheckpoint.getAgentRole() != null ? pendingCheckpoint.getAgentRole() : cronRole;

        if (!statefulAgentStore.claimForResume(cronSessionId, finalRole)) {
            cronStore.markAwaitingApprovalIfOwner(cron.getId(), deliveryId);
            return ctx.alreadyProcessingMsg().get();
        }

        AgentResult result = null;
        try {
            String goal = ctx.cronResumeGoal().get(cronSessionId, finalRole);
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
                    finalRole,
                    () -> agentLoop.run(
                            cronSessionId,
                            finalRole,
                            goal,
                            pendingCheckpoint.getSystemNote(),
                            reasoningEngine,
                            budget,
                            ctx.context));

            if (result == null) {
                markCronFailedIfOwner(cron.getId(), deliveryId, "Cron approval resume returned no result");
                return ctx.errorMsg().get();
            }

            ctx.postProcessCronDecision().accept(cron, ctx.reason, result);
            updateLatestRun(cronSessionId, result);
            ctx.jobNotifier().accept(cron, result);
            return ctx.successResponse().get(result);
        } catch (Exception e) {
            markCronFailedIfOwner(cron.getId(), deliveryId, LogUtil.rootMessage(e));
            throw e;
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

    private void markCronFailedIfOwner(String cronId, String deliveryId, String reason) {
        try {
            cronStore.markFailedIfOwner(cronId, deliveryId, reason);
        } catch (RuntimeException _) {
            // Preserve the original resume failure when persistence is unavailable.
        }
    }

    private boolean isTerminalResult(AgentResult result) {
        return result instanceof AgentResult.Finished
                || result instanceof AgentResult.StructuredFinished
                || result instanceof AgentResult.Failed
                || result instanceof AgentResult.LoopExceeded;
    }

    private void updateLatestRun(String cronSessionId, AgentResult result) {
        if (cronRunStore == null || cronSessionId == null || result == null) {
            return;
        }
        cronRunStore.findAll(CheckpointStatus.AWAITING_APPROVAL.name(), 0, 100).stream()
                .filter(run -> cronSessionId.equals(run.sessionId()))
                .findFirst()
                .ifPresent(run -> cronRunStore.save(CronRunResultMapper.fromResult(run, result)));
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

    private String resolveSessionRole(String resolvedSessionId, AgentCron cron) {
        if (resolvedSessionId != null) {
            Optional<AgentCheckpoint> cp = statefulAgentStore
                    .findLatestAwaitingApproval(resolvedSessionId)
                    .or(() -> statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(resolvedSessionId));
            if (cp.isPresent() && cp.get().getAgentRole() != null) {
                return cp.get().getAgentRole();
            }
        }
        return cron != null && cron.getExecutionRole() != null ? cron.getExecutionRole() : "researcher";
    }

    private AgentCron resolveCronJob(String cronId, String cronSessionId) {
        String jobId = extractJobId(cronId);
        AgentCron cron = cronStore.findById(jobId).map(AgentCronRecord::fromDto).orElse(null);
        if (cron == null && cronSessionId != null && cronSessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
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
