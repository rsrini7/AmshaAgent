package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Reconciles parallel batch tool calls with journal state and appends aggregate results
 * per Proposal §10 and §12.6.
 */
@ApplicationScoped
public class ParallelBatchAttemptReconciler {

    private static final Logger log = Logger.getLogger(ParallelBatchAttemptReconciler.class);
    private static final int MAX_CAS_RETRIES = 3;
    private static final String DEFAULT_INDETERMINATE_OBSERVATION =
            "Tool outcome is uncertain; automatic replay is blocked.";

    private final Instance<ToolAttemptStorePort> toolAttemptStores;
    private final Instance<CheckpointStorePort> checkpointStores;
    private final ToolAttemptStorePort directAttemptStore;
    private final CheckpointStorePort directCheckpointStore;
    private final CheckpointSerializer checkpointSerializer;
    private final ObjectMapper objectMapper;

    protected ParallelBatchAttemptReconciler() {
        this(null, null, null, null, null, null);
    }

    @Inject
    public ParallelBatchAttemptReconciler(
            Instance<ToolAttemptStorePort> toolAttemptStores,
            Instance<CheckpointStorePort> checkpointStores,
            CheckpointSerializer checkpointSerializer,
            ObjectMapper objectMapper) {
        this(toolAttemptStores, checkpointStores, null, null, checkpointSerializer, objectMapper);
    }

    public ParallelBatchAttemptReconciler(
            ToolAttemptStorePort directAttemptStore,
            CheckpointStorePort directCheckpointStore,
            CheckpointSerializer checkpointSerializer,
            ObjectMapper objectMapper) {
        this(null, null, directAttemptStore, directCheckpointStore, checkpointSerializer, objectMapper);
    }

