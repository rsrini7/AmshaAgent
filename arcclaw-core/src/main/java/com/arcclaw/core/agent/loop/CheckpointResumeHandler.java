package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCronRecord;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.AgentGuards;
import com.arcclaw.core.util.AgentResultFormatter;
import com.arcclaw.core.util.CronScheduleUtil;
import com.arcclaw.persistence.api.CronStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

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

    @Inject
    public CheckpointResumeHandler(StatefulAgentStore agentStore,
                                   ToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   AgentLoop agentLoop,
                                   ReasoningEngine reasoningEngine,
                                   ChannelPluginRegistry channelPluginRegistry,
                                   CliResultBridge cliResultBridge,
                                   CronStorePort cronStore) {
        this.agentStore = agentStore;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.channelPluginRegistry = channelPluginRegistry;
        this.cliResultBridge = cliResultBridge;
        this.cronStore = cronStore;
        this.objectMapper = objectMapper;
        this.strategyDispatcher = new ResumeStrategyDispatcher(agentStore, toolRegistry, objectMapper, reasoningEngine);
    }

    @Inject
    ManagedExecutor executor;

    @ActivateRequestContext
    public void resumeAfterBatchDecision(
            String sessionId,
            String agentRole,
            String userId,
            String chatId,
            String channelType) {

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
                        log.warnf("resumeAfterBatchDecision: no resume goal found for session %s role %s", sessionId, agentRole);
                        return;
                    }
         
                    ChannelType cType = ChannelType.fromString(channelType).orElse(ChannelType.WEBSOCKET);
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

    private MessageContext prepareResumeMessageContext(String chatId, ChannelUser user, ChannelType cType, String goal) {
        ChannelMessage msg = switch (cType.id()) {
            case "discord"   -> new ChannelMessage.DiscordMessage(user, goal, chatId, chatId, Instant.now());
            case "rest_api"  -> new ChannelMessage.RestApiMessage(user, goal, chatId, Instant.now());
            case "cli"       -> new ChannelMessage.CliMessage(user, goal, chatId, Instant.now());
            case "websocket" -> new ChannelMessage.WebSocketMessage(user, goal, chatId, Instant.now());
            default          -> new ChannelMessage.TelegramMessage(user, goal, chatId, Instant.now());
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
                cp.getSessionId(), context.getUser().platformId(), context.getChatId(), cp.getAgentRole(),
                context.getUser().channel().id(), context.getUser().platformId(),
                () -> agentLoop.run(
                    cp.getSessionId(), cp.getAgentRole(), goal,
                    cp.getSystemNote(), reasoningEngine, budget, context));
        } catch (Exception e) {
            handleResumeFailure(cp.getSessionId(), context, e);
            throw new RuntimeException("Failed to resume session", e);
        }
    }

    private int calculateResumeBudget(AgentCheckpoint cp) {
        int maxResumeIterations = 5;
        int original = cp.getMaxIterations() != null ? cp.getMaxIterations() : maxResumeIterations;
        int used = cp.getIterationsUsed();
        int remaining = Math.max(0, original - used);
        return Math.max(3, Math.min(remaining, maxResumeIterations));
    }

    private void handleResumeFailure(String sessionId, MessageContext context, Exception e) {
        log.errorf("Failed to resume session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
        if (context == null || context.getUser() == null) {
            log.warnf("handleResumeFailure: context is null for session %s - no channel notification sent", sessionId);
            return;
        }
        channelPluginRegistry.find(context.getUser().channel().id())
            .map(ChannelPlugin::sender)
            .ifPresentOrElse(
                s -> s.sendRaw(context.getUser(), "❌ Resume failed: " + AgentErrorMessageUtil.safeMessage(e)),
                () -> log.debugf("CheckpointResumeHandler: no sender for type %s", context.getUser().channel().id())
            );
    }

    private void dispatchResumeResult(MessageContext context, AgentResult result, ChannelType cType, ChannelUser user, String sessionId, String toolInfo) {
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
        channelPluginRegistry.find(cType.id())
            .map(ChannelPlugin::sender)
            .ifPresentOrElse(
                s -> sendFormattedResult(s, user, result, finalOutput, sessionId, cType),
                () -> fallbackResultDispatch(sessionId, result, cType)
            );
    }

    private void sendFormattedResult(ChannelSender s, ChannelUser user, AgentResult result, String output, String sessionId, ChannelType cType) {
        if (result instanceof AgentResult.AwaitingApproval aa && aa.toolName().startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
            s.send(user, List.of(new ReplyNode.Text(output), new ReplyNode.ApprovalPrompt(aa.toolName(), aa.paramsJson(), sessionId)));
        } else if (ChannelType.WEBSOCKET.id().equalsIgnoreCase(cType.id())) {
            s.sendBySession(sessionId, output);
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
        if (checkpoint == null) return null;
        String toolName = checkpoint.getPendingToolName();
        if (toolName == null || toolName.isBlank()) return null;

        StringBuilder sb = new StringBuilder();

        if (checkpoint.isParallelBatch()) {
            String[] tools = toolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length()).split(",");
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
                    Map<String, Object> params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
                    if (!params.isEmpty()) {
                        sb.append("\n");
                        for (Map.Entry<String, Object> e : params.entrySet()) {
                            String val = e.getValue() instanceof String s ? s : objectMapper.writeValueAsString(e.getValue());
                            if (val.length() > 200) val = val.substring(0, 200) + "…";
                            sb.append("  ").append(e.getKey()).append(": ").append(val).append("\n");
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
                else cronStore.markFired(id, Instant.now(), CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.StructuredFinished _ -> {
                if (cron.isRunOnce()) cronStore.markCompleted(id, Instant.now());
                else cronStore.markFired(id, Instant.now(), CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
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
            String sessionId,
            String agentRole,
            String userId,
            String chatId,
            String channelType) throws Exception {

        if (!agentStore.claimForResume(sessionId, agentRole)) {
            log.infof("BATCH-RESUME-SYNC-GUARD: session %s already resuming", sessionId);
            return null;
        }

        syncCronStatus(sessionId);

        try {
            AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
            if (checkpoint == null) {
                log.warnf("resumeAfterBatchDecisionSync: no checkpoint found for session %s role %s", sessionId, agentRole);
                return null;
            }

            String resumeGoal = strategyDispatcher.resolveResumeGoal(sessionId, agentRole);
            if (resumeGoal == null || resumeGoal.isBlank()) {
                log.warnf("resumeAfterBatchDecisionSync: no resume goal found for session %s role %s",
                    sessionId, agentRole);
                return null;
            }

            ChannelType cType = ChannelType.fromString(channelType).orElse(ChannelType.CLI);
            ChannelUser user = new ChannelUser(userId, chatId, "Operator", cType);

            MessageContext context = new MessageContext(chatId, user, 
                new ChannelMessage.CliMessage(user, resumeGoal, chatId, Instant.now()));
            context.setResumeSignal(true);
            context.setGoal(resumeGoal);

            int budget = calculateResumeBudget(checkpoint);

            log.infof("Synchronously resuming session=%s budget=%d", sessionId, budget);

            return WorkspaceContext.runWithFullAutonomy(
                sessionId, userId, chatId, agentRole, cType.id(), userId,
                () -> agentLoop.run(
                    sessionId, agentRole, resumeGoal,
                    checkpoint.getSystemNote(),
                    reasoningEngine, budget, context));

        } finally {
            agentStore.clearBatchResuming(sessionId);
        }
    }

    public String resolveResumeGoal(String sessionId, String agentRole) {
        return strategyDispatcher.resolveResumeGoal(sessionId, agentRole);
    }

    public AgentResult handle(AgentExecutionContext context,
                              List<ReasoningStep> history,
                              MessageContext messageContext,
                              AgentStateMachineRunner stateMachineRunner) {
        if (messageContext == null || !messageContext.isResumeSignal()) return null;

        var checkpoint = agentStore.load(context.sessionId(), context.agentRole()).orElse(null);
        if (checkpoint == null) return null;

        if (checkpoint.getStatus() != CheckpointStatus.AWAITING_APPROVAL
                && checkpoint.getStatus() != CheckpointStatus.RESUMING) {
            log.warnf("[AgentLoop] Attempted to resume session %s but status is %s.",
                    context.sessionId(), checkpoint.getStatus());
            return null;
        }

        return strategyDispatcher.handle(context, history, messageContext, stateMachineRunner, checkpoint);
    }
}
