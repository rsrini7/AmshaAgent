package com.amsha.persistence.fs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptStatus;

/**
 * Handles attempt admission logic for filesystem persistence per Proposal §12.4.
 */
class FsToolAttemptAdmissionHandler {

    private final FsToolAttemptHelper helper;

    FsToolAttemptAdmissionHandler(FsToolAttemptHelper helper) {
        this.helper = helper;
    }

    ToolAttemptAdmissionResult admitInitialUnderLock(ToolAttemptAdmissionRequest req) {
        List<FsToolAttemptEnvelope> envelopes = helper.loadAllEnvelopes(req.workspaceId());
        List<ToolAttemptDto> opAttempts = envelopes.stream()
                .map(FsToolAttemptEnvelope::attempt)
                .filter(a -> a.operationId().equals(req.operationId()))
                .sorted(Comparator.comparingInt(ToolAttemptDto::attemptNumber))
                .toList();

        if (!opAttempts.isEmpty()) {
            ToolAttemptDto first = opAttempts.get(0);
            if (matchesInitialRequest(first, req)) {
                Optional<ToolAttemptAuditOutboxDto> outbox = first.status() == ToolAttemptStatus.ADMITTED
                        ? findOutboxEventUnderLock(
                                envelopes, req.workspaceId() + ":" + first.attemptId() + ":ATTEMPT_ADMITTED")
                        : Optional.empty();
                return ToolAttemptAdmissionResult.identicalExisting(first, outbox.orElse(null));
            }
            return ToolAttemptAdmissionResult.conflict("Existing attempt does not match initial admission request");
        }

        FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(req.workspaceId());
        if (req.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            Optional<ToolActionClaimDto> claimOpt = findClaimInEnvelope(
                    claimsEnv, req.sessionId(), req.toolName(), req.fingerprintVersion(), req.actionFingerprint());
            if (claimOpt.isPresent()) {
                ToolActionClaimDto claim = claimOpt.get();
                if (claim.status() == ActionClaimStatus.CONSUMED) {
                    Optional<ToolAttemptDto> refOpt = helper.loadAttemptEnvelope(req.workspaceId(), claim.attemptId())
                            .map(FsToolAttemptEnvelope::attempt);
                    if (refOpt.isPresent() && refOpt.get().status().isReplayableAtMostOnceOutcome()) {
                        return ToolAttemptAdmissionResult.atMostOnceExisting(refOpt.get());
                    }
                    return ToolAttemptAdmissionResult.conflict(
                            "AT_MOST_ONCE claim is CONSUMED but attempt is not terminal");
                }
                return ToolAttemptAdmissionResult.conflict(
                        "AT_MOST_ONCE claim is active and RESERVED by attempt: " + claim.attemptId());
            }
        }

        String attemptId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        if (req.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            ToolActionClaimDto newClaim = new ToolActionClaimDto(
                    req.workspaceId(),
                    req.sessionId(),
                    req.toolName(),
                    req.fingerprintVersion(),
                    req.actionFingerprint(),
                    ActionClaimStatus.RESERVED,
                    req.operationId(),
                    attemptId,
                    req.createdAt(),
                    null);
            List<ToolActionClaimDto> updatedClaims = new ArrayList<>(claimsEnv.claims());
            updatedClaims.add(newClaim);
            helper.saveClaimsEnvelope(
                    new FsWorkspaceClaimsEnvelope(claimsEnv.version(), req.workspaceId(), updatedClaims));
        }

        ToolAttemptDto attempt = new ToolAttemptDto(
                req.workspaceId(),
                req.sessionId(),
                req.operationId(),
                attemptId,
                1,
                req.toolName(),
                req.actionFingerprint(),
                req.fingerprintVersion(),
                req.targetFingerprint(),
                req.idempotencyMode(),
                ToolAttemptStatus.ADMITTED,
                0L,
                req.executionKind(),
                null,
                null,
                null,
                null,
                req.createdAt(),
                null,
                null,
                null);

        String eventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        ToolAttemptAuditOutboxDto outbox = ToolAttemptAuditOutboxDto.forAttempt(
                eventId,
                req.workspaceId(),
                req.sessionId(),
                attemptId,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                req.admissionSafeMetadataJson(),
                req.createdAt());

        helper.saveAttemptEnvelope(FsToolAttemptEnvelope.of(attempt, outbox));
        return ToolAttemptAdmissionResult.created(attempt, outbox);
    }

