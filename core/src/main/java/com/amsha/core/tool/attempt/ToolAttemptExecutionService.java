package com.amsha.core.tool.attempt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.agent.loop.ToolObservationSanitizer;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Coordinates admission, pre-launch audit acknowledgement, execution state, and settlement. */
@ApplicationScoped
public class ToolAttemptExecutionService {
    public static final String EXECUTION_KIND_BUILT_IN = "BUILT_IN";
    public static final String EXECUTION_KIND_SANDBOX = "SANDBOX";
    public static final String EXECUTION_KIND_DYNAMIC = "DYNAMIC";
    public static final String EXECUTION_KIND_HOST = "HOST";
    public static final String TIMEOUT_ERROR_CODE = "TOOL_ATTEMPT_TIMED_OUT";
    public static final String MISSING_OPERATION_ID_OUTPUT = "Tool execution requires an orchestration operation ID.";

    private static final String BLOCKED_OUTPUT = "Tool execution is blocked by durable safety checks.";
    private static final String INDETERMINATE_OUTPUT = "Tool outcome is uncertain; automatic replay is blocked.";
    private static final String UNSAFE_OUTPUT_OUTPUT = "Tool output could not be safely recorded.";
    private static final String REASON_INDETERMINATE = "INDETERMINATE_OUTCOME";
    private static final String REASON_RETRY_APPROVAL = "RETRY_APPROVAL_REQUIRED";

    private final Instance<ToolAttemptStorePort> stores;
    private final ActionFingerprintGenerator fingerprints;
    private final ToolTargetResolver targetResolver;
    private final ToolAttemptAuditOutboxRelay outboxRelay;
    private final ToolObservationSanitizer observationSanitizer;
    private final ObjectMapper objectMapper;

    @Inject
    public ToolAttemptExecutionService(
            Instance<ToolAttemptStorePort> stores,
            ActionFingerprintGenerator fingerprints,
            ToolTargetResolver targetResolver,
            ToolAttemptAuditOutboxRelay outboxRelay,
            ToolObservationSanitizer observationSanitizer,
            ObjectMapper objectMapper) {
        this.stores = stores;
        this.fingerprints = fingerprints;
        this.targetResolver = targetResolver;
        this.outboxRelay = outboxRelay;
        this.observationSanitizer = observationSanitizer;
        this.objectMapper = objectMapper;
    }

