package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.DurableActionLockDto;
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
import com.amsha.persistence.sqlite.jdbi.ToolAttemptJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * SQLite implementation of {@link ToolAttemptStorePort} using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
public class SqliteToolAttemptStoreAdapter implements ToolAttemptStorePort {

    private final Jdbi jdbi;
    private final SqliteToolAttemptAdmissionHandler admissionHandler;
    private final SqliteToolAttemptResolutionHandler resolutionHandler;

    @Inject
    public SqliteToolAttemptStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
        this.admissionHandler = new SqliteToolAttemptAdmissionHandler();
        this.resolutionHandler = new SqliteToolAttemptResolutionHandler();
    }

    protected SqliteToolAttemptStoreAdapter() {
        this(null);
    }

    @Override
    public ToolAttemptAdmissionResult admit(ToolAttemptAdmissionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return jdbi.inTransaction(handle -> {
            ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
            if (request.retryOfAttemptId() == null) {
                return admissionHandler.admitInitial(dao, request);
            } else {
                return admissionHandler.admitRetryUnapproved(dao, request);
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

        return jdbi.inTransaction(handle -> {
            ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
            Optional<ToolAttemptDto> opt = dao.findAttempt(workspaceId, attemptId);
            if (opt.isEmpty()) {
                return ToolAttemptTransitionResult.conflict("Attempt not found: " + attemptId);
            }
            ToolAttemptDto cur = opt.get();
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

            int updated = dao.updateAttemptStatus(
                    workspaceId,
                    attemptId,
                    expectedStatus.name(),
                    expectedFencingToken,
                    targetStatus.name(),
                    nextFencingToken,
                    handleStr,
                    identityStr,
                    exitCode,
                    obs,
                    launchRequestedAt,
                    runningAt,
                    settledAt);
            if (updated != 1) {
                return ToolAttemptTransitionResult.stale("CAS failed on attempt update");
            }

            updateClaimLifecycleOnTransition(dao, cur, targetStatus, ts);

            if (optionalLock != null) {
                dao.insertLock(optionalLock);
            }
            if (outboxEvents != null) {
                for (ToolAttemptAuditOutboxDto event : outboxEvents) {
                    dao.insertOutboxEvent(event);
                }
            }

            ToolAttemptDto finalAttempt = new ToolAttemptDto(
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
            return ToolAttemptTransitionResult.applied(finalAttempt);
        });
    }

    private void updateClaimLifecycleOnTransition(
            ToolAttemptJdbiDao dao, ToolAttemptDto cur, ToolAttemptStatus targetStatus, Instant ts) {
        if (cur.idempotencyModeSnapshot() != ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            return;
        }
        if (targetStatus == ToolAttemptStatus.SETTLED_SUCCESS
                || targetStatus == ToolAttemptStatus.SETTLED_ERROR
                || targetStatus == ToolAttemptStatus.TIMED_OUT) {
            dao.updateClaimState(
                    cur.workspaceId(),
                    cur.sessionId(),
                    cur.toolName(),
                    cur.fingerprintVersion(),
                    cur.actionFingerprint(),
                    ActionClaimStatus.CONSUMED.name(),
                    ts);
        } else if (targetStatus == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH) {
            dao.deleteClaim(
                    cur.workspaceId(),
                    cur.sessionId(),
                    cur.toolName(),
                    cur.fingerprintVersion(),
                    cur.actionFingerprint());
        }
    }

    @Override
    public boolean applyResolution(
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(resolutionId, "resolutionId must not be null");
        Objects.requireNonNull(lockId, "lockId must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        Objects.requireNonNull(resolvedOutboxEvent, "resolvedOutboxEvent must not be null");

        return jdbi.inTransaction(handle -> resolutionHandler.applyResolution(
                handle, workspaceId, resolutionId, lockId, consumedAt, resolvedOutboxEvent));
    }

    @Override
    public ToolAttemptAdmissionResult applyResolutionAndAdmitRetry(
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent,
            ToolAttemptAdmissionRequest retryRequest) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(resolutionId, "resolutionId must not be null");
        Objects.requireNonNull(lockId, "lockId must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        Objects.requireNonNull(resolvedOutboxEvent, "resolvedOutboxEvent must not be null");
        Objects.requireNonNull(retryRequest, "retryRequest must not be null");

        return jdbi.inTransaction(handle -> resolutionHandler.applyResolutionAndAdmitRetry(
                handle, workspaceId, resolutionId, lockId, consumedAt, resolvedOutboxEvent, retryRequest));
    }

    @Override
    public Optional<ToolAttemptDto> findAttempt(String workspaceId, String attemptId) {
        return jdbi.withHandle(handle -> handle.attach(ToolAttemptJdbiDao.class).findAttempt(workspaceId, attemptId));
    }

    @Override
    public Optional<ToolAttemptDto> findLatestAttemptByOperation(String workspaceId, String operationId) {
        return jdbi.withHandle(handle ->
                handle.attach(ToolAttemptJdbiDao.class).findLatestAttemptByOperation(workspaceId, operationId));
    }

    @Override
    public List<ToolAttemptDto> findAttemptsBySession(String workspaceId, String sessionId) {
        return jdbi.withHandle(
                handle -> handle.attach(ToolAttemptJdbiDao.class).findAttemptsBySession(workspaceId, sessionId));
    }

    @Override
    public Optional<DurableActionLockDto> findActiveLock(String workspaceId, String lockId) {
        return jdbi.withHandle(handle -> handle.attach(ToolAttemptJdbiDao.class).findActiveLock(workspaceId, lockId));
    }

    @Override
    public List<DurableActionLockDto> findApplicableLocks(
            String workspaceId, String sessionId, String actionFingerprint, String targetFingerprint) {
        return jdbi.withHandle(handle -> handle.attach(ToolAttemptJdbiDao.class)
                .findApplicableLocks(workspaceId, sessionId, actionFingerprint, targetFingerprint));
    }

    @Override
    public Optional<ToolAttemptDto> findAtMostOnceResult(
            String workspaceId, String sessionId, String toolName, int fingerprintVersion, String actionFingerprint) {
        return jdbi.withHandle(handle -> {
            ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
            Optional<ToolActionClaimDto> claimOpt =
                    dao.findClaim(workspaceId, sessionId, toolName, fingerprintVersion, actionFingerprint);
            if (claimOpt.isEmpty() || claimOpt.get().status() != ActionClaimStatus.CONSUMED) {
                return Optional.empty();
            }
            return dao.findAttempt(workspaceId, claimOpt.get().attemptId())
                    .filter(a -> a.status().isReplayableAtMostOnceOutcome());
        });
    }

    @Override
    public Optional<ToolActionClaimDto> findClaim(
            String workspaceId, String sessionId, String toolName, int fingerprintVersion, String actionFingerprint) {
        return jdbi.withHandle(handle -> handle.attach(ToolAttemptJdbiDao.class)
                .findClaim(workspaceId, sessionId, toolName, fingerprintVersion, actionFingerprint));
    }

    @Override
    public Set<String> listWorkspaceIds() {
        return jdbi.withHandle(handle -> handle.attach(ToolAttemptJdbiDao.class).listWorkspaceIds());
    }

    @Override
    public void prepareResolution(
            ToolAttemptResolutionDto resolution, ToolAttemptAuditOutboxDto authorizationOutboxEvent) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        Objects.requireNonNull(authorizationOutboxEvent, "authorizationOutboxEvent must not be null");
        resolution.validateAuthorizationOutbox(authorizationOutboxEvent);
        jdbi.useTransaction(handle -> {
            ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
            dao.insertResolution(resolution);
            dao.insertOutboxEvent(authorizationOutboxEvent);
        });
    }

    @Override
    public Optional<ToolAttemptResolutionDto> findResolution(String workspaceId, String resolutionId) {
        return jdbi.withHandle(
                handle -> handle.attach(ToolAttemptJdbiDao.class).findResolution(workspaceId, resolutionId));
    }

    @Override
    public Optional<ToolAttemptResolutionDto> findResolutionByLockId(String workspaceId, String lockId) {
        return jdbi.withHandle(
                handle -> handle.attach(ToolAttemptJdbiDao.class).findResolutionByLockId(workspaceId, lockId));
    }

    @Override
    public List<ToolAttemptAuditOutboxDto> leaseOutboxEvents(
            String workspaceId, String leaseId, Instant leaseUntil, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        return jdbi.inTransaction(handle -> {
            ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
            List<ToolAttemptAuditOutboxDto> pending =
                    dao.findDeliverableOutboxEvents(workspaceId, Instant.now(), limit);
            if (pending.isEmpty()) {
                return List.of();
            }
            List<String> eventIds =
                    pending.stream().map(ToolAttemptAuditOutboxDto::eventId).toList();
            dao.leaseOutboxEvents(workspaceId, leaseId, leaseUntil, eventIds);
            return pending.stream()
                    .map(o -> new ToolAttemptAuditOutboxDto(
                            o.eventId(),
                            o.idempotencyKey(),
                            o.workspaceId(),
                            o.sessionId(),
                            o.attemptId(),
                            o.resolutionId(),
                            o.eventKind(),
                            o.safeMetadataJson(),
                            o.createdAt(),
                            o.deliveryAttempts(),
                            o.nextAttemptAt(),
                            leaseId,
                            leaseUntil,
                            null,
                            null))
                    .toList();
        });
    }

    @Override
    public boolean acknowledgeOutboxEvent(
            String workspaceId, String eventId, String leaseId, String deliveredAuditEventId, Instant deliveredAt) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(deliveredAuditEventId, "deliveredAuditEventId must not be null");
        Objects.requireNonNull(deliveredAt, "deliveredAt must not be null");
        return jdbi.inTransaction(handle -> handle.attach(ToolAttemptJdbiDao.class)
                        .acknowledgeOutboxEvent(workspaceId, eventId, leaseId, deliveredAuditEventId, deliveredAt)
                == 1);
    }

    @Override
    public boolean rescheduleOutboxEvent(
            String workspaceId, String eventId, String leaseId, Instant nextAttemptAt, int deliveryAttempts) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        return jdbi.inTransaction(handle -> handle.attach(ToolAttemptJdbiDao.class)
                        .rescheduleOutboxEvent(workspaceId, eventId, leaseId, nextAttemptAt, deliveryAttempts)
                == 1);
    }

    @Override
    public Optional<ToolAttemptAuditOutboxDto> findOutboxEvent(String workspaceId, String eventId) {
        return jdbi.withHandle(handle -> handle.attach(ToolAttemptJdbiDao.class).findOutboxEvent(workspaceId, eventId));
    }

    @Override
    public long pruneTerminalBefore(String workspaceId, Instant cutoff) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        return jdbi.inTransaction(handle -> {
            ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
            long total = 0;
            total += dao.pruneDeliveredOutboxRows(workspaceId, cutoff);
            total += dao.pruneTerminalResolutions(workspaceId, cutoff);
            total += dao.pruneClearedLocks(workspaceId, cutoff);
            total += dao.pruneTerminalAttempts(workspaceId, cutoff);
            return total;
        });
    }

    @Override
    public long deleteClaimsBySession(String workspaceId, String sessionId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return jdbi.inTransaction(
                handle -> handle.attach(ToolAttemptJdbiDao.class).deleteClaimsBySession(workspaceId, sessionId));
    }
}
