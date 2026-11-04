package com.amsha.persistence.fs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptResolutionDto;
import com.amsha.persistence.api.ToolAttemptResolutionStatus;
import com.amsha.persistence.api.ToolAttemptStatus;

/**
 * Resolution and approved retry handler for {@link FsToolAttemptStoreAdapter} per Proposal §12.4.
 */
class FsToolAttemptResolutionHandler {

    private final FsToolAttemptHelper helper;

    FsToolAttemptResolutionHandler(FsToolAttemptHelper helper) {
        this.helper = helper;
    }

    void prepareResolution(ToolAttemptResolutionDto resolution, ToolAttemptAuditOutboxDto authorizationOutboxEvent) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        Objects.requireNonNull(authorizationOutboxEvent, "authorizationOutboxEvent must not be null");
        resolution.validateAuthorizationOutbox(authorizationOutboxEvent);
        helper.withWorkspaceLock(resolution.workspaceId(), () -> {
            FsToolAttemptEnvelope env = helper.loadAttemptEnvelope(
                            resolution.workspaceId(), resolution.originAttemptId())
                    .orElseThrow(() ->
                            new IllegalArgumentException("Origin attempt not found: " + resolution.originAttemptId()));
            List<ToolAttemptResolutionDto> newRes = new ArrayList<>(env.resolutions());
            newRes.add(resolution);
            List<ToolAttemptAuditOutboxDto> newOutbox = new ArrayList<>(env.outboxEvents());
            newOutbox.add(authorizationOutboxEvent);
            helper.saveAttemptEnvelope(
                    new FsToolAttemptEnvelope(env.version(), env.attempt(), env.locks(), newRes, newOutbox));
            return null;
        });
    }

    boolean applyResolution(
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

        return helper.withWorkspaceLock(workspaceId, () -> {
            for (FsToolAttemptEnvelope env : helper.loadAllEnvelopes(workspaceId)) {
                Optional<ToolAttemptResolutionDto> resOpt = env.resolutions().stream()
                        .filter(r -> r.resolutionId().equals(resolutionId))
                        .findFirst();
                if (resOpt.isPresent()) {
                    ToolAttemptResolutionDto res = resOpt.get();
                    if (res.isRetryAuthorization()) return false;
                    if (res.resolutionStatus() != ToolAttemptResolutionStatus.PENDING) return false;
                    if (!res.workspaceId().equals(workspaceId) || !res.lockId().equals(lockId)) return false;

                    // Audit-before-mutation (§12.7): authorization audit event must be durably acknowledged and match
                    // resolution
                    if (res.authorizationOutboxEventId() == null) return false;
                    Optional<ToolAttemptAuditOutboxDto> authOutboxOpt = env.outboxEvents().stream()
                            .filter(o -> o.eventId().equals(res.authorizationOutboxEventId()))
                            .findFirst();
                    if (authOutboxOpt.isEmpty()
                            || authOutboxOpt.get().deliveredAt() == null
                            || !res.resolutionId().equals(authOutboxOpt.get().resolutionId())
                            || authOutboxOpt.get().eventKind() != ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED
                            || !workspaceId.equals(authOutboxOpt.get().workspaceId())) {
                        return false;
                    }

                    Optional<DurableActionLockDto> lockOpt = env.locks().stream()
                            .filter(l -> l.lockId().equals(lockId) && l.status() == DurableActionLockStatus.ACTIVE)
                            .findFirst();
                    if (lockOpt.isEmpty()) return false;
                    DurableActionLockDto lock = lockOpt.get();
                    if (!lock.originAttemptId().equals(res.originAttemptId())
                            || !lock.actionFingerprint().equals(res.actionFingerprint())
                            || !Objects.equals(lock.targetFingerprint(), res.targetFingerprint())) {
                        return false;
                    }

                    completeOriginResolution(env, resolutionId, lockId, consumedAt, resolvedOutboxEvent);
                    return true;
                }
            }
            return false;
        });
    }

    ToolAttemptAdmissionResult applyResolutionAndAdmitRetry(
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

        return helper.withWorkspaceLock(workspaceId, () -> {
            FsToolAttemptEnvelope originEnv = findOriginEnvelopeForResolution(workspaceId, resolutionId);
            if (originEnv == null) {
                return ToolAttemptAdmissionResult.conflict("Resolution not found: " + resolutionId);
            }
            ToolAttemptResolutionDto res = findResolution(originEnv, resolutionId);
            ToolAttemptDto preceding = originEnv.attempt();

            // 1. Replay check: if resolution is already APPLIED (repeat call after successful mutation)
            if (res.resolutionStatus() == ToolAttemptResolutionStatus.APPLIED) {
                return handleAppliedReplay(workspaceId, resolutionId, res, preceding, retryRequest, lockId);
            }

            if (res.resolutionStatus() != ToolAttemptResolutionStatus.PENDING) {
                return ToolAttemptAdmissionResult.conflict("Resolution is not PENDING: " + res.resolutionStatus());
            }

            String validationError = validateRetryPreconditions(originEnv, res, workspaceId, lockId, retryRequest);
            if (validationError != null) {
                return ToolAttemptAdmissionResult.conflict(validationError);
            }

            // Audit-before-mutation (§12.7): authorization audit event must be durably acknowledged and match
            // resolution
            if (res.authorizationOutboxEventId() == null) {
                return ToolAttemptAdmissionResult.conflict(
                        "Resolution missing authorization outbox event ID: " + resolutionId);
            }
            Optional<ToolAttemptAuditOutboxDto> authOutboxOpt = originEnv.outboxEvents().stream()
                    .filter(o -> o.eventId().equals(res.authorizationOutboxEventId()))
                    .findFirst();
            if (authOutboxOpt.isEmpty()
                    || authOutboxOpt.get().deliveredAt() == null
                    || !resolutionId.equals(authOutboxOpt.get().resolutionId())
                    || authOutboxOpt.get().eventKind() != ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED
                    || !workspaceId.equals(authOutboxOpt.get().workspaceId())) {
                return ToolAttemptAdmissionResult.conflict(
                        "Resolution authorization audit event has not been acknowledged or does not match resolution: "
                                + res.authorizationOutboxEventId());
            }

            if (!preceding.status().isEligibleForRetry(retryRequest.idempotencyMode(), true)) {
                return ToolAttemptAdmissionResult.conflict("Prior attempt not eligible for retry with resolution");
            }

            // 2. Crash recovery check: check if retry attempt envelope was already written before crash
            List<FsToolAttemptEnvelope> envelopes = helper.loadAllEnvelopes(workspaceId);
            Optional<FsToolAttemptEnvelope> recoveredEnvOpt = envelopes.stream()
                    .filter(e -> e.attempt().operationId().equals(retryRequest.operationId())
                            && e.attempt().attemptNumber() == preceding.attemptNumber() + 1
                            && e.attempt().actionFingerprint().equals(res.actionFingerprint())
                            && Objects.equals(e.attempt().targetFingerprint(), res.targetFingerprint())
                            && e.attempt().idempotencyModeSnapshot() == retryRequest.idempotencyMode())
                    .findFirst();

            ToolAttemptDto retryAttempt;
            ToolAttemptAuditOutboxDto outbox;
            boolean isRecovery = recoveredEnvOpt.isPresent();

            if (isRecovery) {
                FsToolAttemptEnvelope recovered = recoveredEnvOpt.get();
                retryAttempt = recovered.attempt();
                outbox = recovered.outboxEvents().stream()
                        .filter(o -> o.idempotencyKey()
                                .equals(workspaceId + ":" + retryAttempt.attemptId() + ":ATTEMPT_ADMITTED"))
                        .findFirst()
                        .orElse(null);
            } else {
                String attemptId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
                retryAttempt = createRetryAttempt(workspaceId, attemptId, preceding.attemptNumber() + 1, retryRequest);
                String eventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
                outbox = ToolAttemptAuditOutboxDto.forAttempt(
                        eventId,
                        workspaceId,
                        retryRequest.sessionId(),
                        attemptId,
                        ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                        retryRequest.admissionSafeMetadataJson(),
                        retryRequest.createdAt());

                if (retryRequest.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
                    FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(workspaceId);
                    List<ToolActionClaimDto> updatedClaims = new ArrayList<>(claimsEnv.claims());
                    updatedClaims.add(new ToolActionClaimDto(
                            workspaceId,
                            retryRequest.sessionId(),
                            retryRequest.toolName(),
                            retryRequest.fingerprintVersion(),
                            retryRequest.actionFingerprint(),
                            ActionClaimStatus.RESERVED,
                            retryRequest.operationId(),
                            attemptId,
                            retryRequest.createdAt(),
                            retryRequest.createdAt()));
                    helper.saveClaimsEnvelope(
                            new FsWorkspaceClaimsEnvelope(claimsEnv.version(), workspaceId, updatedClaims));
                }

                helper.saveAttemptEnvelope(FsToolAttemptEnvelope.of(retryAttempt, outbox));
            }

            // Complete origin envelope update (marks resolution APPLIED, clears lock, writes resolved outbox)
            completeOriginResolution(originEnv, resolutionId, lockId, consumedAt, resolvedOutboxEvent);

            return isRecovery
                    ? ToolAttemptAdmissionResult.identicalExisting(retryAttempt, outbox)
                    : ToolAttemptAdmissionResult.created(retryAttempt, outbox);
        });
    }

    private ToolAttemptAdmissionResult handleAppliedReplay(
            String workspaceId,
            String resolutionId,
            ToolAttemptResolutionDto res,
            ToolAttemptDto preceding,
            ToolAttemptAdmissionRequest retryRequest,
            String lockId) {
        if (!res.isRetryAuthorization()
                || !res.workspaceId().equals(workspaceId)
                || !retryRequest.workspaceId().equals(workspaceId)
                || !res.lockId().equals(lockId)
                || !preceding.attemptId().equals(res.originAttemptId())
                || !res.originAttemptId().equals(retryRequest.retryOfAttemptId())
                || !res.actionFingerprint().equals(retryRequest.actionFingerprint())
                || !Objects.equals(res.targetFingerprint(), retryRequest.targetFingerprint())
                || !retryRequest.matchesAttemptIdentity(preceding)) {
            return ToolAttemptAdmissionResult.conflict(
                    "Retry request does not match applied resolution: " + resolutionId);
        }
        List<FsToolAttemptEnvelope> envelopes = helper.loadAllEnvelopes(workspaceId);
        Optional<ToolAttemptDto> existingRetryOpt = envelopes.stream()
                .map(FsToolAttemptEnvelope::attempt)
                .filter(a -> a.operationId().equals(retryRequest.operationId())
                        && a.attemptNumber() == preceding.attemptNumber() + 1
                        && retryRequest.matchesAttemptIdentity(a)
                        && a.actionFingerprint().equals(res.actionFingerprint())
                        && Objects.equals(a.targetFingerprint(), res.targetFingerprint()))
                .findFirst();
        if (existingRetryOpt.isPresent()) {
            ToolAttemptDto existing = existingRetryOpt.get();
            Optional<ToolAttemptAuditOutboxDto> outbox = existing.status() == ToolAttemptStatus.ADMITTED
                    ? envelopes.stream()
                            .flatMap(e -> e.outboxEvents().stream())
                            .filter(o -> o.idempotencyKey()
                                    .equals(workspaceId + ":" + existing.attemptId() + ":ATTEMPT_ADMITTED"))
                            .findFirst()
                    : Optional.empty();
            return ToolAttemptAdmissionResult.identicalExisting(existing, outbox.orElse(null));
        }
        return ToolAttemptAdmissionResult.conflict(
                "Resolution is already applied but retry attempt was not found: " + resolutionId);
    }

    private void completeOriginResolution(
            FsToolAttemptEnvelope originEnv,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent) {
        List<ToolAttemptResolutionDto> newResList = originEnv.resolutions().stream()
                .map(r -> r.resolutionId().equals(resolutionId)
                        ? new ToolAttemptResolutionDto(
                                r.resolutionId(),
                                r.lockId(),
                                r.workspaceId(),
                                r.actorId(),
                                r.resolutionKind(),
                                ToolAttemptResolutionStatus.APPLIED,
                                r.originAttemptId(),
                                r.actionFingerprint(),
                                r.targetFingerprint(),
                                r.evidenceReferenceOrDigest(),
                                r.authorizationOutboxEventId(),
                                resolvedOutboxEvent.eventId(),
                                r.createdAt(),
                                consumedAt)
                        : r)
                .toList();

        List<DurableActionLockDto> newLockList = originEnv.locks().stream()
                .map(l -> l.lockId().equals(lockId)
                        ? new DurableActionLockDto(
                                l.lockId(),
                                l.workspaceId(),
                                l.sessionId(),
                                l.originOperationId(),
                                l.originAttemptId(),
                                l.actionFingerprint(),
                                l.targetFingerprint(),
                                l.scope(),
                                l.reason(),
                                DurableActionLockStatus.CLEARED,
                                l.createdAt(),
                                consumedAt,
                                resolutionId)
                        : l)
                .toList();

        List<ToolAttemptAuditOutboxDto> newOriginOutbox = new ArrayList<>(originEnv.outboxEvents());
        newOriginOutbox.add(resolvedOutboxEvent);

        helper.saveAttemptEnvelope(new FsToolAttemptEnvelope(
                originEnv.version(), originEnv.attempt(), newLockList, newResList, newOriginOutbox));
    }

    private FsToolAttemptEnvelope findOriginEnvelopeForResolution(String workspaceId, String resolutionId) {
        for (FsToolAttemptEnvelope env : helper.loadAllEnvelopes(workspaceId)) {
            for (ToolAttemptResolutionDto r : env.resolutions()) {
                if (r.resolutionId().equals(resolutionId)) {
                    return env;
                }
            }
        }
        return null;
    }

    private ToolAttemptResolutionDto findResolution(FsToolAttemptEnvelope env, String resolutionId) {
        return env.resolutions().stream()
                .filter(r -> r.resolutionId().equals(resolutionId))
                .findFirst()
                .orElse(null);
    }

    private String validateRetryPreconditions(
            FsToolAttemptEnvelope originEnv,
            ToolAttemptResolutionDto res,
            String workspaceId,
            String lockId,
            ToolAttemptAdmissionRequest retryRequest) {
        if (!res.isRetryAuthorization()) {
            return "Resolution is not a retry authorization: " + res.resolutionKind();
        }
        if (!res.workspaceId().equals(workspaceId)
                || !retryRequest.workspaceId().equals(workspaceId)) {
            return "Workspace mismatch for resolution: " + res.resolutionId();
        }
        if (!res.lockId().equals(lockId)) {
            return "Resolution lockId mismatch: " + lockId;
        }
        if (!res.actionFingerprint().equals(retryRequest.actionFingerprint())) {
            return "Resolution actionFingerprint mismatch";
        }
        if (!Objects.equals(res.targetFingerprint(), retryRequest.targetFingerprint())) {
            return "Resolution targetFingerprint mismatch";
        }
        if (!res.originAttemptId().equals(retryRequest.retryOfAttemptId())) {
            return "Resolution originAttemptId does not match retryOfAttemptId";
        }

        Optional<DurableActionLockDto> lockOpt = originEnv.locks().stream()
                .filter(l -> l.lockId().equals(lockId) && l.status() == DurableActionLockStatus.ACTIVE)
                .findFirst();
        if (lockOpt.isEmpty()) {
            return "Active lock not found: " + lockId;
        }
        DurableActionLockDto lock = lockOpt.get();
        if (!lock.originAttemptId().equals(res.originAttemptId())
                || !lock.actionFingerprint().equals(res.actionFingerprint())
                || !Objects.equals(lock.targetFingerprint(), res.targetFingerprint())) {
            return "Lock identity mismatch with resolution: " + lockId;
        }

        ToolAttemptDto preceding = originEnv.attempt();
        if (!preceding.sessionId().equals(retryRequest.sessionId())
                || !preceding.operationId().equals(retryRequest.operationId())
                || !preceding.toolName().equals(retryRequest.toolName())
                || preceding.fingerprintVersion() != retryRequest.fingerprintVersion()
                || !preceding.actionFingerprint().equals(retryRequest.actionFingerprint())
                || !Objects.equals(preceding.targetFingerprint(), retryRequest.targetFingerprint())
                || preceding.idempotencyModeSnapshot() != retryRequest.idempotencyMode()) {
            return "Preceding attempt identity mismatch with retry request";
        }
        return null;
    }

    private static ToolAttemptDto createRetryAttempt(
            String workspaceId, String attemptId, int attemptNumber, ToolAttemptAdmissionRequest retryRequest) {
        return new ToolAttemptDto(
                workspaceId,
                retryRequest.sessionId(),
                retryRequest.operationId(),
                attemptId,
                attemptNumber,
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
    }
}