    /** Admits the operation and synchronously acknowledges its admission audit event. */
    public Admission admit(
            String sessionId,
            String operationId,
            String toolName,
            Map<String, Object> parameters,
            IdempotencyMode mode,
            String executionKind) {
        String workspaceId = WorkspaceContext.activeWorkspaceId().orElse(null);
        if (workspaceId == null || workspaceId.isBlank()) {
            return Admission.blocked(ToolResponse.failure(503, BLOCKED_OUTPUT));
        }
        if (sessionId == null || sessionId.isBlank() || operationId == null || operationId.isBlank()) {
            return Admission.blocked(ToolResponse.failure(400, BLOCKED_OUTPUT));
        }
        if (toolName == null || toolName.isBlank() || parameters == null || mode == null) {
            return Admission.blocked(ToolResponse.failure(400, BLOCKED_OUTPUT));
        }
        if (stores.isUnsatisfied() || stores.isAmbiguous()) {
            return Admission.blocked(ToolResponse.failure(503, BLOCKED_OUTPUT));
        }

        try {
            ToolAttemptStorePort store = stores.get();
            String actionFingerprint = fingerprints.generate(workspaceId, sessionId, toolName, parameters);
            ToolTargetResolution target = targetResolver.resolve(workspaceId, sessionId, toolName, parameters);
            ToolAttemptIdempotencyMode attemptMode = ToolAttemptAdmissionRequestFactory.attemptMode(mode);
            ToolAttemptAdmissionRequest baseRequest = ToolAttemptAdmissionRequestFactory.request(
                    objectMapper,
                    workspaceId,
                    sessionId,
                    operationId,
                    null,
                    toolName,
                    actionFingerprint,
                    target,
                    attemptMode,
                    executionKind);

            if (!store.findApplicableLocks(workspaceId, sessionId, actionFingerprint, target.targetFingerprint())
                    .isEmpty()) {
                return Admission.blocked(ToolResponse.failure(409, BLOCKED_OUTPUT));
            }

            Optional<ToolAttemptDto> prior = store.findLatestAttemptByOperation(workspaceId, operationId);
            String retryOfAttemptId = null;
            if (prior.isPresent()) {
                ToolAttemptDto previous = prior.get();
                if (!baseRequest.matchesAttemptIdentity(previous)) {
                    return Admission.blocked(ToolResponse.failure(409, BLOCKED_OUTPUT));
                }
                if (previous.status().isTerminal()) {
                    if (previous.status().isEligibleForRetry(attemptMode, false)) {
                        retryOfAttemptId = previous.attemptId();
                    } else {
                        return Admission.replay(replay(previous));
                    }
                } else if (previous.status() != ToolAttemptStatus.ADMITTED) {
                    return Admission.blocked(ToolResponse.failure(409, INDETERMINATE_OUTPUT));
                }
            }

            ToolAttemptAdmissionRequest admissionRequest = retryOfAttemptId == null
                    ? baseRequest
                    : ToolAttemptAdmissionRequestFactory.request(
                            objectMapper,
                            workspaceId,
                            sessionId,
                            operationId,
                            retryOfAttemptId,
                            toolName,
                            actionFingerprint,
                            target,
                            attemptMode,
                            executionKind);
            ToolAttemptAdmissionResult result = store.admit(admissionRequest);
            if (result.status() == ToolAttemptAdmissionResult.AdmissionStatus.AT_MOST_ONCE_EXISTING) {
                return Admission.replay(replay(result.attempt()));
            }
            if (result.status() == ToolAttemptAdmissionResult.AdmissionStatus.CONFLICT) {
                return Admission.blocked(ToolResponse.failure(409, BLOCKED_OUTPUT));
            }

            ToolAttemptDto attempt = result.attempt();
            if (attempt.status().isTerminal()) {
                return Admission.replay(replay(attempt));
            }
            if (attempt.status() != ToolAttemptStatus.ADMITTED || result.admissionOutboxEvent() == null) {
                return Admission.blocked(ToolResponse.failure(409, INDETERMINATE_OUTPUT));
            }

            outboxRelay.deliverRequired(
                    workspaceId, result.admissionOutboxEvent().eventId());
            return Admission.ready(
                    new AttemptHandle(store, attempt, target, admissionRequest.admissionSafeMetadataJson()));
        } catch (RuntimeException _) {
            return Admission.blocked(ToolResponse.failure(503, BLOCKED_OUTPUT));
        }
    }

    /** Persists and acknowledges launch intent, then fences the RUNNING transition. */
    public void requestLaunch(AttemptHandle handle, String executionHandle) {
        ToolAttemptDto admitted = handle.attempt();
        if (admitted.status() != ToolAttemptStatus.ADMITTED) {
            throw new IllegalStateException("Tool attempt is not ready for launch.");
        }
        Instant launchTime = Instant.now();
        ToolAttemptAuditOutboxDto launchEvent =
                event(handle, ToolAttemptAuditEventKind.LAUNCH_REQUESTED, handle.safeMetadataJson(), launchTime);
        ToolAttemptDto launchRequested = applyTransition(
                handle,
                ToolAttemptStatus.ADMITTED,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptSettlementFields.forLaunch(launchTime, executionHandle),
                null,
                List.of(launchEvent));
        outboxRelay.deliverRequired(launchRequested.workspaceId(), launchEvent.eventId());
    }

    public void markExecutionStarting(AttemptHandle handle) {
        if (handle.attempt().status() != ToolAttemptStatus.LAUNCH_REQUESTED) {
            throw new IllegalStateException("Tool attempt has no acknowledged launch intent.");
        }
        handle.markExecutionMayHaveStarted();
    }

    public void markLaunchFailedBeforeStart(AttemptHandle handle) {
        handle.markLaunchDidNotStart();
    }

    public void markRunning(AttemptHandle handle, String processStartIdentity) {
        ToolAttemptDto launchRequested = handle.attempt();
        if (launchRequested.status() != ToolAttemptStatus.LAUNCH_REQUESTED) {
            throw new IllegalStateException("Tool attempt has no acknowledged launch intent.");
        }
        handle.markExecutionMayHaveStarted();
        applyTransition(
                handle,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                ToolAttemptStatus.RUNNING,
                ToolAttemptSettlementFields.forRunning(
                        Instant.now(), launchRequested.executionHandle(), processStartIdentity),
                null,
                List.of());
    }

    /** Built-in and dynamic JVM tools are RUNNING immediately before invocation. */
    public void launchAndStart(AttemptHandle handle) {
        requestLaunch(handle, null);
        markExecutionStarting(handle);
        markRunning(handle, null);
    }

