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
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentResultFormatter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    protected CheckpointResumeHandler() {
        this.agentStore = null;
        this.toolRegistry = null;
        this.objectMapper = null;
        this.agentLoop = null;
        this.reasoningEngine = null;
        this.channelPluginRegistry = null;
    }

    @Inject
    public CheckpointResumeHandler(StatefulAgentStore agentStore,
                                   ToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   AgentLoop agentLoop,
                                   ReasoningEngine reasoningEngine,
                                   ChannelPluginRegistry channelPluginRegistry) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.channelPluginRegistry = channelPluginRegistry;
    }

    public void resumeAfterBatchDecision(
            String sessionId,
            String agentRole,
            String userId,
            String chatId,
            String channelType) {

        // §7.29 double-resume guard
        if (!agentStore.markBatchResuming(sessionId)) {
            log.infof("BATCH-RESUME-GUARD: session %s already resuming", sessionId);
            return;
        }

        try {
            String resumeGoal = resolveResumeGoal(sessionId, agentRole);
            if (resumeGoal == null || resumeGoal.isBlank()) {
                log.warnf("resumeAfterBatchDecision: no resume goal " +
                    "found for session %s role %s — cannot resume",
                    sessionId, agentRole);
                return;
            }

            ChannelType cType = ChannelType.fromString(channelType)
                    .orElse(ChannelType.WEBSOCKET);

            ChannelUser user = new ChannelUser(
                userId, chatId, "Operator", cType);

            ChannelMessage msg = switch (cType.id()) {
                case "discord"   -> new ChannelMessage.DiscordMessage(user, resumeGoal, chatId, chatId, Instant.now());
                case "rest_api"  -> new ChannelMessage.RestApiMessage(user, resumeGoal, chatId, Instant.now());
                case "cli"       -> new ChannelMessage.CliMessage(user, resumeGoal, chatId, Instant.now());
                case "websocket" -> new ChannelMessage.WebSocketMessage(user, resumeGoal, chatId, Instant.now());
                default          -> new ChannelMessage.TelegramMessage(user, resumeGoal, chatId, Instant.now());
            };

            MessageContext context = new MessageContext(chatId, user, msg);
            context.setResumeSignal(true);
            context.setGoal(resumeGoal);

            AgentResult result;
            try {
                result = WorkspaceContext.runWithFullAutonomy(
                    sessionId, userId, chatId, agentRole,
                    () -> agentLoop.run(
                        sessionId, agentRole, resumeGoal,
                        reasoningEngine, 5, context));
            } catch (Exception e) {
                log.errorf(e, "Failed to resume session %s", sessionId);
                channelPluginRegistry.find(cType.id())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                        s -> s.sendRaw(user, "❌ Resume failed: " + e.getMessage()),
                        () -> log.warnf("CheckpointResumeHandler: no sender for type %s — error reply dropped", cType.id())
                    );
                throw new RuntimeException("Failed to resume session", e);
            }

            // Send the actual agent result back to the channel
            if (result != null) {
                String output = AgentResultFormatter.format(result);
                channelPluginRegistry.find(cType.id())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                        s -> {
                            if (result instanceof AgentResult.AwaitingApproval aa
                                    && aa.toolName().startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
                                s.send(user, List.of(
                                    new ReplyNode.Text(output),
                                    new ReplyNode.ApprovalPrompt(aa.toolName(), aa.paramsJson(), sessionId)
                                ));
                            } else {
                                s.sendRaw(user, output);
                            }
                        },
                        () -> log.warnf("CheckpointResumeHandler: no sender for type %s — result reply dropped", cType.id())
                    );
            } else {
                log.warnf("resumeAfterBatchDecision: agentLoop.run() returned null for session %s", sessionId);
            }

        } finally {
            agentStore.clearBatchResuming(sessionId);
        }
    }

    public String resolveResumeGoal(String sessionId, String agentRole) {
        return agentStore.load(sessionId, agentRole)
            .map(cp -> {
                // If all decisions in the batch are DENIED, route to the denial handler
                if (cp.hasBatchToolDecisions()) {
                    try {
                        Map<String, String> decisions = objectMapper.readValue(
                            cp.getBatchToolDecisions(),
                            new TypeReference<Map<String, String>>() {}
                        );
                        boolean allDenied = !decisions.isEmpty() &&
                            decisions.values().stream()
                                .allMatch(AgentSentinels.TOOL_DECISION_DENIED::equals);
                        if (allDenied) return AgentSentinels.GOAL_DENY_APPROVED;
                    } catch (Exception e) {
                        log.warnf("resolveResumeGoal: failed to parse batch decisions for session %s", sessionId);
                    }
                }
                String og = cp.getOriginalGoal();
                return og != null && !og.isBlank() ? og : AgentSentinels.GOAL_RESUME_APPROVED;
            })
            .orElse(AgentSentinels.GOAL_RESUME_APPROVED);
    }

    public AgentResult handle(AgentExecutionContext context,
                              List<ReasoningStep> history,
                              MessageContext messageContext) {
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        String goal = context.goal();

        if (messageContext == null || !messageContext.isResumeSignal()) {
            return null;
        }

        var resumeOpt = agentStore.load(sessionId, agentRole);
        if (resumeOpt.isEmpty()) {
            return null;
        }

        var checkpoint = resumeOpt.get();

        if (checkpoint.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            log.warnf("[AgentLoop] Attempted to resume session %s but status is %s. Aborting resume.",
                    sessionId, checkpoint.getStatus());
            return null;
        }

        String pendingTool = checkpoint.getPendingToolName();
        String pendingParams = checkpoint.getPendingToolParametersJson();

        boolean isDenial = AgentSentinels.GOAL_DENY_APPROVED.equals(goal);

        if (isDenial) {
            log.infof("[AgentLoop] User denied tool execution for session %s, tool %s",
                    sessionId, pendingTool);

            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setFailReason("User denied tool execution.");
            checkpoint.setPendingToolName(null);
            checkpoint.setPendingToolParametersJson(null);
            agentStore.save(checkpoint);

            return AgentResult.finished(
                    "❌ Tool execution was denied and the task has been cancelled.",
                    List.copyOf(history));
        }

        if (checkpoint.isParallelBatch()) {
            return resumeParallelBatch(context, history, checkpoint);
        }

        log.infof("[AgentLoop] Resuming session %s executing pending tool %s",
                sessionId, pendingTool);

        try {
            ToolResponse resumeResponse = WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    WorkspaceContext.userId(),
                    WorkspaceContext.chatId(),
                    agentRole,
                    () -> toolRegistry.invokeTool(pendingTool, pendingParams, sessionId));

            history.add(ReasoningStep.pending(
                    "Human Approved",
                    pendingTool,
                    Map.of("parameters", pendingParams != null ? pendingParams : "{}"),
                    0, 0)
                    .withObservation(resumeResponse.output()));

            checkpoint.setPendingToolName(null);
            checkpoint.setPendingToolParametersJson(null);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            agentStore.save(checkpoint);

            return null;

        } catch (Exception e) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setPendingToolName(null);
            checkpoint.setPendingToolParametersJson(null);
            agentStore.save(checkpoint);

            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            log.errorf("Resume tool execution failed for session=%s tool=%s: %s",
                    sessionId, pendingTool, errMsg);

            return AgentResult.failed(
                    "TOOL_EXECUTION_FAILED: " + errMsg,
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history));
        }
    }

    private AgentResult resumeParallelBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        log.infof("[PARALLEL-HITL-RESUME] session=%s resuming batch: %s",
                  sessionId, checkpoint.parallelToolNames());

        List<ParallelToolCall> calls;
        try {
            calls = objectMapper.readValue(
                checkpoint.getPendingToolParametersJson(),
                new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[PARALLEL-HITL-RESUME] session=%s: failed to parse batch: %s",
                       sessionId, e.getMessage());
            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setFailReason("Failed to parse parallel tool batch: " + e.getMessage());
            agentStore.save(checkpoint);
            return AgentResult.failed(
                "Failed to resume parallel tool batch — corrupt checkpoint.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history));
        }

        Map<String, String> decisions = Map.of();
        if (checkpoint.hasBatchToolDecisions()) {
            try {
                decisions = objectMapper.readValue(
                    checkpoint.getBatchToolDecisions(),
                    new TypeReference<Map<String, String>>() {}
                );
            } catch (Exception e) {
                log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load decisions — " +
                          "defaulting to PENDING for all tools: %s",
                          sessionId,
                          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        Map<String, String> preExecuted = Map.of();
        if (checkpoint.hasPreExecutedResults()) {
            try {
                preExecuted = objectMapper.readValue(
                    checkpoint.getPreExecutedResults(),
                    new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load pre-executed results — " +
                          "will re-invoke affected tools: %s",
                          sessionId,
                          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        List<ParallelToolCall> completed = new ArrayList<>();
        for (ParallelToolCall call : calls) {
            if (preExecuted.containsKey(call.toolName())) {
                try {
                    ToolResponse stored = objectMapper.readValue(
                        preExecuted.get(call.toolName()), ToolResponse.class);
                    completed.add(call.withResult(stored.output(), stored.success()));
                    continue;
                } catch (Exception e) {
                                        log.warnf("[PRE-EXEC-RESTORE] session=%s could not restore result for %s" +
                              ", re-invoking: %s",
                              sessionId, call.toolName(),
                              e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    // fall through to normal execution if restore failed
                }
            }

            String decision = decisions.getOrDefault(
                call.toolName(), AgentSentinels.TOOL_DECISION_PENDING);

            if (AgentSentinels.TOOL_DECISION_DENIED.equals(decision)) {
                completed.add(call.withResult("User denied this tool execution.", false));
                continue;
            }

            try {
                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    WorkspaceContext.userId(),
                    WorkspaceContext.chatId(),
                    agentRole,
                    () -> toolRegistry.invokeTool(
                        call.toolName(), call.paramsJson(), sessionId));
                completed.add(call.withResult(response.output(), response.success()));
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warnf("[PARALLEL-HITL-RESUME] session=%s tool=%s failed: %s",
                          sessionId, call.toolName(), errMsg);
                completed.add(call.withResult(
                    "Tool execution failed on resume: " + errMsg, false));
            }
        }

        String completedJson;
        try {
            completedJson = objectMapper.writeValueAsString(completed);
        } catch (Exception e) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            agentStore.save(checkpoint);
            return AgentResult.failed(
                "Failed to serialize resumed batch results.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history));
        }

        String summary = completed.stream()
            .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
            .collect(Collectors.joining(", "));

        ReasoningStep completedStep = ReasoningStep.parallelBatch(
            "Human Approved (batch)",
            Map.of("batch", completedJson),
            0, 0
        ).withObservation(summary);

        history.add(completedStep);

        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);

        return null; // Signal main loop to continue to next LLM call
    }
}