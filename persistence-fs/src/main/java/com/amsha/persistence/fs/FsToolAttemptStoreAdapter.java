package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.ActionLockScope;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptResolutionDto;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of {@link ToolAttemptStorePort} per Proposal §12.4.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsToolAttemptStoreAdapter implements ToolAttemptStorePort {

    private final FsToolAttemptHelper helper;
    private final FsToolAttemptAdmissionHandler admissionHandler;
    private final FsToolAttemptResolutionHandler resolutionHandler;
    private final FsToolAttemptOutboxHandler outboxHandler;

    @Inject
    public FsToolAttemptStoreAdapter(FsToolAttemptHelper helper) {
        this.helper = helper;
        this.admissionHandler = new FsToolAttemptAdmissionHandler(helper);
        this.resolutionHandler = new FsToolAttemptResolutionHandler(helper);
        this.outboxHandler = new FsToolAttemptOutboxHandler(helper);
    }

    protected FsToolAttemptStoreAdapter() {
        this(null);
    }

    @Override
    public ToolAttemptAdmissionResult admit(ToolAttemptAdmissionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return helper.withWorkspaceLock(request.workspaceId(), () -> {
            helper.repairOrRecoverClaims(request.workspaceId());
            if (request.retryOfAttemptId() == null) {
                return admissionHandler.admitInitialUnderLock(request);
            } else {
                return admissionHandler.admitRetryUnapprovedUnderLock(request);
            }
        });
    }

    @Override
    public ToolAttemptTransitionResult transition(
            String workspaceId,
            String attemptId,
            ToolAttemptStatus expectedStatus,
            long expectedFencingToken,
            ToolAttemptStatus targetStatus,
            ToolAttemptSettlementFields settlementFields,
            DurableActionLockDto optionalLock,
            List<ToolAttemptAuditOutboxDto> outboxEvents) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(expectedStatus, "expectedStatus must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");

        if (!expectedStatus.canTransitionTo(targetStatus)) {
            return ToolAttemptTransitionResult.conflict(
                    "Illegal transition from " + expectedStatus + " to " + targetStatus);
        }

        return helper.withWorkspaceLock(workspaceId, () -> {
            Optional<FsToolAttemptEnvelope> opt = helper.loadAttemptEnvelope(workspaceId, attemptId);
            if (opt.isEmpty()) {
                return ToolAttemptTransitionResult.conflict("Attempt not found: " + attemptId);
            }
            FsToolAttemptEnvelope env = opt.get();
            ToolAttemptDto cur = env.attempt();
            if (cur.status().isTerminal()) {
                return ToolAttemptTransitionResult.conflict("Attempt is in terminal state: " + cur.status());
            }
            if (cur.status() != expectedStatus || cur.fencingToken() != expectedFencingToken) {
                return ToolAttemptTransitionResult.stale("Attempt state mismatch: expected (" + expectedStatus + ", "
                        + expectedFencingToken + ") but was (" + cur.status() + ", " + cur.fencingToken() + ")");
            }

            Instant ts = settlementFields != null && settlementFields.eventTimestamp() != null
                    ? settlementFields.eventTimestamp()
                    : Instant.now();
            Instant launchRequestedAt =
                    targetStatus == ToolAttemptStatus.LAUNCH_REQUESTED ? ts : cur.launchRequestedAt();
            Instant runningAt = targetStatus == ToolAttemptStatus.RUNNING ? ts : cur.runningAt();
            Instant settledAt = targetStatus.isTerminal() ? ts : null;
            long nextFencingToken = expectedFencingToken + 1;

            String handleStr = settlementFields != null && settlementFields.executionHandle() != null
                    ? settlementFields.executionHandle()
                    : cur.executionHandle();
            String identityStr = settlementFields != null && settlementFields.processStartIdentity() != null
                    ? settlementFields.processStartIdentity()
                    : cur.processStartIdentity();
            Integer exitCode = settlementFields != null && settlementFields.exitCode() != null
                    ? settlementFields.exitCode()
                    : cur.exitCode();
            String obs = settlementFields != null && settlementFields.sanitizedObservation() != null
                    ? settlementFields.sanitizedObservation()
                    : cur.sanitizedObservation();

            ToolAttemptDto updated = new ToolAttemptDto(
                    cur.workspaceId(),
                    cur.sessionId(),
                    cur.operationId(),
                    cur.attemptId(),
                    cur.attemptNumber(),
                    cur.toolName(),
                    cur.actionFingerprint(),
                    cur.fingerprintVersion(),
                    cur.targetFingerprint(),
                    cur.idempotencyModeSnapshot(),
                    targetStatus,
                    nextFencingToken,
                    cur.executionKind(),
                    handleStr,
                    identityStr,
                    exitCode,
                    obs,
                    cur.createdAt(),
                    launchRequestedAt,
                    runningAt,
                    settledAt);

            List<DurableActionLockDto> newLocks = new ArrayList<>(env.locks());
            if (optionalLock != null) newLocks.add(optionalLock);

            List<ToolAttemptAuditOutboxDto> newOutbox = new ArrayList<>(env.outboxEvents());
            if (outboxEvents != null) newOutbox.addAll(outboxEvents);

            FsToolAttemptEnvelope newEnv =
                    new FsToolAttemptEnvelope(env.version(), updated, newLocks, env.resolutions(), newOutbox);
            helper.saveAttemptEnvelope(newEnv);

            if (cur.idempotencyModeSnapshot() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
                FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(workspaceId);
                if (targetStatus == ToolAttemptStatus.SETTLED_SUCCESS
                        || targetStatus == ToolAttemptStatus.SETTLED_ERROR
                        || targetStatus == ToolAttemptStatus.TIMED_OUT) {
                    List<ToolActionClaimDto> updatedClaims = claimsEnv.claims().stream()
                            .map(c -> isMatchingClaim(c, cur)
                                    ? new ToolActionClaimDto(
                                            c.workspaceId(),
                                            c.sessionId(),
                                            c.toolName(),
                                            c.fingerprintVersion(),
                                            c.actionFingerprint(),
                                            ActionClaimStatus.CONSUMED,
                                            c.operationId(),
                                            c.attemptId(),
                                            c.createdAt(),
                                            ts)
                                    : c)
                            .toList();
                    helper.saveClaimsEnvelope(
                            new FsWorkspaceClaimsEnvelope(claimsEnv.version(), workspaceId, updatedClaims));
                } else if (targetStatus == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH) {
                    List<ToolActionClaimDto> updatedClaims = claimsEnv.claims().stream()
                            .filter(c -> !isMatchingClaim(c, cur))
                            .toList();
                    helper.saveClaimsEnvelope(
                            new FsWorkspaceClaimsEnvelope(claimsEnv.version(), workspaceId, updatedClaims));
                }
            }

            return ToolAttemptTransitionResult.applied(updated);
        });
    }

    @Override
    public boolean applyResolution(
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent) {
        return resolutionHandler.applyResolution(workspaceId, resolutionId, lockId, consumedAt, resolvedOutboxEvent);
    }

    @Override
    public ToolAttemptAdmissionResult applyResolutionAndAdmitRetry(
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent,
            ToolAttemptAdmissionRequest retryRequest) {
        return resolutionHandler.applyResolutionAndAdmitRetry(
                workspaceId, resolutionId, lockId, consumedAt, resolvedOutboxEvent, retryRequest);
    }

    @Override
    public Optional<ToolAttemptDto> findAttempt(String workspaceId, String attemptId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAttemptEnvelope(workspaceId, attemptId).map(FsToolAttemptEnvelope::attempt));
    }

    @Override
    public Optional<ToolAttemptDto> findLatestAttemptByOperation(String workspaceId, String operationId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .map(FsToolAttemptEnvelope::attempt)
                        .filter(a -> a.operationId().equals(operationId))
                        .max(Comparator.comparingInt(ToolAttemptDto::attemptNumber)));
    }

    @Override
    public List<ToolAttemptDto> findAttemptsBySession(String workspaceId, String sessionId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .map(FsToolAttemptEnvelope::attempt)
                        .filter(a -> a.sessionId().equals(sessionId))
                        .sorted(Comparator.comparingInt(ToolAttemptDto::attemptNumber))
                        .toList());
    }

    @Override
    public List<ToolAttemptDto> findActiveAttempts(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .map(FsToolAttemptEnvelope::attempt)
                        .filter(a -> a.status() == ToolAttemptStatus.ADMITTED
                                || a.status() == ToolAttemptStatus.LAUNCH_REQUESTED
                                || a.status() == ToolAttemptStatus.RUNNING)
                        .sorted(Comparator.comparing(ToolAttemptDto::createdAt)
                                .thenComparingInt(ToolAttemptDto::attemptNumber)
                                .thenComparing(ToolAttemptDto::attemptId))
                        .toList());
    }

    @Override
    public Optional<DurableActionLockDto> findActiveLock(String workspaceId, String lockId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .flatMap(e -> e.locks().stream())
                        .filter(l -> l.lockId().equals(lockId) && l.status() == DurableActionLockStatus.ACTIVE)
                        .findFirst());
    }

    @Override
    public List<DurableActionLockDto> findApplicableLocks(
            String workspaceId, String sessionId, String actionFingerprint, String targetFingerprint) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .flatMap(e -> e.locks().stream())
                        .filter(l -> l.status() == DurableActionLockStatus.ACTIVE)
                        .filter(l -> (l.scope() == ActionLockScope.EXACT_REQUEST
                                        && l.actionFingerprint().equals(actionFingerprint))
                                || (l.scope() == ActionLockScope.TARGET_RESOURCE
                                        && targetFingerprint != null
                                        && targetFingerprint.equals(l.targetFingerprint()))
                                || (l.scope() == ActionLockScope.SESSION_MUTATIONS
                                        && l.sessionId().equals(sessionId)))
                        .sorted(Comparator.comparing(DurableActionLockDto::createdAt))
                        .toList());
    }

    @Override
    public Optional<ToolAttemptDto> findAtMostOnceResult(
            String workspaceId, String sessionId, String toolName, int fingerprintVersion, String actionFingerprint) {
        return helper.withWorkspaceLock(workspaceId, () -> {
            FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(workspaceId);
            Optional<ToolActionClaimDto> claimOpt = FsToolAttemptAdmissionHandler.findClaimInEnvelope(
                    claimsEnv, sessionId, toolName, fingerprintVersion, actionFingerprint);
            if (claimOpt.isEmpty() || claimOpt.get().status() != ActionClaimStatus.CONSUMED) {
                return Optional.empty();
            }
            return helper.loadAttemptEnvelope(workspaceId, claimOpt.get().attemptId())
                    .map(FsToolAttemptEnvelope::attempt)
                    .filter(a -> a.status().isReplayableAtMostOnceOutcome());
        });
    }

    @Override
    public Optional<ToolActionClaimDto> findClaim(
            String workspaceId, String sessionId, String toolName, int fingerprintVersion, String actionFingerprint) {
        return helper.withWorkspaceLock(workspaceId, () -> {
            FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(workspaceId);
            return FsToolAttemptAdmissionHandler.findClaimInEnvelope(
                    claimsEnv, sessionId, toolName, fingerprintVersion, actionFingerprint);
        });
    }

    @Override
    public Set<String> listWorkspaceIds() {
        return helper.listWorkspaceIds();
    }

    @Override
    public void prepareResolution(
            ToolAttemptResolutionDto resolution, ToolAttemptAuditOutboxDto authorizationOutboxEvent) {
        resolutionHandler.prepareResolution(resolution, authorizationOutboxEvent);
    }

    @Override
    public Optional<ToolAttemptResolutionDto> findResolution(String workspaceId, String resolutionId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .flatMap(e -> e.resolutions().stream())
                        .filter(r -> r.resolutionId().equals(resolutionId))
                        .findFirst());
    }

    @Override
    public Optional<ToolAttemptResolutionDto> findResolutionByLockId(String workspaceId, String lockId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .flatMap(e -> e.resolutions().stream())
                        .filter(r -> r.lockId().equals(lockId))
                        .max(Comparator.comparing(ToolAttemptResolutionDto::createdAt)));
    }

    @Override
    public List<ToolAttemptAuditOutboxDto> leaseOutboxEvents(
            String workspaceId, String leaseId, Instant leaseUntil, int limit) {
        return outboxHandler.leaseOutboxEvents(workspaceId, leaseId, leaseUntil, limit);
    }

    @Override
    public boolean acknowledgeOutboxEvent(
            String workspaceId, String eventId, String leaseId, String deliveredAuditEventId, Instant deliveredAt) {
        return outboxHandler.acknowledgeOutboxEvent(workspaceId, eventId, leaseId, deliveredAuditEventId, deliveredAt);
    }

    @Override
    public boolean rescheduleOutboxEvent(
            String workspaceId, String eventId, String leaseId, Instant nextAttemptAt, int deliveryAttempts) {
        return outboxHandler.rescheduleOutboxEvent(workspaceId, eventId, leaseId, nextAttemptAt, deliveryAttempts);
    }

    @Override
    public Optional<ToolAttemptAuditOutboxDto> findOutboxEvent(String workspaceId, String eventId) {
        return outboxHandler.findOutboxEvent(workspaceId, eventId);
    }

    @Override
    public long pruneTerminalBefore(String workspaceId, Instant cutoff) {
        return outboxHandler.pruneTerminalBefore(workspaceId, cutoff);
    }

    @Override
    public long deleteClaimsBySession(String workspaceId, String sessionId) {
        return outboxHandler.deleteClaimsBySession(workspaceId, sessionId);
    }

    private static boolean isMatchingClaim(ToolActionClaimDto c, ToolAttemptDto att) {
        return c.workspaceId().equals(att.workspaceId())
                && c.sessionId().equals(att.sessionId())
                && c.toolName().equals(att.toolName())
                && c.fingerprintVersion() == att.fingerprintVersion()
                && c.actionFingerprint().equals(att.actionFingerprint());
    }
}
