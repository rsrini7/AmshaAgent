package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Strategy dispatcher for session resumption. Handles the logic for resuming 
 * from various checkpoint states (APPROVAL, PARALLEL_BATCH, etc.) to keep
 * CheckpointResumeHandler focused on orchestration.
 */
class ResumeStrategyDispatcher {

    private static final Logger log = Logger.getLogger(ResumeStrategyDispatcher.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ReasoningEngine reasoningEngine;

    ResumeStrategyDispatcher(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ReasoningEngine reasoningEngine) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.reasoningEngine = reasoningEngine;
    }

    AgentResult handle(AgentExecutionContext context,
                       List<ReasoningStep> history,
                       MessageContext messageContext,
                       AgentStateMachineRunner stateMachineRunner,
                       AgentCheckpoint checkpoint) {
        
        if (AgentSentinels.GOAL_DENY_APPROVED.equals(context.goal())) {
            return performDenialCleanup(context, history, checkpoint);
        }

        // §Issue 6: Strict Goal Isolation
        String resumeGoal = (messageContext.getGoal() != null && !messageContext.getGoal().isBlank() && !AgentSentinels.GOAL_RESUME_APPROVED.equals(messageContext.getGoal()))
                ? messageContext.getGoal()
                : resolveResumeGoal(checkpoint);
        
        AgentExecutionContext finalContext = context;
        if (!context.goal().equals(resumeGoal)) {
            log.infof("[ResumeStrategy] Isolated specific resumption goal for session %s: %s", context.sessionId(), resumeGoal);
            finalContext = new AgentExecutionContext(
                    context.sessionId(), context.userId(), context.chatId(), context.agentRole(),
                    resumeGoal, context.autonomyLevel(), context.structuredOutputMode(), context.maxIterations(),
                    context.startingIteration(), context.channelType(), context.channelPlatformId(),
                    context.systemNote(), context.data()
            );
        }

        String resolvedChannelType = resolveChannelType(finalContext, messageContext);

        if (checkpoint.isParallelBatch()) {
            return resumeParallelBatch(finalContext, history, checkpoint, resolvedChannelType);
        }

        return executePendingTool(finalContext, history, checkpoint, resolvedChannelType, stateMachineRunner);
    }

    public String resolveResumeGoal(String sessionId, String agentRole) {
        return agentStore.load(sessionId, agentRole)
            .map(this::resolveResumeGoal)
            .orElse(AgentSentinels.GOAL_RESUME_APPROVED);
    }

    public String resolveResumeGoal(AgentCheckpoint cp) {
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

    private AgentResult performDenialCleanup(AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint) {
        log.infof("[ResumeStrategy] User denied tool execution for session %s", context.sessionId());
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setFailReason("User denied tool execution.");
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);

        return AgentResult.finished("❌ Tool execution was denied and the task has been cancelled.", List.copyOf(history));
    }

    private AgentResult executePendingTool(AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint, String channelType, AgentStateMachineRunner stateMachineRunner) {
        context.setCheckpointRestoreOffset(history.size());
        String toolName = checkpoint.getPendingToolName();
        String paramsJson = checkpoint.getPendingToolParametersJson();
        String sessionId = context.sessionId();
        
        String effectiveRole = checkpoint.getPendingToolRole() != null 
                ? checkpoint.getPendingToolRole() 
                : context.agentRole();

        log.infof("[ResumeStrategy] Resuming session %s executing pending tool %s as role %s", 
                sessionId, toolName, effectiveRole);

        int retryCount = 0;
        int maxRetries = 2;
        
        while (true) {
            try {
                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                        sessionId, context.userId(), context.chatId(), effectiveRole,
                        channelType, context.channelPlatformId(),
                        () -> toolRegistry.invokeTool(toolName, paramsJson, sessionId));

                if (response instanceof ToolResponse.ToolFailure failure && failure.isInfraFailure()) {
                    return AgentResult.failed("⚠️ The sandbox execution environment is currently offline.",
                            AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                }

                recordReasoningStep(toolName, paramsJson, response.output(), response instanceof ToolResponse.ToolSuccess, history, sessionId);
                
                if (ToolCapability.INGEST_DOCUMENT.toolName().equals(toolName)) {
                    history.add(ReasoningStep.systemMessage(
                            "The document has been ingested successfully. " +
                            "Summarize what was stored for the user in a natural conversational tone."));
                }
                
                completeInProgressCheckpoint(checkpoint);

                if (response instanceof ToolResponse.ToolSuccess success) {
                    if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) {
                        return AgentResult.finished(success.output(), List.copyOf(history));
                    }
                    return stateMachineRunner.runFinishPass(context, reasoningEngine, history);
                } else {
                    return null;
                }
            } catch (Exception e) {
                long retryAfter = AgentErrorMessageUtil.parseRetryAfterMillis(e);
                if (retryAfter > 0 && retryAfter < 5000 && retryCount < maxRetries) {
                    retryCount++;
                    try {
                        Thread.sleep(retryAfter + 100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Resume interrupted during backoff", ie);
                    }
                    continue; 
                }
                return handleResumeExecutionError(checkpoint, toolName, sessionId, history, e);
            }
        }
    }