    /** Attaches store-allocated identity to the context passed into the executor. */
    public ToolExecutionContext attach(ToolExecutionContext context, AttemptHandle handle) {
        ToolAttemptDto attempt = handle.attempt();
        return context.withAttempt(
                attempt.workspaceId(), attempt.attemptId(), attempt.attemptNumber(), attempt.fencingToken());
    }

    /** Applies the standard observation transformation without recording an attempt. */
    public ToolResponse sanitizeWithoutAttempt(String toolName, ToolResponse response) {
        return withSanitizedOutput(response, observationSanitizer.sanitize(toolName, response.output()));
    }

    /** Sanitizes a tool result, persists its terminal state and any required durable lock. */
    public ToolResponse settle(AttemptHandle handle, String toolName, ToolResponse rawResponse) {
        if (handle.attempt().status().isTerminal()) {
            return replay(handle.attempt());
        }
        ToolResponse response = rawResponse == null ? ToolResponse.infraFailure(INDETERMINATE_OUTPUT) : rawResponse;
        ToolResponse sanitized;
        try {
            sanitized = withSanitizedOutput(response, observationSanitizer.sanitize(toolName, response.output()));
        } catch (RuntimeException _) {
            sanitized = ToolResponse.infraFailure(UNSAFE_OUTPUT_OUTPUT);
        }

        ToolAttemptDto current = handle.attempt();
        ToolAttemptStatus targetStatus;
        DurableActionLockDto lock = null;
        Instant now = Instant.now();
        if (current.status() == ToolAttemptStatus.ADMITTED
                || (current.status() == ToolAttemptStatus.LAUNCH_REQUESTED && !handle.executionMayHaveStarted())) {
            targetStatus = ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH;
        } else if (current.status() != ToolAttemptStatus.RUNNING || sanitized.isInfraFailure()) {
            targetStatus = ToolAttemptStatus.INDETERMINATE;
            lock = lock(handle, REASON_INDETERMINATE, now);
        } else if (isTimedOut(sanitized)) {
            targetStatus = ToolAttemptStatus.TIMED_OUT;
            if (current.idempotencyModeSnapshot() == ToolAttemptIdempotencyMode.NON_IDEMPOTENT) {
                lock = lock(handle, REASON_RETRY_APPROVAL, now);
            }
        } else if (sanitized instanceof ToolResponse.ToolSuccess) {
            targetStatus = ToolAttemptStatus.SETTLED_SUCCESS;
        } else {
            targetStatus = ToolAttemptStatus.SETTLED_ERROR;
            if (current.idempotencyModeSnapshot() == ToolAttemptIdempotencyMode.NON_IDEMPOTENT) {
                lock = lock(handle, REASON_RETRY_APPROVAL, now);
            }
        }

        ToolAttemptSettlementFields settlement = settlementFields(current, targetStatus, sanitized, now);
        List<ToolAttemptAuditOutboxDto> outboxEvents = new ArrayList<>();
        outboxEvents.add(event(handle, ToolAttemptAuditEventKind.ATTEMPT_SETTLED, handle.safeMetadataJson(), now));
        if (lock != null) {
            outboxEvents.add(event(handle, ToolAttemptAuditEventKind.ACTION_LOCKED, handle.safeMetadataJson(), now));
        }
        applyTransition(handle, current.status(), targetStatus, settlement, lock, outboxEvents);
        handle.completeSettlement();
        return sanitized;
    }

    private ToolAttemptDto applyTransition(
            AttemptHandle handle,
            ToolAttemptStatus expected,
            ToolAttemptStatus target,
            ToolAttemptSettlementFields fields,
            DurableActionLockDto lock,
            List<ToolAttemptAuditOutboxDto> outboxEvents) {
        ToolAttemptDto current = handle.attempt();
        ToolAttemptTransitionResult result = handle.store()
                .transition(
                        current.workspaceId(),
                        current.attemptId(),
                        expected,
                        current.fencingToken(),
                        target,
                        fields,
                        lock,
                        outboxEvents);
        if (result.status() != ToolAttemptTransitionResult.TransitionStatus.APPLIED) {
            throw new IllegalStateException("Tool attempt state transition was stale or conflicted.");
        }
        handle.update(result.updatedAttempt());
        return result.updatedAttempt();
    }

