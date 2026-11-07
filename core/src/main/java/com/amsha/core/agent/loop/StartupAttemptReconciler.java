package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxRecoveryEvidence;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tool.attempt.ToolAttemptLockPolicy;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Startup reconciler for tool attempt settlement and execution ownership verification.
 * Implements Proposal Phase C item 4 (§10) and contracts in §§5.1, 6, 7, 8, 12.1, 12.4, and 12.8.
 */
@ApplicationScoped
public class StartupAttemptReconciler {

    private static final Logger log = Logger.getLogger(StartupAttemptReconciler.class);

    private final ToolAttemptStorePort attemptStore;
    private final IdentityStorePort identityStore;
    private final SandboxExecutionPort sandboxPort;
    private final CheckpointStorePort checkpointStore;
    private final ToolObservationSanitizer observationSanitizer;
    private final StartupCheckpointOperationMatcher operationMatcher;
    private final boolean singleInstance;

    @Inject
    public StartupAttemptReconciler(
            Instance<ToolAttemptStorePort> attemptStoreInstance,
            Instance<IdentityStorePort> identityStoreInstance,
            Instance<SandboxExecutionPort> sandboxPortInstance,
            Instance<CheckpointStorePort> checkpointStoreInstance,
            Instance<ToolObservationSanitizer> observationSanitizerInstance,
            StartupCheckpointOperationMatcher operationMatcher,
            @ConfigProperty(name = "amsha.recovery.single-instance", defaultValue = "false") boolean singleInstance) {
        this.attemptStore = attemptStoreInstance.isResolvable() ? attemptStoreInstance.get() : null;
        this.identityStore = identityStoreInstance.isResolvable() ? identityStoreInstance.get() : null;
        this.sandboxPort = sandboxPortInstance.isResolvable() ? sandboxPortInstance.get() : null;
        this.checkpointStore = checkpointStoreInstance.isResolvable() ? checkpointStoreInstance.get() : null;
        this.observationSanitizer =
                observationSanitizerInstance.isResolvable() ? observationSanitizerInstance.get() : null;
        this.operationMatcher = operationMatcher;
        this.singleInstance = singleInstance;
    }

    StartupAttemptReconciler(
            ToolAttemptStorePort attemptStore,
            IdentityStorePort identityStore,
            SandboxExecutionPort sandboxPort,
            boolean singleInstance) {
        this(attemptStore, identityStore, sandboxPort, null, null, singleInstance);
    }

    StartupAttemptReconciler(
            ToolAttemptStorePort attemptStore,
            IdentityStorePort identityStore,
            SandboxExecutionPort sandboxPort,
            CheckpointStorePort checkpointStore,
            ToolObservationSanitizer observationSanitizer,
            boolean singleInstance) {
        this(attemptStore, identityStore, sandboxPort, checkpointStore, observationSanitizer, null, singleInstance);
    }

    StartupAttemptReconciler(
            ToolAttemptStorePort attemptStore,
            IdentityStorePort identityStore,
            SandboxExecutionPort sandboxPort,
            CheckpointStorePort checkpointStore,
            ToolObservationSanitizer observationSanitizer,
            StartupCheckpointOperationMatcher operationMatcher,
            boolean singleInstance) {
        this.attemptStore = attemptStore;
        this.identityStore = identityStore;
        this.sandboxPort = sandboxPort;
        this.checkpointStore = checkpointStore;
        this.observationSanitizer = observationSanitizer;
        this.operationMatcher = operationMatcher;
        this.singleInstance = singleInstance;
    }

