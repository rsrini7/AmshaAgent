package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
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
import com.amsha.core.speech.SpeechDeliveryService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.AgentGuards;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;

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
    private final CliResultBridge cliResultBridge;
    private final CronStorePort cronStore;
    private final ObjectMapper objectMapper;
    private final ResumeStrategyDispatcher strategyDispatcher;

    protected CheckpointResumeHandler() {
        this.agentStore = null;
        this.agentLoop = null;
        this.reasoningEngine = null;
        this.channelPluginRegistry = null;
        this.cliResultBridge = null;
        this.cronStore = null;
        this.objectMapper = null;
        this.strategyDispatcher = null;
    }

    public CheckpointResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            AgentLoop agentLoop,
            ReasoningEngine reasoningEngine,
            ChannelPluginRegistry channelPluginRegistry,
            CronStorePort cronStore,
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
                cliResultBridge,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
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
            CliResultBridge cliResultBridge,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard) {
        this.agentStore = agentStore;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.channelPluginRegistry = channelPluginRegistry;
        this.cliResultBridge = cliResultBridge;
        this.cronStore = cronStore;
        this.objectMapper = objectMapper;
        this.strategyDispatcher = new ResumeStrategyDispatcher(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                delegationResolver,
                guard);
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
            agentStore.releaseResumeClaim(sessionId, agentRole);
            agentStore.clearBatchResuming(sessionId);
            return false;
        }

        try {
            if (!syncCronStatus(sessionId)) {
                agentStore.releaseResumeClaim(sessionId, agentRole);
                agentStore.clearBatchResuming(sessionId);
                return false;
            }
            executor.execute(() -> {
                try {
                    Arc.container().requestContext().activate();
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
                            context = prepareResumeMessageContext(chatId, user, cType, resumeGoal, checkpoint);

                            String toolInfo = buildToolInfoPart(checkpoint);
                            AgentResult result = runResumeLoop(context, checkpoint, resumeGoal);
                            if (result != null) {
                                dispatchResumeResult(context, result, cType, user, sessionId, toolInfo);
                            }
                        }
                    } catch (Exception e) {
                        handleResumeFailure(sessionId, context, e);
                    } finally {
                        try {
                            Arc.container().requestContext().deactivate();
                        } catch (Exception e) {
                            log.debugf("Request context deactivate (non-fatal): %s", LogUtil.rootMessage(e));
                        }
                    }
                } finally {
                    releaseResumeClaimSafely(sessionId, agentRole);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            CronSessionStatusSync.syncTerminal(
                    sessionId,
                    AgentResult.failed(
                            "Resume executor rejected the session",
                            AgentResult.FailureCategory.SYSTEM_ERROR,
                            List.of()),
                    cronStore);
            releaseResumeClaimSafely(sessionId, agentRole);
            log.warnf("resumeAfterBatchDecision: executor rejected session %s: %s", sessionId, LogUtil.rootMessage(e));
            return false;
        } catch (RuntimeException e) {
            releaseResumeClaimSafely(sessionId, agentRole);
            log.warnf("resumeAfterBatchDecision: failed to dispatch session %s: %s", sessionId, LogUtil.rootMessage(e));
            return false;
        }
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

    private MessageContext prepareResumeMessageContext(
            String chatId, ChannelUser user, ChannelType cType, String goal, AgentCheckpoint checkpoint) {
        ChannelMessage msg =
                switch (cType.id()) {
                    case "telegram" -> new ChannelMessage.TelegramMessage(user, goal, chatId, Instant.now());
                    case "discord" -> new ChannelMessage.DiscordMessage(user, goal, chatId, chatId, Instant.now());
                    case "rest_api" -> new ChannelMessage.RestApiMessage(user, goal, chatId, Instant.now());
                    case "cli" -> new ChannelMessage.CliMessage(user, goal, chatId, Instant.now());
                    case "websocket" -> new ChannelMessage.WebSocketMessage(user, goal, chatId, Instant.now());
                    default -> new ChannelMessage.GenericChannelMessage(user, goal, chatId, Instant.now(), cType);
                };
        MessageContext context = new MessageContext(chatId, user, msg);
        context.setResumeSignal(true);
        context.setGoal(goal);
        if (checkpoint != null && checkpoint.getContextDataJson() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(checkpoint.getContextDataJson(), Map.class);
                if (data != null) data.forEach(context::setData);
            } catch (Exception e) {
                log.debugf("Restore context data (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }
        return context;
    }

    private AgentResult runResumeLoop(MessageContext context, AgentCheckpoint cp, String goal) {
        try {
            int budget = calculateResumeBudget(cp);
            log.infof("Resuming session=%s budget=%d", cp.getSessionId(), budget);

            // §F9 fix: resume with the checkpoint's OWN autonomy level — NOT FULL.
            // Wrapping the whole resumed loop in runWithFullAutonomy let a single approval
            // elevate every subsequent tool call in the session (HITL silently disabled).
            // The one approved tool still gets its elevation via the tightly-scoped
            // runWithFullAutonomy inside PendingToolResumeExecutor/ParallelBatchResumeHandler.
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
        int defaultResumeIterations = 5;
        int original = cp.getMaxIterations() != null ? cp.getMaxIterations() : defaultResumeIterations;
        int used = cp.getIterationsUsed();
        int remaining = Math.max(0, original - used);
        // Use the original max as the per-resume cap — not a hardcoded 5.
        // Skills with higher maxIterations (e.g. 15) need bigger resume budgets.
        int perResumeCap = Math.max(defaultResumeIterations, original);
        return Math.max(3, Math.min(remaining, perResumeCap));
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
        if (!syncCronTerminalStatus(sessionId, result)) {
            log.warnf("Skipping resume notification for session %s because cron ownership was lost", sessionId);
            return;
        }

        if (ChannelType.CLI.id().equalsIgnoreCase(cType.id())) {
            log.debugf("CheckpointResumeHandler: skipping async CLI notification for session %s", sessionId);
            return;
        }

        String baseOutput = AgentResultFormatter.format(result);
        String finalOutput;
        if (toolInfo != null && !toolInfo.isBlank()) {
            boolean wasDenied = baseOutput.contains("denied") || baseOutput.contains("cancelled");
            String header = "---\n" + (wasDenied ? "❌ Denied" : "✅ Approved") + " — " + toolInfo + "\n---";
            finalOutput = header + "\n\n" + baseOutput;
        } else {
            finalOutput = baseOutput;
        }
        channelPluginRegistry
                .find(cType.id())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                        s -> sendFormattedResult(s, user, result, finalOutput, sessionId, context),
                        () -> fallbackResultDispatch(sessionId, result, cType));
    }

    private void sendFormattedResult(
            ChannelSender s,
            ChannelUser user,
            AgentResult result,
            String output,
            String sessionId,
            MessageContext context) {
        if (result instanceof AgentResult.AwaitingApproval aa) {
            s.send(user, List.of(new ApprovalPrompt(aa.toolName(), aa.displayParamsJson(), sessionId)));
            return;
        }
        if (speechDeliveryService != null) {
            speechDeliveryService.deliver(context, s, user, result, output);
        } else {
            s.sendRaw(user, output);
        }
    }

    private void fallbackResultDispatch(String sessionId, AgentResult result, ChannelType cType) {
        log.debugf("CheckpointResumeHandler: no sender for type %s", cType.id());
        if (ChannelType.CLI.id().equalsIgnoreCase(cType.id())) {
            cliResultBridge.complete(sessionId, result);
        }
    }

    private String buildToolInfoPart(AgentCheckpoint checkpoint) {
        return ResumeToolInfoFormatter.format(checkpoint, objectMapper);
    }

    private boolean syncCronStatus(String sessionId) {
        return CronSessionStatusSync.syncRunning(sessionId, cronStore);
    }

    private boolean syncCronTerminalStatus(String sessionId, AgentResult result) {
        return CronSessionStatusSync.syncTerminal(sessionId, result, cronStore);
    }

    @ActivateRequestContext
    public AgentResult resumeAfterBatchDecisionSync(
            String sessionId, String agentRole, String userId, String chatId, String channelType) throws Exception {

        if (!agentStore.claimForResume(sessionId, agentRole)) {
            log.infof("BATCH-RESUME-SYNC-GUARD: session %s already resuming", sessionId);
            return null;
        }

        if (!syncCronStatus(sessionId)) {
            agentStore.releaseResumeClaim(sessionId, agentRole);
            return null;
        }

        try {
            AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
            if (checkpoint == null) {
                log.warnf(
                        "resumeAfterBatchDecisionSync: no checkpoint found for session %s role %s",
                        sessionId, agentRole);
                agentStore.releaseResumeClaim(sessionId, agentRole);
                return null;
            }

            String resumeGoal = strategyDispatcher.resolveResumeGoal(sessionId, agentRole);
            if (resumeGoal == null || resumeGoal.isBlank()) {
                log.warnf(
                        "resumeAfterBatchDecisionSync: no resume goal found for session %s role %s",
                        sessionId, agentRole);
                agentStore.releaseResumeClaim(sessionId, agentRole);
                return null;
            }

            ChannelType cType = ChannelType.fromString(channelType).orElse(ChannelType.CLI);
            ChannelUser user = new ChannelUser(userId, chatId, "Operator", cType);

            MessageContext context = new MessageContext(
                    chatId, user, new ChannelMessage.CliMessage(user, resumeGoal, chatId, Instant.now()));
            context.setResumeSignal(true);
            context.setGoal(resumeGoal);

            int budget = calculateResumeBudget(checkpoint);

            log.infof("Synchronously resuming session=%s budget=%d", sessionId, budget);

            // §F9 fix: same rule as runResumeLoop — resume at the checkpoint's own autonomy.
            return WorkspaceContext.run(
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

        } finally {
            agentStore.clearBatchResuming(sessionId);
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