    private void recordReasoningStep(String tool, String paramsJson, String output, boolean success, List<ReasoningStep> history, String sessionId) {
        Map<String, Object> params = Map.of();
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warnf("Failed to parse params for session %s", sessionId);
        }
        history.add(ReasoningStep.pending("Executing " + tool + " after human approval.", tool, params, 0, 0)
                .withObservation(output, success));
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
        log.infof("[ResumeStrategy] Resuming parallel batch for session %s", sessionId);

        List<ParallelToolCall> calls;
        try {
            calls = objectMapper.readValue(checkpoint.getPendingToolParametersJson(), new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[ResumeStrategy] session=%s: failed to parse batch", checkpoint.getSessionId());
            return handleCorruptBatch(checkpoint, history);
        }

        Map<String, ToolDecision> decisions = Map.of();
        if (checkpoint.hasBatchToolDecisions()) {
            try {
                decisions = objectMapper.readValue(checkpoint.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
            } catch (Exception e) {
                log.debugf("Non-fatal: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        Map<String, String> preExecuted = Map.of();
        if (checkpoint.hasPreExecutedResults()) {
            try {
                preExecuted = objectMapper.readValue(checkpoint.getPreExecutedResults(), new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.debugf("Non-fatal: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        List<ParallelToolCall> completed = new ArrayList<>();
        for (ParallelToolCall call : calls) {
            if (preExecuted.containsKey(call.toolName())) {
                try {
                    ToolResponse stored = objectMapper.readValue(preExecuted.get(call.toolName()), ToolResponse.class);
                    completed.add(switch (stored) {
                        case ToolResponse.ToolSuccess success -> call.withResult(success.output(), true);
                        case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                        case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                    });
                    continue;
                } catch (Exception e) {
                    log.debugf("Non-fatal: %s", ExceptionUtils.getRootCauseMessage(e));
                }
            }

            if (decisions.getOrDefault(call.toolName(), ToolDecision.PENDING) == ToolDecision.DENIED) {
                completed.add(call.withResult("User denied this tool execution.", false));
                continue;
            }

            try {
                String effectiveRole = checkpoint.getPendingToolRole() != null ? checkpoint.getPendingToolRole() : context.agentRole();
                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                    context.sessionId(), context.userId(), context.chatId(), effectiveRole,
                    channelType, context.channelPlatformId(),
                    () -> toolRegistry.invokeTool(call.toolName(), call.paramsJson(), context.sessionId()));
                
                completed.add(switch (response) {
                    case ToolResponse.ToolSuccess success -> call.withResult(success.output(), true);
                    case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                    case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                });
            } catch (Exception e) {
                completed.add(call.withResult("Tool execution failed on resume: " + AgentErrorMessageUtil.safeMessage(e), false));
            }
        }

        try {
            String completedJson = objectMapper.writeValueAsString(completed);
            String summary = completed.stream().map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL")).collect(Collectors.joining(", "));
            history.add(ReasoningStep.parallelBatch("Human Approved (batch)", Map.of("batch", completedJson), 0, 0).withObservation(summary));
            completeInProgressCheckpoint(checkpoint);
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
        return "unknown";
    }
}
