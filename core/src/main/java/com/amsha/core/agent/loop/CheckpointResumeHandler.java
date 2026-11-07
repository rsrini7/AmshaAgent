package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.speech.SpeechDeliveryService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.AgentGuards;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Handles the orchestration of session resumption after human-in-the-loop approval.
 * Delegates specific resumption strategies to ResumeStrategyDispatcher.
 */
@ApplicationScoped
public class CheckpointResumeHandler {

    private static final Logger log = Logger.getLogger(CheckpointResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final AgentLoop agentLoop;
    private final ReasoningEngine reasoningEngine;
    private final ChannelPluginRegistry channelPluginRegistry;
    private final CronStorePort cronStore;
    private final CronRunStorePort cronRunStore;
    private final ObjectMapper objectMapper;
    private final ResumeStrategyDispatcher strategyDispatcher;
    private final CheckpointResumeResultDispatcher resultDispatcher;
    ResumeCronNotificationDispatcher cronNotificationDispatcher;

    protected CheckpointResumeHandler() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public CheckpointResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            AgentLoop agentLoop,
            ReasoningEngine reasoningEngine,
            ChannelPluginRegistry channelPluginRegistry,
            CronStorePort cronStore,
            CronRunStorePort cronRunStore,
            CliResultBridge cliResultBridge,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService) {
        this(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                reasoningEngine,
                channelPluginRegistry,
                cronStore,
                cronRunStore,
                cliResultBridge,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
                null,
                null,
                null,
                null);
    }

