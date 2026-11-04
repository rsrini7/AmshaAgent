package com.amsha.persistence.sqlite;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptResolutionDto;
import com.amsha.persistence.api.ToolAttemptResolutionStatus;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.sqlite.jdbi.ToolAttemptJdbiDao;

import org.jdbi.v3.core.Handle;

/**
 * Handles resolution and retry admission transactions in SQLite.
 */
class SqliteToolAttemptResolutionHandler {

    boolean applyResolution(
            Handle handle,
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent) {
        ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
        Optional<ToolAttemptResolutionDto> resOpt = dao.findResolution(workspaceId, resolutionId);
        if (resOpt.isEmpty()) {
            return false;
        }
        ToolAttemptResolutionDto resolution = resOpt.get();
        if (resolution.resolutionStatus() != ToolAttemptResolutionStatus.PENDING || resolution.isRetryAuthorization()) {
            return false;
        }
        if (!resolution.workspaceId().equals(workspaceId)
                || !resolution.lockId().equals(lockId)) {
            return false;
        }

        // Audit-before-mutation invariant (§12.7): authorization audit event must be durably acknowledged
        if (resolution.authorizationOutboxEventId() == null) {
            return false;
        }
        Optional<ToolAttemptAuditOutboxDto> authOutboxOpt =
                dao.findOutboxEvent(workspaceId, resolution.authorizationOutboxEventId());
        if (authOutboxOpt.isEmpty()
                || authOutboxOpt.get().deliveredAt() == null
                || !resolution.resolutionId().equals(authOutboxOpt.get().resolutionId())
                || authOutboxOpt.get().eventKind() != ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED
                || !workspaceId.equals(authOutboxOpt.get().workspaceId())) {
            return false;
        }

        // Bound to active lock and origin attempt
        Optional<DurableActionLockDto> lockOpt = dao.findActiveLock(workspaceId, lockId);
        if (lockOpt.isEmpty()) {
            return false;
        }
        DurableActionLockDto lock = lockOpt.get();
        if (!lock.originAttemptId().equals(resolution.originAttemptId())
                || !lock.actionFingerprint().equals(resolution.actionFingerprint())
                || !Objects.equals(lock.targetFingerprint(), resolution.targetFingerprint())) {
            return false;
        }

        Optional<ToolAttemptDto> originOpt = dao.findAttempt(workspaceId, resolution.originAttemptId());
        if (originOpt.isEmpty()) {
            return false;
        }

        int updatedRes = dao.applyResolution(workspaceId, resolutionId, consumedAt, resolvedOutboxEvent.eventId());
        if (updatedRes != 1) {
            handle.rollback();
            return false;
        }

        int clearedLock = dao.clearLock(workspaceId, lockId, consumedAt, resolutionId);
        if (clearedLock != 1) {
            handle.rollback();
            return false;
        }

        dao.insertOutboxEvent(resolvedOutboxEvent);
        return true;
    }

