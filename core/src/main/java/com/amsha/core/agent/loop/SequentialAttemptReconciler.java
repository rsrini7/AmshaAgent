package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Reconciles sequential checkpoint tool attempts with journal state per Proposal §6 and §12.6.
 */
@ApplicationScoped
public class SequentialAttemptReconciler {

    private static final Logger log = Logger.getLogger(SequentialAttemptReconciler.class);
    private static final int MAX_CAS_RETRIES = 3;
    private static final String DEFAULT_INDETERMINATE_OBSERVATION =
            "Tool outcome is uncertain; automatic replay is blocked.";
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() {};

    private final Instance<ToolAttemptStorePort> toolAttemptStores;
    private final Instance<CheckpointStorePort> checkpointStores;
    private final ToolAttemptStorePort directAttemptStore;
    private final CheckpointStorePort directCheckpointStore;
    private final CheckpointSerializer checkpointSerializer;
    private final ObjectMapper objectMapper;

    protected SequentialAttemptReconciler() {
        this(null, null, null, null, null, null);
    }

    @Inject
    public SequentialAttemptReconciler(
            Instance<ToolAttemptStorePort> toolAttemptStores,
            Instance<CheckpointStorePort> checkpointStores,
            CheckpointSerializer checkpointSerializer,
            ObjectMapper objectMapper) {
        this(toolAttemptStores, checkpointStores, null, null, checkpointSerializer, objectMapper);
    }

    public SequentialAttemptReconciler(
            ToolAttemptStorePort directAttemptStore,
            CheckpointStorePort directCheckpointStore,
            CheckpointSerializer checkpointSerializer,
            ObjectMapper objectMapper) {
        this(null, null, directAttemptStore, directCheckpointStore, checkpointSerializer, objectMapper);
    }