    public Set<String> enumerateWorkspaces() {
        if (attemptStore == null) {
            throw new IllegalStateException("ToolAttemptStorePort is required for workspace enumeration.");
        }
        if (identityStore == null) {
            throw new IllegalStateException("IdentityStorePort is required for workspace enumeration.");
        }
        Set<String> workspaces = new LinkedHashSet<>();
        try {
            Set<String> fromAttempts = attemptStore.listWorkspaceIds();
            if (fromAttempts != null) {
                for (String ws : fromAttempts) {
                    if (ws != null && !ws.isBlank()) {
                        workspaces.add(ws);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enumerate workspaces from attempt store", e);
        }
        try {
            List<IdentityWorkspaceDto> fromIdentity = identityStore.listWorkspaces();
            if (fromIdentity != null) {
                for (IdentityWorkspaceDto wsDto : fromIdentity) {
                    if (wsDto != null
                            && wsDto.workspaceId() != null
                            && !wsDto.workspaceId().isBlank()) {
                        workspaces.add(wsDto.workspaceId());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enumerate workspaces from identity store", e);
        }
        return Set.copyOf(workspaces);
    }

    public void reconcileAllWorkspaces() {
        if (attemptStore == null) {
            throw new IllegalStateException("ToolAttemptStorePort is required for startup reconciliation.");
        }
        if (identityStore == null) {
            throw new IllegalStateException("IdentityStorePort is required for startup reconciliation.");
        }
        if (checkpointStore == null) {
            throw new IllegalStateException("CheckpointStorePort is required for startup reconciliation.");
        }
        if (observationSanitizer == null) {
            throw new IllegalStateException(
                    "ToolObservationSanitizer is required for startup reconciliation to ensure output sanitization.");
        }
        Set<String> workspaces = enumerateWorkspaces();
        log.infof("Startup attempt reconciliation starting across %d workspace(s).", workspaces.size());
        for (String workspaceId : workspaces) {
            try {
                WorkspaceContext.callWithActiveWorkspace(workspaceId, () -> {
                    reconcileWorkspace(workspaceId);
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException("Failed to reconcile workspace " + workspaceId, e);
            }
        }
        log.infof("Startup attempt reconciliation completed across %d workspace(s).", workspaces.size());
    }

    public void reconcileWorkspace(String workspaceId) {
        List<ToolAttemptDto> activeAttempts = attemptStore.findActiveAttempts(workspaceId);
        if (activeAttempts == null || activeAttempts.isEmpty()) {
            return;
        }
        log.infof("Reconciling %d active attempt(s) in workspace %s", activeAttempts.size(), workspaceId);
        for (ToolAttemptDto attempt : activeAttempts) {
            reconcileAttempt(workspaceId, attempt);
        }
    }

    private void reconcileAttempt(String workspaceId, ToolAttemptDto attempt) {
        if (attempt.status().isTerminal()) {
            return;
        }
        Instant now = Instant.now();
        if (attempt.status() == ToolAttemptStatus.ADMITTED) {
            reconcileAdmittedAttempt(workspaceId, attempt, now);
            return;
        }
        if (attempt.status() == ToolAttemptStatus.LAUNCH_REQUESTED || attempt.status() == ToolAttemptStatus.RUNNING) {
            reconcileActiveAttempt(workspaceId, attempt, now);
        }
    }

    private void reconcileAdmittedAttempt(String workspaceId, ToolAttemptDto attempt, Instant now) {
        if (!singleInstance) {
            throw new IllegalStateException("Multi-instance reconciliation not supported: attempt "
                    + attempt.attemptId() + " in ADMITTED status cannot be verified without local ownership");
        }
        if (checkpointStore == null) {
            throw new IllegalStateException(
                    "CheckpointStorePort is unavailable to check abandonment for ADMITTED attempt "
                            + attempt.attemptId());
        }
        Optional<CheckpointDto> cpOpt;
        try {
            cpOpt = checkpointStore.findLatestNonArchivedBySession(attempt.sessionId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query checkpoint status for session " + attempt.sessionId(), e);
        }

        if (cpOpt.isEmpty()) {
            ToolAttemptSettlementFields settlement = ToolAttemptSettlementFields.empty(now);
            List<ToolAttemptAuditOutboxDto> outbox = buildOutboxEvents(attempt, null, now);
            applyTransitionWithRetry(
                    workspaceId, attempt, ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH, settlement, null, outbox);
            return;
        }

        CheckpointDto cp = cpOpt.get();
        String statusStr = cp.status();
        if (statusStr == null || statusStr.isBlank()) {
            throw new IllegalStateException(
                    "Cannot verify abandonment: checkpoint status is missing for session " + attempt.sessionId());
        }
        CheckpointStatus cpStatus;
        try {
            cpStatus = CheckpointStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Cannot verify abandonment: unrecognized checkpoint status '" + statusStr + "' for session "
                            + attempt.sessionId(),
                    e);
        }
        if (operationMatcher == null || !operationMatcher.matches(cp, attempt.operationId(), attempt.toolName())) {
            log.infof(
                    "Checkpoint for session %s does not match attempt %s (operation %s, tool %s); leaving untouched",
                    attempt.sessionId(), attempt.attemptId(), attempt.operationId(), attempt.toolName());
            return;
        }
        if (cpStatus.isTerminal()) {
            ToolAttemptSettlementFields settlement = ToolAttemptSettlementFields.empty(now);
            List<ToolAttemptAuditOutboxDto> outbox = buildOutboxEvents(attempt, null, now);
            applyTransitionWithRetry(
                    workspaceId, attempt, ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH, settlement, null, outbox);
        } else {
            log.infof(
                    "Attempt %s remains ADMITTED for active checkpoint session %s (status=%s)",
                    attempt.attemptId(), attempt.sessionId(), cpStatus);
        }
    }

    private void reconcileActiveAttempt(String workspaceId, ToolAttemptDto attempt, Instant now) {
        if (!singleInstance) {
            handleIndeterminateTransition(
                    workspaceId,
                    attempt,
                    "Executor ownership cannot be verified in multi-instance mode; state indeterminate",
                    now);
            return;
        }
        if ("IN_PROCESS".equalsIgnoreCase(attempt.executionKind()) || attempt.executionHandle() == null) {
            handleIndeterminateTransition(workspaceId, attempt, "In-process execution died on JVM restart", now);
            return;
        }
        if (sandboxPort == null) {
            handleIndeterminateTransition(workspaceId, attempt, "Sandbox port unavailable on restart", now);
            return;
        }

        SandboxRecoveryEvidence evidence = sandboxPort.inspectAttempt(
                workspaceId, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity());

        if (evidence instanceof SandboxRecoveryEvidence.RunningVerified) {
            boolean terminated = sandboxPort.terminateRunningAttempt(
                    workspaceId, attempt.attemptId(), attempt.executionHandle(), attempt.processStartIdentity());
            String reason = terminated
                    ? "Running executor terminated across restart; state indeterminate"
                    : "Running executor stop unconfirmed across restart; state indeterminate";
            handleIndeterminateTransition(workspaceId, attempt, reason, now);
        } else if (evidence instanceof SandboxRecoveryEvidence.ExitedVerified exited) {
            handleExitedEvidence(workspaceId, attempt, exited, now);
        } else if (evidence instanceof SandboxRecoveryEvidence.Missing missing) {
            handleIndeterminateTransition(
                    workspaceId, attempt, "Executor missing on restart: " + missing.reason(), now);
        } else if (evidence instanceof SandboxRecoveryEvidence.OwnershipMismatch mismatch) {
            handleIndeterminateTransition(
                    workspaceId, attempt, "Ownership mismatch on restart: " + mismatch.reason(), now);
        } else if (evidence instanceof SandboxRecoveryEvidence.Inconclusive inconclusive) {
            handleIndeterminateTransition(
                    workspaceId, attempt, "Inconclusive executor state: " + inconclusive.reason(), now);
        }
    }

    private void handleExitedEvidence(
            String workspaceId, ToolAttemptDto attempt, SandboxRecoveryEvidence.ExitedVerified exited, Instant now) {
        if (observationSanitizer == null) {
            throw new IllegalStateException(
                    "ToolObservationSanitizer is unavailable; cannot sanitize output for attempt "
                            + attempt.attemptId());
        }
        String rawOutput = exited.output();
        String sanitizedOutput = observationSanitizer.sanitize(attempt.toolName(), rawOutput == null ? "" : rawOutput);
        if (exited.exitCode() == 0) {
            ToolAttemptSettlementFields settlement =
                    ToolAttemptSettlementFields.forSuccess(now, attempt.executionHandle(), sanitizedOutput);
            List<ToolAttemptAuditOutboxDto> outbox = buildOutboxEvents(attempt, null, now);
            applyTransitionWithRetry(workspaceId, attempt, ToolAttemptStatus.SETTLED_SUCCESS, settlement, null, outbox);
        } else {
            ToolAttemptSettlementFields settlement = ToolAttemptSettlementFields.forError(
                    now, attempt.executionHandle(), exited.exitCode(), sanitizedOutput);
            DurableActionLockDto lock = null;
            if (attempt.idempotencyModeSnapshot() == ToolAttemptIdempotencyMode.NON_IDEMPOTENT) {
                lock = createLock(attempt, "NON_IDEMPOTENT execution exited with error " + exited.exitCode(), now);
            }
            List<ToolAttemptAuditOutboxDto> outbox = buildOutboxEvents(attempt, lock, now);
            applyTransitionWithRetry(workspaceId, attempt, ToolAttemptStatus.SETTLED_ERROR, settlement, lock, outbox);
        }
    }

    private void handleIndeterminateTransition(String workspaceId, ToolAttemptDto attempt, String reason, Instant now) {
        DurableActionLockDto lock = createLock(attempt, reason, now);
        ToolAttemptSettlementFields settlement = new ToolAttemptSettlementFields(
                now, attempt.executionHandle(), attempt.processStartIdentity(), null, reason);
        List<ToolAttemptAuditOutboxDto> outbox = buildOutboxEvents(attempt, lock, now);
        applyTransitionWithRetry(workspaceId, attempt, ToolAttemptStatus.INDETERMINATE, settlement, lock, outbox);
    }

    private DurableActionLockDto createLock(ToolAttemptDto attempt, String reason, Instant timestamp) {
        return ToolAttemptLockPolicy.createLock(attempt, reason, timestamp);
    }

    private List<ToolAttemptAuditOutboxDto> buildOutboxEvents(
            ToolAttemptDto attempt, DurableActionLockDto lock, Instant timestamp) {
        List<ToolAttemptAuditOutboxDto> events = new ArrayList<>();
        events.add(ToolAttemptAuditOutboxDto.forAttempt(
                UUID.randomUUID().toString(),
                attempt.workspaceId(),
                attempt.sessionId(),
                attempt.attemptId(),
                ToolAttemptAuditEventKind.ATTEMPT_SETTLED,
                "{}",
                timestamp));
        if (lock != null) {
            events.add(ToolAttemptAuditOutboxDto.forAttempt(
                    UUID.randomUUID().toString(),
                    attempt.workspaceId(),
                    attempt.sessionId(),
                    attempt.attemptId(),
                    ToolAttemptAuditEventKind.ACTION_LOCKED,
                    "{}",
                    timestamp));
        }
        return List.copyOf(events);
    }

    private void applyTransitionWithRetry(
            String workspaceId,
            ToolAttemptDto initialAttempt,
            ToolAttemptStatus targetStatus,
            ToolAttemptSettlementFields settlementFields,
            DurableActionLockDto lock,
            List<ToolAttemptAuditOutboxDto> outboxEvents) {
        ToolAttemptDto current = initialAttempt;
        for (int i = 0; i < 2; i++) {
            ToolAttemptTransitionResult result = attemptStore.transition(
                    workspaceId,
                    current.attemptId(),
                    current.status(),
                    current.fencingToken(),
                    targetStatus,
                    settlementFields,
                    lock,
                    outboxEvents);
            if (result.status() == ToolAttemptTransitionResult.TransitionStatus.APPLIED) {
                return;
            }
            if (result.status() == ToolAttemptTransitionResult.TransitionStatus.STALE) {
                Optional<ToolAttemptDto> reloaded = attemptStore.findAttempt(workspaceId, current.attemptId());
                if (reloaded.isEmpty() || reloaded.get().status().isTerminal()) {
                    log.infof("Attempt %s already settled in workspace %s", current.attemptId(), workspaceId);
                    return;
                }
                current = reloaded.get();
            } else {
                throw new IllegalStateException("Transition rejected for attempt " + current.attemptId() + ": status="
                        + result.status() + " message=" + result.message());
            }
        }
        throw new IllegalStateException(
                "Failed to transition attempt " + current.attemptId() + " to " + targetStatus + " after retry");
    }
}
