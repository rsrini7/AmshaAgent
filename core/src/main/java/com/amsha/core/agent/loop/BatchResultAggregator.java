package com.amsha.core.agent.loop;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.observability.AgentObserver;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

public class BatchResultAggregator {

    private static final Logger log = Logger.getLogger(BatchResultAggregator.class);

    private final ObjectMapper objectMapper;
    private final Instance<AgentObserver> observers;

    public BatchResultAggregator(ObjectMapper objectMapper, Instance<AgentObserver> observers) {
        this.objectMapper = objectMapper;
        this.observers = observers;
    }

    public List<ParallelToolCall> mergeCompletedCalls(
            List<ParallelToolCall> originalCalls,
            List<ParallelToolCall> dedupCalls,
            List<ParallelToolCall> allowedCompleted) {
        List<ParallelToolCall> allCompleted = new ArrayList<>();
        for (ParallelToolCall original : originalCalls) {
            Optional<ParallelToolCall> dedup = dedupCalls.stream()
                    .filter(d -> Objects.equals(d.callId(), original.callId()))
                    .findFirst();
            if (dedup.isPresent()) {
                allCompleted.add(dedup.get());
            } else {
                allowedCompleted.stream()
                        .filter(c -> Objects.equals(c.callId(), original.callId()))
                        .findFirst()
                        .ifPresent(allCompleted::add);
            }
        }
        return allCompleted;
    }

    public StepDecision buildCompletedBatchDecision(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep batchStep,
            List<ParallelToolCall> completed,
            int consecutiveFailures) {

        String batchJson;
        try {
            batchJson = objectMapper.writeValueAsString(completed);
        } catch (Exception e) {
            log.errorf("[PARALLEL] Failed to serialize completed batch: %s", LogUtil.rootMessage(e));
            return StepDecision.terminal(AgentResult.failed(
                    "Failed to serialize parallel tool results.",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
        }

        Map<String, Object> completedPayload = new HashMap<>();
        completedPayload.put("batch", batchJson);

        String summary = completed.stream()
                .map(c -> String.format("[%s → %s]", c.toolName(), c.success() ? "OK" : "FAIL"))
                .collect(Collectors.joining(", "));

        log.infof("[PARALLEL] session=%s batch completed: %s", context.sessionId(), summary);

        boolean allSuccess = completed.stream().allMatch(ParallelToolCall::success);

        ReasoningStep completedBatchStep = ReasoningStep.parallelBatch(
                        batchStep.thought(), completedPayload, batchStep.inputTokens(), batchStep.outputTokens())
                .withObservation(summary, allSuccess);

        history.add(completedBatchStep);

        observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), completedBatchStep));

        return StepDecision.proceed(completedBatchStep);
    }

    public List<ReasoningStep> appendPendingBatchStep(List<ReasoningStep> history, ReasoningStep batchStep) {
        List<ReasoningStep> snapshot = new ArrayList<>(history);
        snapshot.add(batchStep);
        return List.copyOf(snapshot);
    }
}