    private SequentialAttemptReconciler(
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

    /**
     * Outcome of sequential attempt reconciliation.
     */
    public sealed interface ReconciliationOutcome {
        /** Settled or indeterminate outcome persisted; caller should run finish pass without re-executing. */
        record Reconciled(ReasoningStep reconciledStep, boolean indeterminate) implements ReconciliationOutcome {}

        /** Attempt absent or in ADMITTED status; proceed with ordinary governance and execution. */
        record ProceedToExecution(Optional<ToolAttemptDto> admittedAttempt) implements ReconciliationOutcome {}

        /** Conflict, store error, or unrecoverable state; resume is blocked. */
        record Blocked(String reason) implements ReconciliationOutcome {}
    }

    /**
     * Reconciles a pending tool operation against durable tool attempts before invoking the executor.
     */
    public ReconciliationOutcome reconcile(
            AgentExecutionContext context,
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            String toolName,
            String paramsJson,
            String operationId) {
        String workspaceId = WorkspaceContext.activeWorkspaceId().orElse(null);
        if (workspaceId == null || workspaceId.isBlank()) {
            log.warnf("Reconciliation blocked: active workspaceId is missing for session %s", context.sessionId());
            return new ReconciliationOutcome.Blocked("Active workspace is required for tool attempt reconciliation.");
        }

        ToolAttemptStorePort attemptStore = resolveAttemptStore();
        CheckpointStorePort cpStore = resolveCheckpointStore();
        if (attemptStore == null || cpStore == null) {
            log.errorf("Reconciliation blocked: required store ports unavailable for session %s", context.sessionId());
            return new ReconciliationOutcome.Blocked("Durable persistence stores are unavailable.");
        }

        Optional<ToolAttemptDto> attemptOpt;
        try {
            attemptOpt = attemptStore.findLatestAttemptByOperation(workspaceId, operationId);
        } catch (Exception e) {
            log.errorf(
                    "Store lookup error for operation %s in workspace %s: %s",
                    operationId, workspaceId, LogUtil.rootMessage(e));
            return new ReconciliationOutcome.Blocked("Durable tool attempt lookup failed: " + e.getMessage());
        }

        if (attemptOpt.isEmpty()) {
            log.debugf("No prior attempt for operation %s; proceeding to admission and execution", operationId);
            return new ReconciliationOutcome.ProceedToExecution(Optional.empty());
        }

        ToolAttemptDto attempt = attemptOpt.get();
        if (!Objects.equals(attempt.sessionId(), checkpoint.getSessionId())
                || !Objects.equals(attempt.workspaceId(), workspaceId)) {
            log.errorf(
                    "Attempt %s session/workspace mismatch: expected (%s, %s) but got (%s, %s)",
                    attempt.attemptId(),
                    checkpoint.getSessionId(),
                    workspaceId,
                    attempt.sessionId(),
                    attempt.workspaceId());
            return new ReconciliationOutcome.Blocked("Tool attempt session or workspace mismatch.");
        }

        ToolAttemptStatus status = attempt.status();
        if (status == ToolAttemptStatus.ADMITTED || status == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH) {
            log.infof(
                    "Attempt %s is in pre-launch state %s; resuming existing attempt through ordinary governance",
                    attempt.attemptId(), status);
            return new ReconciliationOutcome.ProceedToExecution(Optional.of(attempt));
        }

        if (status == ToolAttemptStatus.LAUNCH_REQUESTED || status == ToolAttemptStatus.RUNNING) {
            log.warnf(
                    "Attempt %s for operation %s in non-terminal running state %s without settlement",
                    attempt.attemptId(), operationId, status);
            return new ReconciliationOutcome.Blocked("Tool attempt is in non-terminal running state: " + status);
        }

        if (status.isSettled() || status == ToolAttemptStatus.TIMED_OUT || status == ToolAttemptStatus.INDETERMINATE) {
            return reconcileTerminalOutcome(
                    workspaceId, checkpoint, history, toolName, paramsJson, operationId, attempt, cpStore);
        }

        return new ReconciliationOutcome.Blocked("Unsupported attempt status for reconciliation: " + status);
    }

    private ReconciliationOutcome reconcileTerminalOutcome(
            String workspaceId,
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            String toolName,
            String paramsJson,
            String operationId,
            ToolAttemptDto attempt,
            CheckpointStorePort cpStore) {
        boolean isIndeterminate = attempt.status() == ToolAttemptStatus.INDETERMINATE;
        String observation;
        Boolean success;
        String errorCode;

        switch (attempt.status()) {
            case SETTLED_SUCCESS -> {
                observation = attempt.sanitizedObservation();
                success = Boolean.TRUE;
                errorCode = null;
            }
            case SETTLED_ERROR -> {
                observation = attempt.sanitizedObservation() != null
                        ? attempt.sanitizedObservation()
                        : "Tool attempt completed with error.";
                success = Boolean.FALSE;
                errorCode = "TOOL_ATTEMPT_ERROR";
            }
            case TIMED_OUT -> {
                observation = attempt.sanitizedObservation() != null
                        ? attempt.sanitizedObservation()
                        : "Tool attempt timed out.";
                success = Boolean.FALSE;
                errorCode = "TOOL_ATTEMPT_TIMED_OUT";
            }
            case INDETERMINATE -> {
                observation = attempt.sanitizedObservation() != null
                        ? attempt.sanitizedObservation()
                        : DEFAULT_INDETERMINATE_OBSERVATION;
                success = Boolean.FALSE;
                errorCode = "INDETERMINATE_OUTCOME";
            }
            default -> throw new IllegalStateException("Unexpected attempt status: " + attempt.status());
        }

        OptionalInt pendingIdx = PendingToolOperationIdLookup.findUniquePendingIndex(history, operationId);
        ReasoningStep resumedStep;
        if (pendingIdx.isPresent()) {
            ReasoningStep pending = history.get(pendingIdx.getAsInt());
            resumedStep = new ReasoningStep.ToolCallStep(
                            pending.thought(),
                            pending.action(),
                            pending.actionInput(),
                            observation,
                            pending.inputTokens(),
                            pending.outputTokens(),
                            success,
                            errorCode,
                            pending.traceId(),
                            pending.rootCauseClass(),
                            pending.isFastPath(),
                            pending.isTruncationRecovery())
                    .withOperationId(operationId);
        } else {
            Map<String, Object> params = parseParams(paramsJson);
            resumedStep = ReasoningStep.of(
                            "Executing " + toolName + " after human approval.",
                            toolName,
                            params,
                            observation,
                            0,
                            0,
                            success,
                            errorCode,
                            null,
                            null)
                    .withOperationId(operationId);
        }

        ReasoningStepDto stepDto =
                checkpointSerializer != null ? checkpointSerializer.toDto(resumedStep) : toStepDtoFallback(resumedStep);

        int expectedVersion = checkpoint.getVersion() != null ? checkpoint.getVersion() : 0;
        CheckpointAppendResult appendResult = null;
        for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
            appendResult = cpStore.appendResultIfAbsent(
                    workspaceId, checkpoint.getId(), expectedVersion, stepDto, List.of(attempt.attemptId()));
            if (appendResult != CheckpointAppendResult.STALE) {
                break;
            }
            Optional<CheckpointDto> latestCp = cpStore.findById(checkpoint.getId());
            if (latestCp.isPresent()) {
                expectedVersion = latestCp.get().version();
                checkpoint.setVersion(expectedVersion);
            } else {
                break;
            }
        }

        if (appendResult == CheckpointAppendResult.APPENDED) {
            refreshCheckpointVersion(checkpoint, cpStore, expectedVersion + 1);
            applyStepToHistory(history, pendingIdx, resumedStep);
            return new ReconciliationOutcome.Reconciled(resumedStep, isIndeterminate);
        }

        if (appendResult == CheckpointAppendResult.ALREADY_PRESENT) {
            refreshCheckpointVersion(checkpoint, cpStore, expectedVersion);
            applyStepToHistory(history, pendingIdx, resumedStep);
            return new ReconciliationOutcome.Reconciled(resumedStep, isIndeterminate);
        }

        if (appendResult == CheckpointAppendResult.CONFLICT) {
            return new ReconciliationOutcome.Blocked(
                    "Durable checkpoint append conflict for attempt " + attempt.attemptId());
        }

        return new ReconciliationOutcome.Blocked(
                "Durable checkpoint append exceeded CAS retries (stale version) for session "
                        + checkpoint.getSessionId());
    }