    ToolAttemptAdmissionResult applyResolutionAndAdmitRetry(
            Handle handle,
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent,
            ToolAttemptAdmissionRequest retryRequest) {
        ToolAttemptJdbiDao dao = handle.attach(ToolAttemptJdbiDao.class);
        Optional<ToolAttemptResolutionDto> resOpt = dao.findResolution(workspaceId, resolutionId);
        if (resOpt.isEmpty()) {
            return ToolAttemptAdmissionResult.conflict("Resolution not found: " + resolutionId);
        }
        ToolAttemptResolutionDto resolution = resOpt.get();
        if (!resolution.workspaceId().equals(workspaceId)
                || !retryRequest.workspaceId().equals(workspaceId)) {
            return ToolAttemptAdmissionResult.conflict("Workspace mismatch for resolution: " + resolutionId);
        }

        if (resolution.resolutionStatus() != ToolAttemptResolutionStatus.PENDING) {
            if (resolution.resolutionStatus() == ToolAttemptResolutionStatus.APPLIED
                    && resolution.isRetryAuthorization()
                    && resolution.lockId().equals(lockId)
                    && retryRequest.retryOfAttemptId() != null
                    && retryRequest.retryOfAttemptId().equals(resolution.originAttemptId())
                    && retryRequest.actionFingerprint().equals(resolution.actionFingerprint())
                    && Objects.equals(retryRequest.targetFingerprint(), resolution.targetFingerprint())) {
                Optional<ToolAttemptDto> originOpt = dao.findAttempt(workspaceId, resolution.originAttemptId());
                Optional<ToolAttemptDto> latestOpt =
                        dao.findLatestAttemptByOperation(workspaceId, retryRequest.operationId());
                if (originOpt.isPresent() && latestOpt.isPresent()) {
                    ToolAttemptDto origin = originOpt.get();
                    ToolAttemptDto latest = latestOpt.get();
                    if (retryRequest.matchesAttemptIdentity(origin)
                            && origin.attemptId().equals(retryRequest.retryOfAttemptId())
                            && retryRequest.matchesAttemptIdentity(latest)
                            && latest.attemptNumber() == origin.attemptNumber() + 1) {
                        Optional<ToolAttemptAuditOutboxDto> outbox = latest.status() == ToolAttemptStatus.ADMITTED
                                ? dao.findOutboxEventByIdempotencyKey(
                                        workspaceId, workspaceId + ":" + latest.attemptId() + ":ATTEMPT_ADMITTED")
                                : Optional.empty();
                        return ToolAttemptAdmissionResult.identicalExisting(latest, outbox.orElse(null));
                    }
                }
            }
            return ToolAttemptAdmissionResult.conflict("Resolution is already applied or expired: " + resolutionId);
        }
        if (!resolution.isRetryAuthorization()) {
            return ToolAttemptAdmissionResult.conflict(
                    "Resolution is not an authorized ONE_SHOT_RETRY: " + resolutionId);
        }
        if (!resolution.lockId().equals(lockId)) {
            return ToolAttemptAdmissionResult.conflict("Lock ID does not match resolution lock: " + lockId);
        }

        // Audit-before-mutation invariant (§12.7): authorization audit event must be durably acknowledged
        if (resolution.authorizationOutboxEventId() == null) {
            return ToolAttemptAdmissionResult.conflict(
                    "Resolution missing authorization outbox event ID: " + resolutionId);
        }
        Optional<ToolAttemptAuditOutboxDto> authOutboxOpt =
                dao.findOutboxEvent(workspaceId, resolution.authorizationOutboxEventId());
        if (authOutboxOpt.isEmpty()
                || authOutboxOpt.get().deliveredAt() == null
                || !resolution.resolutionId().equals(authOutboxOpt.get().resolutionId())
                || authOutboxOpt.get().eventKind() != ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED
                || !workspaceId.equals(authOutboxOpt.get().workspaceId())) {
            return ToolAttemptAdmissionResult.conflict(
                    "Resolution authorization audit event has not been acknowledged: "
                            + resolution.authorizationOutboxEventId());
        }

        // Bound to active lock
        Optional<DurableActionLockDto> lockOpt = dao.findActiveLock(workspaceId, lockId);
        if (lockOpt.isEmpty()) {
            return ToolAttemptAdmissionResult.conflict("Active lock not found: " + lockId);
        }
        DurableActionLockDto lock = lockOpt.get();
        if (!lock.originAttemptId().equals(resolution.originAttemptId())
                || !lock.actionFingerprint().equals(resolution.actionFingerprint())
                || !Objects.equals(lock.targetFingerprint(), resolution.targetFingerprint())) {
            return ToolAttemptAdmissionResult.conflict(
                    "Lock identity does not match resolution origin or fingerprints: " + lockId);
        }

        // Bound to retry request
        if (retryRequest.retryOfAttemptId() == null
                || !retryRequest.retryOfAttemptId().equals(resolution.originAttemptId())) {
            return ToolAttemptAdmissionResult.conflict(
                    "Retry request retryOfAttemptId does not match resolution origin: "
                            + retryRequest.retryOfAttemptId());
        }
        if (!retryRequest.actionFingerprint().equals(resolution.actionFingerprint())) {
            return ToolAttemptAdmissionResult.conflict(
                    "Retry request action fingerprint does not match resolution: " + retryRequest.actionFingerprint());
        }
        if (!Objects.equals(retryRequest.targetFingerprint(), resolution.targetFingerprint())) {
            return ToolAttemptAdmissionResult.conflict(
                    "Retry request target fingerprint does not match resolution: " + retryRequest.targetFingerprint());
        }

        // Bound to preceding attempt
        Optional<ToolAttemptDto> precOpt = dao.findAttempt(workspaceId, retryRequest.retryOfAttemptId());
        if (precOpt.isEmpty()) {
            return ToolAttemptAdmissionResult.conflict(
                    "Preceding attempt not found: " + retryRequest.retryOfAttemptId());
        }
        ToolAttemptDto preceding = precOpt.get();
        if (!preceding.sessionId().equals(retryRequest.sessionId())
                || !preceding.operationId().equals(retryRequest.operationId())
                || !preceding.toolName().equals(retryRequest.toolName())
                || preceding.fingerprintVersion() != retryRequest.fingerprintVersion()
                || !preceding.actionFingerprint().equals(retryRequest.actionFingerprint())
                || !Objects.equals(preceding.targetFingerprint(), retryRequest.targetFingerprint())
                || preceding.idempotencyModeSnapshot() != retryRequest.idempotencyMode()) {
            return ToolAttemptAdmissionResult.conflict(
                    "Retry request identity does not match preceding attempt: " + retryRequest.retryOfAttemptId());
        }

        Optional<ToolAttemptDto> latestOpt = dao.findLatestAttemptByOperation(workspaceId, retryRequest.operationId());
        if (latestOpt.isEmpty() || !latestOpt.get().attemptId().equals(preceding.attemptId())) {
            return ToolAttemptAdmissionResult.conflict(
                    "Preceding attempt is not the latest attempt for operation: " + retryRequest.operationId());
        }

        if (!preceding.status().isEligibleForRetry(retryRequest.idempotencyMode(), true)) {
            return ToolAttemptAdmissionResult.conflict("Prior attempt with status " + preceding.status()
                    + " is not eligible for approved retry under " + retryRequest.idempotencyMode());
        }

        // Execute all mutations atomically; rollback on any failure
        int updatedRes = dao.applyResolution(workspaceId, resolutionId, consumedAt, resolvedOutboxEvent.eventId());
        if (updatedRes != 1) {
            handle.rollback();
            return ToolAttemptAdmissionResult.conflict("Failed to consume resolution: " + resolutionId);
        }

        int clearedLock = dao.clearLock(workspaceId, lockId, consumedAt, resolutionId);
        if (clearedLock != 1) {
            handle.rollback();
            return ToolAttemptAdmissionResult.conflict("Failed to clear lock: " + lockId);
        }

        dao.insertOutboxEvent(resolvedOutboxEvent);

        String attemptId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        int nextAttemptNumber = preceding.attemptNumber() + 1;

        if (retryRequest.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            dao.insertClaim(
                    workspaceId,
                    retryRequest.sessionId(),
                    retryRequest.toolName(),
                    retryRequest.fingerprintVersion(),
                    retryRequest.actionFingerprint(),
                    ActionClaimStatus.RESERVED.name(),
                    retryRequest.operationId(),
                    attemptId,
                    retryRequest.createdAt(),
                    retryRequest.createdAt());
        }

        ToolAttemptDto retryAttempt = new ToolAttemptDto(
                workspaceId,
                retryRequest.sessionId(),
                retryRequest.operationId(),
                attemptId,
                nextAttemptNumber,
                retryRequest.toolName(),
                retryRequest.actionFingerprint(),
                retryRequest.fingerprintVersion(),
                retryRequest.targetFingerprint(),
                retryRequest.idempotencyMode(),
                ToolAttemptStatus.ADMITTED,
                0L,
                retryRequest.executionKind(),
                null,
                null,
                null,
                null,
                retryRequest.createdAt(),
                null,
                null,
                null);
        dao.insertAttempt(retryAttempt);

        String eventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        ToolAttemptAuditOutboxDto outbox = ToolAttemptAuditOutboxDto.forAttempt(
                eventId,
                workspaceId,
                retryRequest.sessionId(),
                attemptId,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                retryRequest.admissionSafeMetadataJson(),
                retryRequest.createdAt());
        dao.insertOutboxEvent(outbox);

        return ToolAttemptAdmissionResult.created(retryAttempt, outbox);
    }
}
