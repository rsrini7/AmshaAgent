package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ContentNode;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.AgentGuards;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.core.util.CronScheduleUtil;
import com.amsha.persistence.api.CronStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
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

    @ActivateRequestContext
    public void resumeAfterBatchDecision(
            String sessionId, String agentRole, String userId, String chatId, String channelType) {

        if (AgentGuards.isShuttingDown()) {
            log.warnf("Container shutting down - cannot resume session %s", sessionId);
            return;
        }

        if (!agentStore.claimForResume(sessionId, agentRole)) {
            log.infof("[BATCH-RESUME-GUARD] session=%s already resuming - skipping duplicate.", sessionId);
            return;
        }

        AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
        if (checkpoint == null) {
            log.warnf("[RESUME-GUARD] checkpoint null after claim for session=%s - releasing DB state.", sessionId);
            agentStore.releaseResumeClaim(sessionId, agentRole);
            agentStore.clearBatchResuming(sessionId);
            return;
        }

        syncCronStatus(sessionId);

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
                        return;
                    }

                    ChannelType cType = (channelType == null || channelType.isBlank())
                            ? ChannelType.WEBSOCKET
                            : ChannelType.of(channelType);
                    ChannelUser user = new ChannelUser(userId, chatId, "Operator", cType);
                    context = prepareResumeMessageContext(chatId, user, cType, resumeGoal);

                    String toolInfo = buildToolInfoPart(checkpoint);
                    AgentResult result = runResumeLoop(context, checkpoint, resumeGoal);
                    if (result != null) {
                        dispatchResumeResult(context, result, cType, user, sessionId, toolInfo);
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
                agentStore.clearBatchResuming(sessionId);
            }
        });
    }

    private MessageContext prepareResumeMessageContext(
            String chatId, ChannelUser user, ChannelType cType, String goal) {
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
        return context;
    }

    private AgentResult runResumeLoop(MessageContext context, AgentCheckpoint cp, String goal) {
        try {
            int budget = calculateResumeBudget(cp);
            log.infof("Resuming session=%s budget=%d", cp.getSessionId(), budget);

            return WorkspaceContext.runWithFullAutonomy(
                    cp.getSessionId(),
                    context.getUser().platformId(),
                    context.getChatId(),
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

    private void dispatchResumeResult(
            MessageContext _context,
            AgentResult result,
            ChannelType cType,
            ChannelUser user,
            String sessionId,
            String toolInfo) {
        syncCronTerminalStatus(sessionId, result);

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
                        s -> sendFormattedResult(s, user, result, finalOutput, sessionId, cType),
                        () -> fallbackResultDispatch(sessionId, result, cType));
    }

    private void sendFormattedResult(
            ChannelSender s,
            ChannelUser user,
            AgentResult result,
            String output,
            String sessionId,
            ChannelType _cType) {
        if (result instanceof AgentResult.AwaitingApproval aa
                && aa.toolName().startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
            s.send(
                    user,
                    List.of(
                            new ContentNode.Text(output),
                            new ApprovalPrompt(aa.toolName(), aa.paramsJson(), sessionId)));
        } else {
            // CLI, WebSocket, and unknown channel types all use raw text delivery
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
        if (checkpoint == null) return null;
        String toolName = checkpoint.getPendingToolName();
        if (toolName == null || toolName.isBlank()) return null;

        StringBuilder sb = new StringBuilder();

        if (checkpoint.isParallelBatch()) {
            String[] tools = toolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length())
                    .split(",", -1);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String t : tools) {
                counts.put(t, counts.getOrDefault(t, 0) + 1);
            }
            boolean first = true;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey());
                if (e.getValue() > 1) sb.append(" (").append(e.getValue()).append("x)");
            }
        } else {
            sb.append(toolName);
            String paramsJson = checkpoint.getPendingToolParametersJson();
            if (paramsJson != null && !paramsJson.isBlank()) {
                try {
                    Map<String, Object> params =
                            objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
                    if (!params.isEmpty()) {
                        sb.append("\n");
                        for (Map.Entry<String, Object> e : params.entrySet()) {
                            String val = e.getValue() instanceof String s
                                    ? s
                                    : objectMapper.writeValueAsString(e.getValue());
                            if (val.length() > 200) val = val.substring(0, 200) + "…";
                            sb.append("  ")
                                    .append(e.getKey())
                                    .append(": ")
                                    .append(val)
                                    .append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.debugf("Failed to format params JSON, using raw: %s", LogUtil.rootMessage(e));
                    String display = paramsJson.length() > 500 ? paramsJson.substring(0, 500) + "…" : paramsJson;
                    sb.append("\n  ").append(display);
                }
            }
        }

        return sb.toString().stripTrailing();
    }

    private void syncCronStatus(String sessionId) {
        if (sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            String sub = sessionId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
            if (sub.length() >= 36) {
                String jobId = sub.substring(0, 36);
                try {
                    cronStore.markRunning(jobId);
                } catch (Exception e) {
                    log.warnf("Failed to sync cron status for job %s: %s", jobId, LogUtil.rootMessage(e));
                }
            }
        }
    }

    private void syncCronTerminalStatus(String sessionId, AgentResult result) {
        if (sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX)) {
            String sub = sessionId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
            if (sub.length() >= 36) {
                String jobId = sub.substring(0, 36);
                try {
                    markCronTerminal(jobId, result);
                } catch (Exception e) {
                    log.warnf("Failed to sync terminal cron status for job %s: %s", jobId, LogUtil.rootMessage(e));
                }
            }
        }
    }

    private void markCronTerminal(String id, AgentResult result) {
        var dto = cronStore.findById(id).orElse(null);
        if (dto == null || result == null) return;
        var cron = AgentCronRecord.fromDto(dto);

        switch (result) {
            case AgentResult.Finished _ -> {
                if (cron.isRunOnce()) cronStore.markCompleted(id, Instant.now());
                else
                    cronStore.markFired(
                            id,
                            Instant.now(),
                            CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.StructuredFinished _ -> {
                if (cron.isRunOnce()) cronStore.markCompleted(id, Instant.now());
                else
                    cronStore.markFired(
                            id,
                            Instant.now(),
                            CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.Failed f -> cronStore.markFailed(id, f.reason());
            case AgentResult.AwaitingApproval _ -> cronStore.markAwaitingApproval(id);
            case AgentResult.AwaitingRetry _ -> {}
            case AgentResult.LoopExceeded _ -> cronStore.markFailed(id, "Loop exceeded");
            default -> {}
        }
    }

    @ActivateRequestContext
    public AgentResult resumeAfterBatchDecisionSync(
            String sessionId, String agentRole, String userId, String chatId, String channelType) throws Exception {

        if (!agentStore.claimForResume(sessionId, agentRole)) {
            log.infof("BATCH-RESUME-SYNC-GUARD: session %s already resuming", sessionId);
            return null;
        }

        syncCronStatus(sessionId);

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

            MessageContext context = new MessageContext(
                    chatId, user, new ChannelMessage.CliMessage(user, resumeGoal, chatId, Instant.now()));
            context.setResumeSignal(true);
            context.setGoal(resumeGoal);

            int budget = calculateResumeBudget(checkpoint);

            log.infof("Synchronously resuming session=%s budget=%d", sessionId, budget);

            return WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    userId,
                    chatId,
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
