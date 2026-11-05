package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Handles checkpoint serialization for paused parallel tool batches. */
final class ParallelToolPauseSupport {
    private static final Logger log = Logger.getLogger(ParallelToolPauseSupport.class);
    private static final TypeReference<List<ParallelToolCall>> BATCH_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final BatchResultAggregator batchResultAggregator;
    private final ApprovalCheckpointManager checkpointManager;

    ParallelToolPauseSupport(
            ObjectMapper objectMapper,
            BatchResultAggregator batchResultAggregator,
            ApprovalCheckpointManager checkpointManager) {
        this.objectMapper = objectMapper;
        this.batchResultAggregator = batchResultAggregator;
        this.checkpointManager = checkpointManager;
    }

    StepDecision persistDelegatedPause(
            AgentExecutionContext context,
            List<ParallelToolCall> calls,
            List<ParallelToolCall> dedupCalls,
            List<ParallelToolCall> allowedCompleted,
            List<ReasoningStep> history,
            ReasoningStep batchStep,
            DelegatedParallelPauseSupport.Pause pause) {
        Map<String, ToolResponse> completedResults =
                DelegatedParallelPauseSupport.completedResults(dedupCalls, allowedCompleted, pause);
        List<ReasoningStep> historyAtPause = batchResultAggregator.appendPendingBatchStep(history, batchStep);
        List<ParallelToolCall> checkpointCalls = rewriteDelegatedCalls(calls, allowedCompleted);
        return StepDecision.terminal(checkpointManager.persistBatchWithPreExecuted(
                context,
                checkpointCalls,
                completedResults,
                historyAtPause,
                context.channelType(),
                context.channelPlatformId()));
    }

    List<ParallelToolCall> deserializeBatch(ReasoningStep batchStep, String sessionId) {
        try {
            Object batchRaw = batchStep.actionInput() != null
                    ? batchStep.actionInput().get(ReasoningStep.PARALLEL_BATCH_ACTION_INPUT_KEY)
                    : null;
            if (batchRaw == null) {
                return null;
            }
            String batchJson = batchRaw instanceof String text ? text : objectMapper.writeValueAsString(batchRaw);
            return objectMapper.readValue(batchJson, BATCH_TYPE);
        } catch (Exception e) {
            log.errorf(
                    LogUtil.relevantThrowable(e),
                    "[PARALLEL] session=%s: batch deserialization failed: %s",
                    sessionId,
                    LogUtil.rootMessage(e));
            return null;
        }
    }

    static String resultKey(ParallelToolCall call) {
        return call.callId() == null || call.callId().isBlank() ? call.toolName() : call.callId();
    }

    List<ParallelToolCall> rewriteDelegatedCalls(List<ParallelToolCall> calls, List<ParallelToolCall> completed) {
        return calls.stream()
                .map(original -> completed.stream()
                        .filter(candidate -> Objects.equals(candidate.callId(), original.callId()))
                        .filter(candidate ->
                                ToolCapability.DELEGATE_TO_AGENT.toolName().equals(candidate.toolName()))
                        .findFirst()
                        .map(candidate -> ParallelToolCall.pending(
                                        candidate.callId(),
                                        candidate.toolName(),
                                        candidate.params(),
                                        candidate.paramsJson())
                                .withOperationId(
                                        candidate.operationId() == null
                                                ? ToolOperationIdentity.generateCanonicalUuid()
                                                : candidate.operationId()))
                        .orElse(original))
                .toList();
    }
}