    public CheckpointResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            AgentLoop agentLoop,
            ReasoningEngine reasoningEngine,
            ChannelPluginRegistry channelPluginRegistry,
            CronStorePort cronStore,
            CronRunStorePort cronRunStore,
            CliResultBridge cliResultBridge,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard,
            ResumeCronNotificationDispatcher cronNotificationDispatcher) {
        this(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                reasoningEngine,
                channelPluginRegistry,
                cronStore,
                cronRunStore,
                cliResultBridge,
                recoveryService,
                escalationService,
                delegationResolver,
                guard,
                cronNotificationDispatcher,
                null,
                null);
    }

    @Inject
    public CheckpointResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            AgentLoop agentLoop,
            ReasoningEngine reasoningEngine,
            ChannelPluginRegistry channelPluginRegistry,
            CronStorePort cronStore,
            CronRunStorePort cronRunStore,
            CliResultBridge cliResultBridge,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard,
            ResumeCronNotificationDispatcher cronNotificationDispatcher,
            SequentialAttemptReconciler attemptReconciler,
            ParallelBatchAttemptReconciler batchReconciler) {
        this.agentStore = agentStore;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.channelPluginRegistry = channelPluginRegistry;
        this.cronStore = cronStore;
        this.cronRunStore = cronRunStore;
        this.objectMapper = objectMapper;
        this.cronNotificationDispatcher = cronNotificationDispatcher != null
                ? cronNotificationDispatcher
                : (cronStore == null
                        ? null
                        : new ResumeCronNotificationDispatcher(cronStore, channelPluginRegistry, null));
        this.resultDispatcher = new CheckpointResumeResultDispatcher(
                cronStore,
                cronRunStore,
                () -> this.cronNotificationDispatcher,
                cliResultBridge,
                channelPluginRegistry,
                () -> speechDeliveryService);
        this.strategyDispatcher = (agentStore == null || toolRegistry == null)
                ? null
                : new ResumeStrategyDispatcher(
                        agentStore,
                        toolRegistry,
                        objectMapper,
                        reasoningEngine,
                        recoveryService,
                        escalationService,
                        delegationResolver,
                        guard,
                        attemptReconciler,
                        batchReconciler);
    }

    @Inject
    ManagedExecutor executor;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    SpeechDeliveryService speechDeliveryService;

    @ActivateRequestContext
    public boolean resumeAfterBatchDecision(
            String sessionId, String agentRole, String userId, String chatId, String channelType) {

        if (AgentGuards.isShuttingDown()) {
            log.warnf("Container shutting down - cannot resume session %s", sessionId);
            return false;
        }

        if (!agentStore.claimForResume(sessionId, agentRole)) {
            log.infof("[BATCH-RESUME-GUARD] session=%s already resuming - skipping duplicate.", sessionId);
            return false;
        }

        AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
        if (checkpoint == null) {
            log.warnf("[RESUME-GUARD] checkpoint null after claim for session=%s - releasing DB state.", sessionId);
            releaseResumeClaimSafely(sessionId, agentRole);
            return false;
        }

        try {
            if (!CronSessionStatusSync.syncRunning(sessionId, cronStore)) {
                releaseResumeClaimSafely(sessionId, agentRole);
                return false;
            }
            WorkspaceScopeHolder.run(
                    sessionId,
                    userId,
                    chatId,
                    () -> executor.execute(WorkspaceScopeHolder.wrap(() -> {
                        try {
                            MessageContext context = null;
                            try {
                                String resumeGoal = strategyDispatcher.resolveResumeGoal(sessionId, agentRole);
                                if (resumeGoal == null || resumeGoal.isBlank()) {
                                    log.warnf(
                                            "resumeAfterBatchDecision: no resume goal found for session %s role %s",
                                            sessionId, agentRole);
                                } else {
                                    ChannelType cType = (channelType == null || channelType.isBlank())
                                            ? ChannelType.WEBSOCKET
                                            : ChannelType.of(channelType);
                                    ChannelUser user = new ChannelUser(userId, chatId, "Operator", cType);
                                    context = resultDispatcher.prepareResumeMessageContext(
                                            chatId, user, cType, resumeGoal, checkpoint, objectMapper);

                                    String toolInfo = ResumeToolInfoFormatter.format(checkpoint, objectMapper);
                                    AgentResult result = runResumeLoop(context, checkpoint, resumeGoal);
                                    if (!tryResumeDelegatingParent(sessionId, result) && result != null) {
                                        dispatchResumeResult(context, result, cType, user, sessionId, toolInfo);
                                    }
                                }
                            } catch (Exception e) {
                                handleResumeFailure(sessionId, context, e);
                            }
                        } finally {
                            releaseResumeClaimSafely(sessionId, agentRole);
                        }
                    })));
            return true;
        } catch (RejectedExecutionException e) {
            CronSessionStatusSync.syncTerminal(
                    sessionId,
                    AgentResult.failed(
                            "Resume executor rejected the session",
                            AgentResult.FailureCategory.SYSTEM_ERROR,
                            List.of()),
                    cronStore,
                    cronRunStore);
            releaseResumeClaimSafely(sessionId, agentRole);
            log.warnf("resumeAfterBatchDecision: executor rejected session %s: %s", sessionId, LogUtil.rootMessage(e));
            return false;
        } catch (RuntimeException e) {
            releaseResumeClaimSafely(sessionId, agentRole);
            log.warnf("resumeAfterBatchDecision: failed to dispatch session %s: %s", sessionId, LogUtil.rootMessage(e));
            return false;
        }
    }

    private boolean tryResumeDelegatingParent(String sessionId, AgentResult result) {
        var handoff = new DelegatedCompletionHandoff(agentStore, objectMapper).capture(sessionId, result);
        if (handoff.isPresent() && handoff.get().ready()) {
            DelegatedCompletionHandoff.ParentResume p = handoff.get();
            log.infof("[DELEGATION] Resuming parent %s after child %s completed", p.sessionId(), sessionId);
            resumeAfterBatchDecision(p.sessionId(), p.agentRole(), p.userId(), p.chatId(), p.channelType());
            return true;
        }
        return false;
    }

    private void releaseResumeClaimSafely(String sessionId, String agentRole) {
        try {
            agentStore.releaseResumeClaim(sessionId, agentRole);
        } catch (RuntimeException e) {
            log.warnf(
                    "resumeAfterBatchDecision: failed to release resume claim for session %s: %s",
                    sessionId, LogUtil.rootMessage(e));
        } finally {
            agentStore.clearBatchResuming(sessionId);
        }
    }

    private AgentResult runResumeLoop(MessageContext context, AgentCheckpoint cp, String goal) {
        try {
            int budget = calculateResumeBudget(cp);
            log.infof("Resuming session=%s budget=%d", cp.getSessionId(), budget);

            return WorkspaceContext.run(
                    cp.getSessionId(),
                    context.getUser().platformId(),
                    context.getChatId(),
                    resumeAutonomy(cp),
                    cp.getAgentRole(),
                    context.getUser().channel().id(),
                    context.getUser().platformId(),
                    () -> agentLoop.run(
                            cp.getSessionId(),
                            cp.getAgentRole(),
                            goal,
                            cp.getSystemNote(),
                            reasoningEngine,
                            budget,
                            context));
        } catch (Exception e) {
            handleResumeFailure(cp.getSessionId(), context, e);
            throw new RuntimeException("Failed to resume session", e);
        }
    }

    /** Autonomy for a resumed loop: the checkpoint's own level, SUPERVISED as safe fallback. */
    static AutonomyLevel resumeAutonomy(AgentCheckpoint cp) {
        return cp.getAutonomyLevel() != null ? cp.getAutonomyLevel() : AutonomyLevel.SUPERVISED;
    }

    private int calculateResumeBudget(AgentCheckpoint cp) {
        int original = cp.getMaxIterations() != null ? cp.getMaxIterations() : 5;
        int remaining = Math.max(0, original - cp.getIterationsUsed());
        return Math.max(3, Math.min(remaining, Math.max(5, original)));
    }

    private void handleResumeFailure(String sessionId, MessageContext context, Exception e) {
        log.errorf("Failed to resume session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
        meterRegistry.counter("amsha.agent.resume.failure").increment();
        if (context == null || context.getUser() == null) {
            log.warnf("handleResumeFailure: context is null for session %s - no channel notification sent", sessionId);
            return;
        }
        channelPluginRegistry
                .find(context.getUser().channel().id())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                        s -> s.sendRaw(context.getUser(), "❌ Resume failed: " + AgentErrorMessageUtil.safeMessage(e)),
                        () -> log.debugf(
                                "CheckpointResumeHandler: no sender for type %s",
                                context.getUser().channel().id()));
    }

    void dispatchResumeResult(
            MessageContext context,
            AgentResult result,
            ChannelType cType,
            ChannelUser user,
            String sessionId,
            String toolInfo) {
        resultDispatcher.dispatchResumeResult(context, result, cType, user, sessionId, toolInfo);
    }

    MessageContext prepareResumeMessageContext(
            String chatId, ChannelUser user, ChannelType cType, String resumeGoal, AgentCheckpoint checkpoint) {
        return resultDispatcher.prepareResumeMessageContext(chatId, user, cType, resumeGoal, checkpoint, objectMapper);
    }

    String buildToolInfoPart(AgentCheckpoint checkpoint) {
        return ResumeToolInfoFormatter.format(checkpoint, objectMapper);
    }

    boolean syncCronStatus(String sessionId) {
        return CronSessionStatusSync.syncRunning(sessionId, cronStore);
    }

    boolean syncCronTerminalStatus(String sessionId, AgentResult result) {
        return CronSessionStatusSync.syncTerminal(sessionId, result, cronStore, cronRunStore);
    }

    @ActivateRequestContext
    public AgentResult resumeAfterBatchDecisionSync(
            String sessionId, String agentRole, String userId, String chatId, String channelType) throws Exception {

        if (!agentStore.claimForResume(sessionId, agentRole)) {
            log.infof("BATCH-RESUME-SYNC-GUARD: session %s already resuming", sessionId);
            return null;
        }

        if (!CronSessionStatusSync.syncRunning(sessionId, cronStore)) {
            agentStore.releaseResumeClaim(sessionId, agentRole);
            return null;
        }

        try {
            AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
            if (checkpoint == null) {
                log.warnf(
                        "resumeAfterBatchDecisionSync: no checkpoint found for session %s role %s",
                        sessionId, agentRole);
                return null;
            }

            String resumeGoal = strategyDispatcher.resolveResumeGoal(sessionId, agentRole);
            if (resumeGoal == null || resumeGoal.isBlank()) {
                log.warnf(
                        "resumeAfterBatchDecisionSync: no resume goal found for session %s role %s",
                        sessionId, agentRole);
                return null;
            }

            ChannelType cType = ChannelType.fromString(channelType).orElse(ChannelType.CLI);
            ChannelUser user = new ChannelUser(userId, chatId, "Operator", cType);
            MessageContext context = resultDispatcher.prepareResumeMessageContext(
                    chatId, user, cType, resumeGoal, checkpoint, objectMapper);

            int budget = calculateResumeBudget(checkpoint);

            log.infof("Synchronously resuming session=%s budget=%d", sessionId, budget);

            // §F9 fix: same rule as runResumeLoop — resume at the checkpoint's own autonomy.
            AgentResult result = WorkspaceContext.run(
                    sessionId,
                    userId,
                    chatId,
                    resumeAutonomy(checkpoint),
                    agentRole,
                    cType.id(),
                    userId,
                    () -> agentLoop.run(
                            sessionId,
                            agentRole,
                            resumeGoal,
                            checkpoint.getSystemNote(),
                            reasoningEngine,
                            budget,
                            context));

            var handoff = new DelegatedCompletionHandoff(agentStore, objectMapper).capture(sessionId, result);
            if (handoff.isPresent() && handoff.get().ready()) {
                DelegatedCompletionHandoff.ParentResume p = handoff.get();
                log.infof("[DELEGATION-SYNC] Resuming parent %s after child %s completed", p.sessionId(), sessionId);
                releaseResumeClaimSafely(sessionId, agentRole);
                return resumeAfterBatchDecisionSync(
                        p.sessionId(),
                        p.agentRole(),
                        p.userId(),
                        p.chatId(),
                        p.channelType() != null ? p.channelType() : channelType);
            }
            return result;

        } finally {
            releaseResumeClaimSafely(sessionId, agentRole);
        }
    }

    public String resolveResumeGoal(String sessionId, String agentRole) {
        return strategyDispatcher.resolveResumeGoal(sessionId, agentRole);
    }

    public AgentResult handle(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            MessageContext messageContext,
            AgentStateMachineRunner stateMachineRunner) {
        if (messageContext == null || !messageContext.isResumeSignal()) return null;

        var checkpoint =
                agentStore.load(context.sessionId(), context.agentRole()).orElse(null);
        if (checkpoint == null) return null;

        if (checkpoint.getStatus() != CheckpointStatus.AWAITING_APPROVAL
                && checkpoint.getStatus() != CheckpointStatus.RESUMING) {
            log.warnf(
                    "[AgentLoop] Attempted to resume session %s but status is %s.",
                    context.sessionId(), checkpoint.getStatus());
            return null;
        }

        return strategyDispatcher.handle(context, history, messageContext, stateMachineRunner, checkpoint);
    }
}