    private void applyStepToHistory(List<ReasoningStep> history, OptionalInt pendingIdx, ReasoningStep resumedStep) {
        if (pendingIdx.isPresent()) {
            history.set(pendingIdx.getAsInt(), resumedStep);
        } else {
            history.add(resumedStep);
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

    /**
     * Cancels an abandoned checkpoint attempt if it exists and is still in ADMITTED status.
     */
    public boolean cancelAbandonedAttemptIfAdmitted(String workspaceId, AgentCheckpoint checkpoint) {
        if (workspaceId == null || workspaceId.isBlank() || checkpoint == null) {
            return false;
        }
        ToolAttemptStorePort attemptStore = resolveAttemptStore();
        if (attemptStore == null) {
            log.warnf("Cannot cancel abandoned attempt: store unavailable for session %s", checkpoint.getSessionId());
            return false;
        }

        if (checkpoint.isParallelBatch()
                || (checkpoint.getPendingToolName() != null
                        && checkpoint
                                .getPendingToolName()
                                .toLowerCase(Locale.ROOT)
                                .contains("parallel"))) {
            return cancelAbandonedBatchAttempts(attemptStore, workspaceId, checkpoint);
        }

        String toolName = checkpoint.getPendingToolName();
        String paramsJson = checkpoint.getPendingToolParametersJson();
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        List<ReasoningStep> loadedSteps =
                checkpointSerializer != null ? checkpointSerializer.deserializeSteps(checkpoint) : List.of();
        Map<String, Object> params = parseParams(paramsJson);
        String operationId =
                PendingToolOperationIdLookup.find(loadedSteps, toolName, params).orElse(null);
        if (operationId == null) {
            log.debugf(
                    "No operationId found for abandoned checkpoint session %s tool %s",
                    checkpoint.getSessionId(), toolName);
            return false;
        }
        return cancelIfAdmitted(attemptStore, workspaceId, checkpoint.getSessionId(), operationId);
    }

    private boolean cancelAbandonedBatchAttempts(
            ToolAttemptStorePort attemptStore, String workspaceId, AgentCheckpoint checkpoint) {
        String paramsJson = checkpoint.getPendingToolParametersJson();
        if (paramsJson == null || paramsJson.isBlank()) {
            return false;
        }
        try {
            List<ParallelToolCall> calls =
                    objectMapper.readValue(paramsJson, new TypeReference<List<ParallelToolCall>>() {});
            boolean anyCancelled = false;
            for (ParallelToolCall call : calls) {
                if (call.operationId() != null
                        && cancelIfAdmitted(attemptStore, workspaceId, checkpoint.getSessionId(), call.operationId())) {
                    anyCancelled = true;
                }
            }
            return anyCancelled;
        } catch (Exception e) {
            log.warnf(
                    "Failed to cancel abandoned batch attempts for session %s: %s",
                    checkpoint.getSessionId(), LogUtil.rootMessage(e));
            return false;
        }
    }

    private boolean cancelIfAdmitted(
            ToolAttemptStorePort attemptStore, String workspaceId, String sessionId, String operationId) {
        try {
            Optional<ToolAttemptDto> latestOpt = attemptStore.findLatestAttemptByOperation(workspaceId, operationId);
            if (latestOpt.isEmpty()) {
                return false;
            }
            ToolAttemptDto attempt = latestOpt.get();
            if (!Objects.equals(attempt.sessionId(), sessionId)
                    || !Objects.equals(attempt.workspaceId(), workspaceId)) {
                log.warnf(
                        "Abandoned attempt %s does not match session %s workspace %s",
                        attempt.attemptId(), sessionId, workspaceId);
                return false;
            }
            if (attempt.status() != ToolAttemptStatus.ADMITTED) {
                log.debugf(
                        "Abandoned attempt %s status is %s (not ADMITTED), skipping cancellation",
                        attempt.attemptId(), attempt.status());
                return false;
            }

            ToolAttemptTransitionResult result = attemptStore.transition(
                    workspaceId,
                    attempt.attemptId(),
                    ToolAttemptStatus.ADMITTED,
                    attempt.fencingToken(),
                    ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
                    ToolAttemptSettlementFields.empty(Instant.now()),
                    null,
                    List.of());
            if (result.status() == ToolAttemptTransitionResult.TransitionStatus.APPLIED) {
                log.infof(
                        "Cancelled abandoned attempt %s as CANCELLED_BEFORE_LAUNCH for session %s",
                        attempt.attemptId(), sessionId);
                return true;
            } else {
                log.warnf(
                        "Failed to cancel abandoned attempt %s: status=%s (%s)",
                        attempt.attemptId(), result.status(), result.message());
                return false;
            }
        } catch (Exception e) {
            log.errorf(
                    "Error transitioning abandoned attempt for operation %s in workspace %s: %s",
                    operationId, workspaceId, LogUtil.rootMessage(e));
            return false;
        }
    }

    private ToolAttemptStorePort resolveAttemptStore() {
        if (directAttemptStore != null) return directAttemptStore;
        if (toolAttemptStores == null || toolAttemptStores.isUnsatisfied() || toolAttemptStores.isAmbiguous()) {
            return null;
        }
        return toolAttemptStores.get();
    }

    private CheckpointStorePort resolveCheckpointStore() {
        if (directCheckpointStore != null) return directCheckpointStore;
        if (checkpointStores == null || checkpointStores.isUnsatisfied() || checkpointStores.isAmbiguous()) {
            return null;
        }
        return checkpointStores.get();
    }

    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(paramsJson, PARAMETER_MAP);
        } catch (Exception e) {
            log.warnf("Failed to parse params json: %s", LogUtil.rootMessage(e));
            return Map.of();
        }
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
}
