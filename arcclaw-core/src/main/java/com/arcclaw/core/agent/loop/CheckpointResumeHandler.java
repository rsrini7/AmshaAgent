package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.AgentGuards;
import com.arcclaw.core.util.AgentResultFormatter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointResumeHandler {

    private static final Logger log = Logger.getLogger(CheckpointResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AgentLoop agentLoop;
    private final ReasoningEngine reasoningEngine;
    private final ChannelPluginRegistry channelPluginRegistry;
    private final CliResultBridge cliResultBridge;

    protected CheckpointResumeHandler() {
        this.agentStore = null;
        this.toolRegistry = null;
        this.objectMapper = null;
        this.agentLoop = null;
        this.reasoningEngine = null;
        this.channelPluginRegistry = null;
        this.cliResultBridge = null;
    }

    @Inject
    public CheckpointResumeHandler(StatefulAgentStore agentStore,
                                   ToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   AgentLoop agentLoop,
                                   ReasoningEngine reasoningEngine,
                                   ChannelPluginRegistry channelPluginRegistry,
                                   CliResultBridge cliResultBridge) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.channelPluginRegistry = channelPluginRegistry;
        this.cliResultBridge = cliResultBridge;
    }

    @Inject
    ManagedExecutor executor;

    public void resumeAfterBatchDecision(
            String sessionId,
            String agentRole,
            String userId,
            String chatId,
            String channelType) {

        if (AgentGuards.isShuttingDown()) {
            log.warnf("Container shutting down — cannot resume session %s", sessionId);
            return;
        }

        // 1. Load checkpoint synchronously to check if it's parallel
        AgentCheckpoint checkpoint = loadCheckpointForResume(sessionId, agentRole);
        if (checkpoint == null) {
            log.warnf("[RESUME-GUARD] checkpoint null after claim for session=%s — releasing DB state.", sessionId);
            agentStore.releaseResumeClaim(sessionId, agentRole); 
            return;
        }

        // §7.29: Atomic guard to prevent double-resumption for parallel batches.
        // We only use the lock for parallel batches (size > 1) to avoid race conditions.
        // Single-tool approvals (sequential) don't need the guard and can proceed directly.
        if (checkpoint.isParallelBatch()) {
            if (!agentStore.markBatchResuming(sessionId, agentRole, checkpoint.hasBrowserTool())) {
                log.infof("[BATCH-RESUME-GUARD] parallel batch for session=%s already resuming — skipping duplicate.", sessionId);
                return;
            }
        }

        executor.execute(() -> {
            MessageContext context = null;
            try {
                String resumeGoal = resolveResumeGoal(checkpoint);
                if (isGoalInvalid(resumeGoal, sessionId, agentRole)) return;
     
                ChannelType cType = ChannelType.fromString(channelType).orElse(ChannelType.WEBSOCKET);
                ChannelUser user = new ChannelUser(userId, chatId, "Operator", cType);
                context = prepareResumeMessageContext(chatId, user, cType, resumeGoal);
     
                AgentResult result = runResumeLoop(context, checkpoint, resumeGoal);
                if (result != null) {
                    dispatchResumeResult(context, result, cType, user, sessionId);
                }
            } catch (Exception e) {
                handleResumeFailure(sessionId, context, e);
            } finally {
                if (checkpoint.isParallelBatch()) {
                    agentStore.clearBatchResuming(sessionId);
                }
            }
        });
    }

    private AgentCheckpoint loadCheckpointForResume(String sessionId, String agentRole) {
        AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
        if (checkpoint == null) {
            log.warnf("resumeAfterBatchDecision: no checkpoint found for session %s role %s", sessionId, agentRole);
        }
        return checkpoint;
    }

    private boolean isGoalInvalid(String resumeGoal, String sessionId, String agentRole) {
        if (resumeGoal == null || resumeGoal.isBlank()) {
            log.warnf("resumeAfterBatchDecision: no resume goal found for session %s role %s", sessionId, agentRole);
            return true;
        }
        return false;
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
                context.getUser().channel().id(),
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
        log.errorf(e, "Failed to resume session %s", sessionId);
        if (context == null || context.getUser() == null) {
            log.warnf("handleResumeFailure: context is null for session %s — no channel notification sent", sessionId);
            return;
        }
        channelPluginRegistry.find(context.getUser().channel().id())
            .map(ChannelPlugin::sender)
            .ifPresentOrElse(
                s -> s.sendRaw(context.getUser(), "❌ Resume failed: " + AgentErrorMessageUtil.safeMessage(e)),
                () -> log.debugf("CheckpointResumeHandler: no sender for type %s", context.getUser().channel().id())
            );
    }

    private void dispatchResumeResult(MessageContext context, AgentResult result, ChannelType cType, ChannelUser user, String sessionId) {
        // Interactive /approve paths depend on the resume handler to deliver the final result.
        // CLI uses the synchronous resume path and must not receive an extra async notification.
        if (ChannelType.CLI.id().equalsIgnoreCase(cType.id())) {
            log.debugf("CheckpointResumeHandler: skipping async CLI notification for session %s", sessionId);
            return;
        }

        String output = AgentResultFormatter.format(result);
        channelPluginRegistry.find(cType.id())
            .map(ChannelPlugin::sender)
            .ifPresentOrElse(
                s -> sendFormattedResult(s, user, result, output, sessionId, cType),
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

    public AgentResult resumeAfterBatchDecisionSync(
            String sessionId,
            String agentRole,
            String userId,
            String chatId,
            String channelType) throws Exception {

        if (!agentStore.markBatchResuming(sessionId, agentRole)) {
            log.infof("BATCH-RESUME-SYNC-GUARD: session %s already resuming", sessionId);
            return null;
        }

        try {
            AgentCheckpoint checkpoint = agentStore.load(sessionId, agentRole).orElse(null);
            if (checkpoint == null) {
                log.warnf("resumeAfterBatchDecisionSync: no checkpoint found for session %s role %s", sessionId, agentRole);
                return null;
            }

            String resumeGoal = resolveResumeGoal(checkpoint);
            if (resumeGoal == null || resumeGoal.isBlank()) {
                log.warnf("resumeAfterBatchDecisionSync: no resume goal found for session %s role %s",
                    sessionId, agentRole);
                return null;
            }

            ChannelType cType = ChannelType.fromString(channelType)
                    .orElse(ChannelType.CLI);

            ChannelUser user = new ChannelUser(
                userId, chatId, "Operator", cType);

            MessageContext context = new MessageContext(chatId, user, 
                new ChannelMessage.CliMessage(user, resumeGoal, chatId, Instant.now()));
            context.setResumeSignal(true);
            context.setGoal(resumeGoal);

            // Fetch budget for the resume.
            int maxResumeIterations = 5;
            int original = checkpoint.getMaxIterations() != null ? checkpoint.getMaxIterations() : maxResumeIterations;
            int used = checkpoint.getIterationsUsed();
            int remaining = Math.max(0, original - used);
            int budget = Math.max(3, Math.min(remaining, maxResumeIterations));

            log.infof("Synchronously resuming session=%s budget=%d (original=%d, used=%d)",
                    sessionId, budget, original, used);

            return WorkspaceContext.runWithFullAutonomy(
                sessionId, userId, chatId, agentRole, cType.id(),
                () -> agentLoop.run(
                    sessionId, agentRole, resumeGoal,
                    checkpoint.getSystemNote(),
                    reasoningEngine, budget, context));

        } finally {
            agentStore.clearBatchResuming(sessionId);
        }
    }

    public String resolveResumeGoal(String sessionId, String agentRole) {
        return agentStore.load(sessionId, agentRole)
            .map(this::resolveResumeGoal)
            .orElse(AgentSentinels.GOAL_RESUME_APPROVED);
    }

    public String resolveResumeGoal(AgentCheckpoint cp) {
        // If all decisions in the batch are DENIED, route to the denial handler
        if (cp.hasBatchToolDecisions()) {
            try {
                Map<String, ToolDecision> decisions = objectMapper.readValue(
                    cp.getBatchToolDecisions(),
                    new TypeReference<Map<String, ToolDecision>>() {}
                );
                boolean allDenied = !decisions.isEmpty() &&
                    decisions.values().stream()
                        .allMatch(ToolDecision.DENIED::equals);
                if (allDenied) return AgentSentinels.GOAL_DENY_APPROVED;
            } catch (Exception e) {
                log.warnf("resolveResumeGoal: failed to parse batch decisions for session %s", cp.getSessionId());
            }
        }
        String og = cp.getOriginalGoal();
        return og != null && !og.isBlank() ? og : AgentSentinels.GOAL_RESUME_APPROVED;
    }

    public AgentResult handle(AgentExecutionContext context,
                              List<ReasoningStep> history,
                              MessageContext messageContext) {
        if (messageContext == null || !messageContext.isResumeSignal()) return null;

        var checkpoint = agentStore.load(context.sessionId(), context.agentRole()).orElse(null);
        if (checkpoint == null) return null;

        if (checkpoint.getStatus() != CheckpointStatus.AWAITING_APPROVAL
                && checkpoint.getStatus() != CheckpointStatus.RESUMING) {
            log.warnf("[AgentLoop] Attempted to resume session %s but status is %s.",
                    context.sessionId(), checkpoint.getStatus());
            return null;
        }

        if (AgentSentinels.GOAL_DENY_APPROVED.equals(context.goal())) {
            return performDenialCleanup(context, history, checkpoint);
        }

        String resolvedChannelType = resolveChannelType(context, messageContext);

        if (checkpoint.isParallelBatch()) {
            return resumeParallelBatch(context, history, checkpoint, resolvedChannelType);
        }

        return executePendingTool(context, history, checkpoint, resolvedChannelType);
    }

    private AgentResult performDenialCleanup(AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint) {
        log.infof("[AgentLoop] User denied tool execution for session %s", context.sessionId());
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setFailReason("User denied tool execution.");
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);

        return AgentResult.finished("❌ Tool execution was denied and the task has been cancelled.", List.copyOf(history));
    }

    private AgentResult executePendingTool(AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint, String channelType) {
        String toolName = checkpoint.getPendingToolName();
        String paramsJson = checkpoint.getPendingToolParametersJson();
        String sessionId = context.sessionId();
        
        // Ensure we use the role that originally called the tool §7.31
        String effectiveRole = checkpoint.getPendingToolRole() != null 
                ? checkpoint.getPendingToolRole() 
                : context.agentRole();

        log.infof("[AgentLoop] Resuming session %s executing pending tool %s as role %s", 
                sessionId, toolName, effectiveRole);

        try {
            ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                    sessionId, WorkspaceContext.requireUserId(), WorkspaceContext.requireChatId(), effectiveRole,
                    channelType,
                    () -> toolRegistry.invokeTool(toolName, paramsJson, sessionId));

            if (response.isInfraFailure()) return handleInfraFailure(history);

            recordReasoningStep(toolName, paramsJson, response.output(), history, sessionId);
            
            // §Issue 4: Force summarization turn for ingest_document
            if (ToolCapability.INGEST_DOCUMENT.toolName().equals(toolName)) {
                history.add(ReasoningStep.systemMessage(
                        "The document has been ingested successfully. " +
                        "Summarize what was stored for the user in a natural conversational tone."));
            }
            
            completeInProgressCheckpoint(checkpoint);

            // §7.32: Return null to allow the StateMachine to continue for one final synthesis turn.
            // Hardening in AgentStateMachineRunner ensures it respects context.startingIteration()
            // to avoid infinite loops.
            return null;
        } catch (Exception e) {
            return handleResumeExecutionError(checkpoint, toolName, sessionId, history, e);
        }
    }

    private AgentResult handleInfraFailure(List<ReasoningStep> history) {
        return AgentResult.failed("⚠️ The sandbox execution environment is currently offline.",
                AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
    }

    private void recordReasoningStep(String tool, String paramsJson, String output, List<ReasoningStep> history, String sessionId) {
        Map<String, Object> params = Map.of();
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warnf("Failed to parse params for session %s", sessionId);
        }
        history.add(ReasoningStep.pending("Executing " + tool + " after human approval.", tool, params, 0, 0)
                .withObservation(output));
    }

    private void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }

    private AgentResult handleResumeExecutionError(AgentCheckpoint cp, String tool, String sid, List<ReasoningStep> history, Exception e) {
        cp.setStatus(CheckpointStatus.FAILED);
        cp.setPendingToolName(null);
        cp.setPendingToolParametersJson(null);
        agentStore.save(cp);
        String msg = AgentErrorMessageUtil.safeMessage(e);
        log.errorf("Resume tool execution failed session=%s tool=%s: %s", sid, tool, msg);
        return AgentResult.failed("TOOL_EXECUTION_FAILED: " + msg, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
    }

    private AgentResult resumeParallelBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType) {

        String sessionId = context.sessionId();
        log.infof("[PARALLEL-HITL-RESUME] session=%s resuming batch", sessionId);

        List<ParallelToolCall> calls = parseParallelCalls(checkpoint);
        if (calls == null) return handleCorruptBatch(checkpoint, history);

        Map<String, ToolDecision> decisions = loadDecisions(checkpoint);
        Map<String, String> preExecuted = loadPreExecutedResults(checkpoint);

        List<ParallelToolCall> completed = new ArrayList<>();
        for (ParallelToolCall call : calls) {
            if (tryRestorePreExecuted(call, preExecuted, completed, sessionId)) continue;

            if (decisions.getOrDefault(call.toolName(), ToolDecision.PENDING) == ToolDecision.DENIED) {
                completed.add(call.withResult("User denied this tool execution.", false));
                continue;
            }

            executeBatchCall(call, context, completed, checkpoint, channelType);
        }

        return finalizeParallelBatch(checkpoint, history, completed);
    }

    private List<ParallelToolCall> parseParallelCalls(AgentCheckpoint cp) {
        try {
            return objectMapper.readValue(cp.getPendingToolParametersJson(), new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL-RESUME] session=%s: failed to parse batch", cp.getSessionId());
            return null;
        }
    }

    private Map<String, ToolDecision> loadDecisions(AgentCheckpoint cp) {
        if (!cp.hasBatchToolDecisions()) return Map.of();
        try {
            return objectMapper.readValue(cp.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
        } catch (Exception e) {
            log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load decisions", cp.getSessionId());
            return Map.of();
        }
    }

    private Map<String, String> loadPreExecutedResults(AgentCheckpoint cp) {
        if (!cp.hasPreExecutedResults()) return Map.of();
        try {
            return objectMapper.readValue(cp.getPreExecutedResults(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load results", cp.getSessionId());
            return Map.of();
        }
    }

    private boolean tryRestorePreExecuted(ParallelToolCall call, Map<String, String> preExecuted, List<ParallelToolCall> completed, String sessionId) {
        if (preExecuted.containsKey(call.toolName())) {
            try {
                ToolResponse stored = objectMapper.readValue(preExecuted.get(call.toolName()), ToolResponse.class);
                completed.add(call.withResult(stored.output(), stored.success()));
                return true;
            } catch (Exception e) {
                log.warnf("[PRE-EXEC-RESTORE] session=%s could not restore %s", sessionId, call.toolName());
            }
        }
        return false;
    }

    private void executeBatchCall(ParallelToolCall call, AgentExecutionContext ctx, List<ParallelToolCall> completed, AgentCheckpoint cp, String channelType) {
        try {
            String effectiveRole = cp.getPendingToolRole() != null ? cp.getPendingToolRole() : ctx.agentRole();
            ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                ctx.sessionId(), WorkspaceContext.requireUserId(), WorkspaceContext.requireChatId(), effectiveRole,
                channelType,
                () -> toolRegistry.invokeTool(call.toolName(), call.paramsJson(), ctx.sessionId()));
            completed.add(call.withResult(response.output(), response.success()));
        } catch (Exception e) {
            completed.add(call.withResult("Tool execution failed on resume: " + AgentErrorMessageUtil.safeMessage(e), false));
        }
    }

    private String resolveChannelType(AgentExecutionContext context, MessageContext messageContext) {
        if (messageContext != null
                && messageContext.getUser() != null
                && messageContext.getUser().channel() != null
                && messageContext.getUser().channel().id() != null
                && !messageContext.getUser().channel().id().isBlank()) {
            return messageContext.getUser().channel().id();
        }
        if (context != null && context.channelType() != null && !context.channelType().isBlank()) {
            return context.channelType();
        }
        return WorkspaceContext.channelType().orElseGet(() -> {
            log.warn("CheckpointResumeHandler: channelType missing during resume; using 'unknown' fallback");
            return "unknown";
        });
    }

    private AgentResult finalizeParallelBatch(AgentCheckpoint checkpoint, List<ReasoningStep> history, List<ParallelToolCall> completed) {
        try {
            String completedJson = objectMapper.writeValueAsString(completed);
            String summary = completed.stream().map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL")).collect(Collectors.joining(", "));
            history.add(ReasoningStep.parallelBatch("Human Approved (batch)", Map.of("batch", completedJson), 0, 0).withObservation(summary));
            completeInProgressCheckpoint(checkpoint);

            // §7.32: Return null to allow the StateMachine to continue for one final synthesis turn.
            return null;
        } catch (Exception e) {
            return handleCorruptBatch(checkpoint, history);
        }
    }

    private AgentResult handleCorruptBatch(AgentCheckpoint cp, List<ReasoningStep> history) {
        cp.setStatus(CheckpointStatus.FAILED);
        agentStore.save(cp);
        return AgentResult.failed("Failed to finalize parallel tool batch.", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
    }
}