    ToolAttemptAdmissionResult admitRetryUnapprovedUnderLock(ToolAttemptAdmissionRequest req) {
        List<FsToolAttemptEnvelope> envelopes = helper.loadAllEnvelopes(req.workspaceId());
        List<ToolAttemptDto> opAttempts = envelopes.stream()
                .map(FsToolAttemptEnvelope::attempt)
                .filter(a -> a.operationId().equals(req.operationId()))
                .sorted(Comparator.comparingInt(ToolAttemptDto::attemptNumber))
                .toList();

        if (opAttempts.size() > 1) {
            ToolAttemptDto latest = opAttempts.get(opAttempts.size() - 1);
            if (matchesRetryRequest(latest, req)) {
                Optional<ToolAttemptAuditOutboxDto> outbox = latest.status() == ToolAttemptStatus.ADMITTED
                        ? findOutboxEventUnderLock(
                                envelopes, req.workspaceId() + ":" + latest.attemptId() + ":ATTEMPT_ADMITTED")
                        : Optional.empty();
                return ToolAttemptAdmissionResult.identicalExisting(latest, outbox.orElse(null));
            }
        }

        Optional<ToolAttemptDto> precOpt = helper.loadAttemptEnvelope(req.workspaceId(), req.retryOfAttemptId())
                .map(FsToolAttemptEnvelope::attempt);
        if (precOpt.isEmpty()) {
            return ToolAttemptAdmissionResult.conflict("Preceding attempt not found: " + req.retryOfAttemptId());
        }
        ToolAttemptDto preceding = precOpt.get();
        if (!matchesPrecedingAttempt(preceding, req)) {
            return ToolAttemptAdmissionResult.conflict("Preceding attempt does not match retry request");
        }
        if (!opAttempts.isEmpty()
                && !opAttempts.get(opAttempts.size() - 1).attemptId().equals(preceding.attemptId())) {
            return ToolAttemptAdmissionResult.conflict("Preceding attempt is not the latest attempt for operation");
        }
        if (!preceding.status().isEligibleForRetry(req.idempotencyMode(), false)) {
            return ToolAttemptAdmissionResult.conflict("Prior attempt with status " + preceding.status()
                    + " is not eligible for unapproved retry under " + req.idempotencyMode());
        }

        String attemptId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        int nextAttemptNumber = preceding.attemptNumber() + 1;

        if (req.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(req.workspaceId());
            ToolActionClaimDto newClaim = new ToolActionClaimDto(
                    req.workspaceId(),
                    req.sessionId(),
                    req.toolName(),
                    req.fingerprintVersion(),
                    req.actionFingerprint(),
                    ActionClaimStatus.RESERVED,
                    req.operationId(),
                    attemptId,
                    req.createdAt(),
                    null);
            List<ToolActionClaimDto> updated = new ArrayList<>(claimsEnv.claims());
            updated.add(newClaim);
            helper.saveClaimsEnvelope(new FsWorkspaceClaimsEnvelope(claimsEnv.version(), req.workspaceId(), updated));
        }

        ToolAttemptDto retryAttempt = new ToolAttemptDto(
                req.workspaceId(),
                req.sessionId(),
                req.operationId(),
                attemptId,
                nextAttemptNumber,
                req.toolName(),
                req.actionFingerprint(),
                req.fingerprintVersion(),
                req.targetFingerprint(),
                req.idempotencyMode(),
                ToolAttemptStatus.ADMITTED,
                0L,
                req.executionKind(),
                null,
                null,
                null,
                null,
                req.createdAt(),
                null,
                null,
                null);

        String eventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        ToolAttemptAuditOutboxDto outbox = ToolAttemptAuditOutboxDto.forAttempt(
                eventId,
                req.workspaceId(),
                req.sessionId(),
                attemptId,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                req.admissionSafeMetadataJson(),
                req.createdAt());

        helper.saveAttemptEnvelope(FsToolAttemptEnvelope.of(retryAttempt, outbox));
        return ToolAttemptAdmissionResult.created(retryAttempt, outbox);
    }

    static Optional<ToolActionClaimDto> findClaimInEnvelope(
            FsWorkspaceClaimsEnvelope env, String sessionId, String toolName, int version, String fp) {
        return env.claims().stream()
                .filter(c -> c.sessionId().equals(sessionId)
                        && c.toolName().equals(toolName)
                        && c.fingerprintVersion() == version
                        && c.actionFingerprint().equals(fp))
                .findFirst();
    }

    private static Optional<ToolAttemptAuditOutboxDto> findOutboxEventUnderLock(
            List<FsToolAttemptEnvelope> envelopes, String key) {
        return envelopes.stream()
                .flatMap(e -> e.outboxEvents().stream())
                .filter(o -> o.idempotencyKey().equals(key))
                .findFirst();
    }

    private static boolean matchesInitialRequest(ToolAttemptDto a, ToolAttemptAdmissionRequest r) {
        return a.workspaceId().equals(r.workspaceId())
                && a.sessionId().equals(r.sessionId())
                && a.operationId().equals(r.operationId())
                && a.toolName().equals(r.toolName())
                && a.actionFingerprint().equals(r.actionFingerprint())
                && a.fingerprintVersion() == r.fingerprintVersion()
                && Objects.equals(a.targetFingerprint(), r.targetFingerprint())
                && a.idempotencyModeSnapshot() == r.idempotencyMode();
    }

    private static boolean matchesRetryRequest(ToolAttemptDto a, ToolAttemptAdmissionRequest r) {
        return a.workspaceId().equals(r.workspaceId())
                && a.sessionId().equals(r.sessionId())
                && a.operationId().equals(r.operationId())
                && a.toolName().equals(r.toolName())
                && a.actionFingerprint().equals(r.actionFingerprint())
                && a.fingerprintVersion() == r.fingerprintVersion()
                && Objects.equals(a.targetFingerprint(), r.targetFingerprint())
                && a.idempotencyModeSnapshot() == r.idempotencyMode();
    }

    private static boolean matchesPrecedingAttempt(ToolAttemptDto p, ToolAttemptAdmissionRequest r) {
        return p.workspaceId().equals(r.workspaceId())
                && p.sessionId().equals(r.sessionId())
                && p.operationId().equals(r.operationId())
                && p.toolName().equals(r.toolName())
                && p.actionFingerprint().equals(r.actionFingerprint())
                && p.fingerprintVersion() == r.fingerprintVersion()
                && Objects.equals(p.targetFingerprint(), r.targetFingerprint())
                && p.idempotencyModeSnapshot() == r.idempotencyMode();
    }
}
