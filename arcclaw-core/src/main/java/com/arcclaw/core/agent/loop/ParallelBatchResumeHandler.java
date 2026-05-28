package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

class ParallelBatchResumeHandler {

    private static final Logger log = Logger.getLogger(ParallelBatchResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    ParallelBatchResumeHandler(StatefulAgentStore agentStore, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    AgentResult resumeParallelBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType) {

        String sessionId = context.sessionId();
        log.infof("[ResumeStrategy] Resuming parallel batch for session %s", sessionId);

        List<ParallelToolCall> calls;
        try {
            calls = objectMapper.readValue(
                    checkpoint.getPendingToolParametersJson(), new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[ResumeStrategy] session=%s: failed to parse batch", checkpoint.getSessionId());
            return handleCorruptBatch(checkpoint, history);
        }

        Map<String, ToolDecision> decisions = Map.of();
        if (checkpoint.hasBatchToolDecisions()) {
            try {
                decisions = objectMapper.readValue(
                        checkpoint.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
            } catch (Exception e) {
                log.debugf("Batch decision parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }

        Map<String, String> preExecuted = Map.of();
        if (checkpoint.hasPreExecutedResults()) {
            try {
                preExecuted = objectMapper.readValue(
                        checkpoint.getPreExecutedResults(), new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.debugf("Pre-executed result parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }

        List<ParallelToolCall> completed = new ArrayList<>();
        for (ParallelToolCall call : calls) {
            if (preExecuted.containsKey(call.toolName())) {
                try {
                    ToolResponse stored = objectMapper.readValue(preExecuted.get(call.toolName()), ToolResponse.class);
                    completed.add(
                            switch (stored) {
                                case ToolResponse.ToolSuccess success -> call.withResult(success.output(), true);
                                case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                                case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                            });
                    continue;
                } catch (Exception e) {
                    log.debugf("Pre-executed tool response parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
                }
            }

            if (decisions.getOrDefault(call.toolName(), ToolDecision.PENDING) == ToolDecision.DENIED) {
                completed.add(call.withResult("User denied this tool execution.", false));
                continue;
            }

            try {
                String effectiveRole =
                        checkpoint.getPendingToolRole() != null ? checkpoint.getPendingToolRole() : context.agentRole();
                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                        context.sessionId(),
                        context.userId(),
                        context.chatId(),
                        effectiveRole,
                        channelType,
                        context.channelPlatformId(),
                        () -> toolRegistry.invokeTool(call.toolName(), call.paramsJson(), context.sessionId()));

                completed.add(
                        switch (response) {
                            case ToolResponse.ToolSuccess success -> call.withResult(success.output(), true);
                            case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                            case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                        });
            } catch (Exception e) {
                completed.add(call.withResult(
                        "Tool execution failed on resume: " + AgentErrorMessageUtil.safeMessage(e), false));
            }
        }

        try {
            String completedJson = objectMapper.writeValueAsString(completed);
            String summary = completed.stream()
                    .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
                    .collect(Collectors.joining(", "));
            history.add(ReasoningStep.parallelBatch("Human Approved (batch)", Map.of("batch", completedJson), 0, 0)
                    .withObservation(summary));
            completeInProgressCheckpoint(checkpoint);
            return null;
        } catch (Exception e) {
            return handleCorruptBatch(checkpoint, history);
        }
    }

    private AgentResult handleCorruptBatch(AgentCheckpoint cp, List<ReasoningStep> history) {
        cp.setStatus(CheckpointStatus.FAILED);
        agentStore.save(cp);
        return AgentResult.failed(
                "Failed to finalize parallel tool batch.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history));
    }

    private void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }
}