    private static ToolAttemptSettlementFields settlementFields(
            ToolAttemptDto attempt, ToolAttemptStatus status, ToolResponse response, Instant timestamp) {
        return switch (status) {
            case SETTLED_SUCCESS ->
                ToolAttemptSettlementFields.forSuccess(timestamp, attempt.executionHandle(), response.output());
            case SETTLED_ERROR ->
                ToolAttemptSettlementFields.forError(
                        timestamp, attempt.executionHandle(), response.exitCode(), response.output());
            case TIMED_OUT ->
                ToolAttemptSettlementFields.forError(
                        timestamp, attempt.executionHandle(), response.exitCode(), response.output());
            case CANCELLED_BEFORE_LAUNCH ->
                new ToolAttemptSettlementFields(timestamp, null, null, response.exitCode(), response.output());
            case INDETERMINATE ->
                new ToolAttemptSettlementFields(
                        timestamp,
                        attempt.executionHandle(),
                        attempt.processStartIdentity(),
                        null,
                        response.output() == null ? INDETERMINATE_OUTPUT : response.output());
            default -> throw new IllegalArgumentException("Unsupported tool attempt terminal status.");
        };
    }

    private static boolean isTimedOut(ToolResponse response) {
        return response instanceof ToolResponse.ToolFailure failure && TIMEOUT_ERROR_CODE.equals(failure.errorCode());
    }

    private static ToolResponse withSanitizedOutput(ToolResponse response, String observation) {
        return switch (response) {
            case ToolResponse.ToolSuccess _ -> ToolResponse.success(observation);
            case ToolResponse.ToolFailure failure ->
                new ToolResponse.ToolFailure(
                        failure.exitCode(),
                        observation,
                        failure.isInfraFailure(),
                        failure.errorCode(),
                        failure.traceId(),
                        failure.rootCauseClass());
            case ToolResponse.ToolPending pending -> new ToolResponse.ToolPending(observation, pending.approvalToken());
        };
    }

    private static ToolResponse replay(ToolAttemptDto attempt) {
        String observation = attempt.sanitizedObservation() == null ? BLOCKED_OUTPUT : attempt.sanitizedObservation();
        return switch (attempt.status()) {
            case SETTLED_SUCCESS -> ToolResponse.success(observation);
            case SETTLED_ERROR ->
                ToolResponse.failure(
                        attempt.exitCode() == null ? 1 : attempt.exitCode(),
                        observation,
                        "TOOL_ATTEMPT_ERROR",
                        null,
                        null);
            case TIMED_OUT -> ToolResponse.failure(1, observation, "TOOL_ATTEMPT_TIMED_OUT", null, null);
            default -> ToolResponse.failure(409, INDETERMINATE_OUTPUT);
        };
    }

    private static DurableActionLockDto lock(AttemptHandle handle, String reason, Instant timestamp) {
        return ToolAttemptLockPolicy.createLock(handle.attempt(), handle.target(), reason, timestamp);
    }

    private ToolAttemptAuditOutboxDto event(
            AttemptHandle handle, ToolAttemptAuditEventKind kind, String metadata, Instant timestamp) {
        ToolAttemptDto attempt = handle.attempt();
        return ToolAttemptAuditOutboxDto.forAttempt(
                UUID.randomUUID().toString(),
                attempt.workspaceId(),
                attempt.sessionId(),
                attempt.attemptId(),
                kind,
                metadata,
                timestamp);
    }

    /** Admission outcome either carries an executable attempt or a safe immediate response. */
    public static final class Admission {
        private final AttemptHandle handle;
        private final ToolResponse immediateResponse;

        private Admission(AttemptHandle handle, ToolResponse immediateResponse) {
            this.handle = handle;
            this.immediateResponse = immediateResponse;
        }

        static Admission ready(AttemptHandle handle) {
            return new Admission(Objects.requireNonNull(handle), null);
        }

        static Admission blocked(ToolResponse response) {
            return new Admission(null, Objects.requireNonNull(response));
        }

        static Admission replay(ToolResponse response) {
            return new Admission(null, Objects.requireNonNull(response));
        }

        public boolean shouldExecute() {
            return handle != null;
        }

        public AttemptHandle handle() {
            return handle;
        }

        public ToolResponse immediateResponse() {
            return immediateResponse;
        }
    }

    /** Invocation-local mutable handle updated only by fenced store transitions. */
    public static final class AttemptHandle extends ToolAttemptHandleState {
        private AttemptHandle(
                ToolAttemptStorePort store,
                ToolAttemptDto attempt,
                ToolTargetResolution target,
                String safeMetadataJson) {
            super(store, attempt, target, safeMetadataJson);
        }
    }
}