    private ParallelBatchAttemptReconciler(
            Instance<ToolAttemptStorePort> toolAttemptStores,
            Instance<CheckpointStorePort> checkpointStores,
            ToolAttemptStorePort directAttemptStore,
            CheckpointStorePort directCheckpointStore,
            CheckpointSerializer checkpointSerializer,
            ObjectMapper objectMapper) {
        this.toolAttemptStores = toolAttemptStores;
        this.checkpointStores = checkpointStores;
        this.directAttemptStore = directAttemptStore;
        this.directCheckpointStore = directCheckpointStore;
        this.checkpointSerializer = checkpointSerializer;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public sealed interface ChildOutcome {
        record Settled(ToolAttemptDto attempt, ParallelToolCall completedCall) implements ChildOutcome {}

        record ReadyToExecute(Optional<ToolAttemptDto> admittedAttempt) implements ChildOutcome {}

        record Blocked(String reason) implements ChildOutcome {}

        record MissingOperationId() implements ChildOutcome {}
    }

    public ChildOutcome reconcileChild(String workspaceId, String sessionId, ParallelToolCall call) {
        if (call == null || call.operationId() == null || call.operationId().isBlank()) {
            return new ChildOutcome.MissingOperationId();
        }
        ToolAttemptStorePort attemptStore = resolveAttemptStore();
        if (attemptStore == null) {
            return new ChildOutcome.ReadyToExecute(Optional.empty());
        }

        Optional<ToolAttemptDto> attemptOpt;
        try {
            attemptOpt = attemptStore.findLatestAttemptByOperation(workspaceId, call.operationId());
        } catch (Exception e) {
            log.errorf(
                    "Store lookup error for batch child op %s in workspace %s: %s",
                    call.operationId(), workspaceId, LogUtil.rootMessage(e));
            return new ChildOutcome.Blocked("Durable tool attempt lookup failed: " + e.getMessage());
        }

        if (attemptOpt.isEmpty()) {
            return new ChildOutcome.ReadyToExecute(Optional.empty());
        }

        ToolAttemptDto attempt = attemptOpt.get();
        if (!Objects.equals(attempt.sessionId(), sessionId) || !Objects.equals(attempt.workspaceId(), workspaceId)) {
            log.errorf(
                    "Attempt %s session/workspace mismatch: expected (%s, %s) but got (%s, %s)",
                    attempt.attemptId(), sessionId, workspaceId, attempt.sessionId(), attempt.workspaceId());
            return new ChildOutcome.Blocked("Tool attempt session or workspace mismatch.");
        }

        ToolAttemptStatus status = attempt.status();
        if (status == ToolAttemptStatus.ADMITTED || status == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH) {
            return new ChildOutcome.ReadyToExecute(Optional.of(attempt));
        }

        if (status == ToolAttemptStatus.LAUNCH_REQUESTED || status == ToolAttemptStatus.RUNNING) {
            log.warnf(
                    "Child attempt %s for op %s in non-terminal running state %s without settlement",
                    attempt.attemptId(), call.operationId(), status);
            return new ChildOutcome.Blocked("Tool attempt is in non-terminal running state: " + status);
        }

        if (status.isSettled() || status == ToolAttemptStatus.TIMED_OUT || status == ToolAttemptStatus.INDETERMINATE) {
            String observation =
                    switch (status) {
                        case SETTLED_SUCCESS -> attempt.sanitizedObservation();
                        case SETTLED_ERROR ->
                            attempt.sanitizedObservation() != null
                                    ? attempt.sanitizedObservation()
                                    : "Tool attempt completed with error.";
                        case TIMED_OUT ->
                            attempt.sanitizedObservation() != null
                                    ? attempt.sanitizedObservation()
                                    : "Tool attempt timed out.";
                        case INDETERMINATE ->
                            attempt.sanitizedObservation() != null
                                    ? attempt.sanitizedObservation()
                                    : DEFAULT_INDETERMINATE_OBSERVATION;
                        default -> throw new IllegalStateException("Unexpected attempt status: " + status);
                    };
            boolean success = status == ToolAttemptStatus.SETTLED_SUCCESS;
            return new ChildOutcome.Settled(attempt, call.withResult(observation, success));
        }

        return new ChildOutcome.Blocked("Unsupported attempt status: " + status);
    }

    public Optional<String> findAttemptId(String workspaceId, String operationId) {
        if (workspaceId == null || operationId == null) {
            return Optional.empty();
        }
        ToolAttemptStorePort attemptStore = resolveAttemptStore();
        if (attemptStore == null) {
            return Optional.empty();
        }
        try {
            return attemptStore
                    .findLatestAttemptByOperation(workspaceId, operationId)
                    .map(ToolAttemptDto::attemptId);
        } catch (Exception e) {
            log.warnf("Failed to lookup attempt for operation %s: %s", operationId, LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    public AgentResult appendAggregateResult(
            String workspaceId,
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            List<ParallelToolCall> originalCalls,
            List<ParallelToolCall> completed,
            List<String> attemptIds,
            StatefulAgentStore agentStore) {
        CheckpointStorePort cpStore = resolveCheckpointStore();
        String completedJson;
        try {
            completedJson = objectMapper.writeValueAsString(completed);
        } catch (Exception e) {
            log.errorf("[PARALLEL] Failed to serialize completed batch: %s", LogUtil.rootMessage(e));
            return failBatch(checkpoint, history, agentStore, "Failed to serialize parallel tool results.");
        }

        String summary = completed.stream()
                .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
                .collect(Collectors.joining(", "));
        boolean allSuccess = completed.stream().allMatch(ParallelToolCall::success);

        OptionalInt pendingIdx = findPendingParallelStepIndex(history);
        ReasoningStep pendingStep = pendingIdx.isPresent() ? history.get(pendingIdx.getAsInt()) : null;
        String thought =
                pendingStep != null && pendingStep.thought() != null ? pendingStep.thought() : "Human Approved (batch)";
        int inTokens = pendingStep != null ? pendingStep.inputTokens() : 0;
        int outTokens = pendingStep != null ? pendingStep.outputTokens() : 0;

        ReasoningStep aggregateStep = ReasoningStep.parallelBatch(
                        thought,
                        Map.of(ReasoningStep.PARALLEL_BATCH_ACTION_INPUT_KEY, completedJson),
                        inTokens,
                        outTokens)
                .withObservation(summary, allSuccess);

        List<String> distinctAttemptIds = attemptIds != null
                ? attemptIds.stream().filter(Objects::nonNull).distinct().toList()
                : List.of();

        if (cpStore != null && checkpoint.getId() != null && !distinctAttemptIds.isEmpty()) {
            ReasoningStepDto stepDto = checkpointSerializer != null
                    ? checkpointSerializer.toDto(aggregateStep)
                    : toStepDtoFallback(aggregateStep);

            int expectedVersion = checkpoint.getVersion() != null ? checkpoint.getVersion() : 0;
            CheckpointAppendResult appendResult = null;
            for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
                appendResult = cpStore.appendResultIfAbsent(
                        workspaceId, checkpoint.getId(), expectedVersion, stepDto, distinctAttemptIds);
                if (appendResult == CheckpointAppendResult.STALE) {
                    Optional<CheckpointDto> reloaded = cpStore.findById(checkpoint.getId());
                    if (reloaded.isPresent() && reloaded.get().version() != null) {
                        expectedVersion = reloaded.get().version();
                        checkpoint.setVersion(expectedVersion);
                        log.infof(
                                "Parallel batch checkpoint version stale; reloaded version %d, retry %d/%d",
                                expectedVersion, retry + 1, MAX_CAS_RETRIES);
                        continue;
                    }
                }
                break;
            }

            if (appendResult == CheckpointAppendResult.APPENDED) {
                refreshCheckpointVersion(checkpoint, cpStore, expectedVersion + 1);
                applyStepToHistory(history, pendingIdx, aggregateStep);
                completeInProgressCheckpoint(checkpoint, agentStore);
                return null;
            }

            if (appendResult == CheckpointAppendResult.ALREADY_PRESENT) {
                refreshCheckpointVersion(checkpoint, cpStore, expectedVersion);
                applyStepToHistory(history, pendingIdx, aggregateStep);
                completeInProgressCheckpoint(checkpoint, agentStore);
                return null;
            }

            if (appendResult == CheckpointAppendResult.CONFLICT) {
                log.errorf("Durable checkpoint append conflict for batch attempts %s", distinctAttemptIds);
                return failBatch(
                        checkpoint, history, agentStore, "Durable checkpoint append conflict for parallel batch.");
            }

            log.errorf(
                    "Durable checkpoint append exceeded CAS retries for batch in session %s",
                    checkpoint.getSessionId());
            return failBatch(checkpoint, history, agentStore, "Durable checkpoint append exceeded CAS retries.");
        }

        applyStepToHistory(history, pendingIdx, aggregateStep);
        completeInProgressCheckpoint(checkpoint, agentStore);
        return null;
    }

    private OptionalInt findPendingParallelStepIndex(List<ReasoningStep> history) {
        if (history == null) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < history.size(); i++) {
            ReasoningStep step = history.get(i);
            if (step.isParallelBatch() && step.observation() == null) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private void applyStepToHistory(List<ReasoningStep> history, OptionalInt pendingIdx, ReasoningStep aggregateStep) {
        if (pendingIdx.isPresent()) {
            history.set(pendingIdx.getAsInt(), aggregateStep);
        } else {
            history.add(aggregateStep);
        }
    }

    private void refreshCheckpointVersion(AgentCheckpoint checkpoint, CheckpointStorePort cpStore, int fallback) {
        if (checkpoint.getId() != null) {
            cpStore.findById(checkpoint.getId())
                    .ifPresentOrElse(
                            dto -> checkpoint.setVersion(dto.version()), () -> checkpoint.setVersion(fallback));
        } else {
            checkpoint.setVersion(fallback);
        }
    }

    private void completeInProgressCheckpoint(AgentCheckpoint checkpoint, StatefulAgentStore agentStore) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        if (agentStore != null) {
            agentStore.save(checkpoint);
        }
    }

    private AgentResult failBatch(
            AgentCheckpoint checkpoint, List<ReasoningStep> history, StatefulAgentStore agentStore, String reason) {
        checkpoint.setStatus(CheckpointStatus.FAILED);
        if (agentStore != null) {
            agentStore.save(checkpoint);
        }
        return AgentResult.failed(reason, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
    }

    private ReasoningStepDto toStepDtoFallback(ReasoningStep step) {
        String thoughtJson = null;
        String actionInputJson = null;
        try {
            thoughtJson = step.thought() != null ? objectMapper.writeValueAsString(step.thought()) : null;
            actionInputJson = step.actionInput() != null ? objectMapper.writeValueAsString(step.actionInput()) : null;
        } catch (Exception _) {
            // best-effort
        }
        return new ReasoningStepDto(
                thoughtJson,
                step.action(),
                actionInputJson,
                step.observation(),
                step.inputTokens(),
                step.outputTokens(),
                step.success(),
                step.errorCode(),
                step.traceId(),
                step.rootCauseClass());
    }

    private ToolAttemptStorePort resolveAttemptStore() {
        if (directAttemptStore != null) {
            return directAttemptStore;
        }
        if (toolAttemptStores != null && !toolAttemptStores.isUnsatisfied()) {
            return toolAttemptStores.get();
        }
        return null;
    }

    private CheckpointStorePort resolveCheckpointStore() {
        if (directCheckpointStore != null) {
            return directCheckpointStore;
        }
        if (checkpointStores != null && !checkpointStores.isUnsatisfied()) {
            return checkpointStores.get();
        }
        return null;
    }
}
