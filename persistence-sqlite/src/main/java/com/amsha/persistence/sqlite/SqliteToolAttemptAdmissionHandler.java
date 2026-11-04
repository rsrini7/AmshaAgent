package com.amsha.persistence.sqlite;

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
import com.amsha.persistence.sqlite.jdbi.ToolAttemptJdbiDao;

/**
 * Handles admission logic for initial attempts and unapproved retries in SQLite.
 */
class SqliteToolAttemptAdmissionHandler {

    ToolAttemptAdmissionResult admitInitial(ToolAttemptJdbiDao dao, ToolAttemptAdmissionRequest req) {
        List<ToolAttemptDto> existing = dao.findAttemptsByOperation(req.workspaceId(), req.operationId());
        if (!existing.isEmpty()) {
            ToolAttemptDto first = existing.get(0);
            if (matchesInitialRequest(first, req)) {
                Optional<ToolAttemptAuditOutboxDto> outbox = first.status() == ToolAttemptStatus.ADMITTED
                        ? dao.findOutboxEventByIdempotencyKey(
                                req.workspaceId(), req.workspaceId() + ":" + first.attemptId() + ":ATTEMPT_ADMITTED")
                        : Optional.empty();
                return ToolAttemptAdmissionResult.identicalExisting(first, outbox.orElse(null));
            }
            return ToolAttemptAdmissionResult.conflict("Existing attempt does not match initial admission request");
        }

        if (req.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            Optional<ToolActionClaimDto> claimOpt = dao.findClaim(
                    req.workspaceId(),
                    req.sessionId(),
                    req.toolName(),
                    req.fingerprintVersion(),
                    req.actionFingerprint());
            if (claimOpt.isPresent()) {
                ToolActionClaimDto claim = claimOpt.get();
                if (claim.status() == ActionClaimStatus.CONSUMED) {
                    Optional<ToolAttemptDto> refOpt = dao.findAttempt(req.workspaceId(), claim.attemptId());
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
            dao.insertClaim(
                    req.workspaceId(),
                    req.sessionId(),
                    req.toolName(),
                    req.fingerprintVersion(),
                    req.actionFingerprint(),
                    ActionClaimStatus.RESERVED.name(),
                    req.operationId(),
                    attemptId,
                    req.createdAt(),
                    req.createdAt());
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
        dao.insertAttempt(attempt);

        String eventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        ToolAttemptAuditOutboxDto outbox = ToolAttemptAuditOutboxDto.forAttempt(
                eventId,
                req.workspaceId(),
                req.sessionId(),
                attemptId,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                req.admissionSafeMetadataJson(),
                req.createdAt());
        dao.insertOutboxEvent(outbox);

        return ToolAttemptAdmissionResult.created(attempt, outbox);
    }

    ToolAttemptAdmissionResult admitRetryUnapproved(ToolAttemptJdbiDao dao, ToolAttemptAdmissionRequest req) {
        List<ToolAttemptDto> opAttempts = dao.findAttemptsByOperation(req.workspaceId(), req.operationId());
        if (opAttempts.size() > 1) {
            ToolAttemptDto latest = opAttempts.get(opAttempts.size() - 1);
            if (matchesRetryRequest(latest, req)) {
                Optional<ToolAttemptAuditOutboxDto> outbox = latest.status() == ToolAttemptStatus.ADMITTED
                        ? dao.findOutboxEventByIdempotencyKey(
                                req.workspaceId(), req.workspaceId() + ":" + latest.attemptId() + ":ATTEMPT_ADMITTED")
                        : Optional.empty();
                return ToolAttemptAdmissionResult.identicalExisting(latest, outbox.orElse(null));
            }
        }

        Optional<ToolAttemptDto> precOpt = dao.findAttempt(req.workspaceId(), req.retryOfAttemptId());
        if (precOpt.isEmpty()) {
            return ToolAttemptAdmissionResult.conflict("Preceding attempt not found: " + req.retryOfAttemptId());
        }
        ToolAttemptDto preceding = precOpt.get();
        if (!matchesPrecedingAttempt(preceding, req)) {
            return ToolAttemptAdmissionResult.conflict("Preceding attempt does not match retry request");
        }

        Optional<ToolAttemptDto> latestOpt = dao.findLatestAttemptByOperation(req.workspaceId(), req.operationId());
        if (latestOpt.isEmpty() || !latestOpt.get().attemptId().equals(preceding.attemptId())) {
            return ToolAttemptAdmissionResult.conflict("Preceding attempt is not the latest attempt for operation");
        }

        if (!preceding.status().isEligibleForRetry(req.idempotencyMode(), false)) {
            return ToolAttemptAdmissionResult.conflict("Prior attempt with status " + preceding.status()
                    + " is not eligible for unapproved retry under " + req.idempotencyMode());
        }

        String attemptId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        int nextAttemptNumber = preceding.attemptNumber() + 1;

        if (req.idempotencyMode() == ToolAttemptIdempotencyMode.AT_MOST_ONCE) {
            dao.insertClaim(
                    req.workspaceId(),
                    req.sessionId(),
                    req.toolName(),
                    req.fingerprintVersion(),
                    req.actionFingerprint(),
                    ActionClaimStatus.RESERVED.name(),
                    req.operationId(),
                    attemptId,
                    req.createdAt(),
                    req.createdAt());
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
        dao.insertAttempt(retryAttempt);

        String eventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        ToolAttemptAuditOutboxDto outbox = ToolAttemptAuditOutboxDto.forAttempt(
                eventId,
                req.workspaceId(),
                req.sessionId(),
                attemptId,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                req.admissionSafeMetadataJson(),
                req.createdAt());
        dao.insertOutboxEvent(outbox);

        return ToolAttemptAdmissionResult.created(retryAttempt, outbox);
    }

    private boolean matchesInitialRequest(ToolAttemptDto attempt, ToolAttemptAdmissionRequest req) {
        return attempt.attemptNumber() == 1
                && attempt.sessionId().equals(req.sessionId())
                && attempt.toolName().equals(req.toolName())
                && attempt.actionFingerprint().equals(req.actionFingerprint())
                && attempt.fingerprintVersion() == req.fingerprintVersion()
                && Objects.equals(attempt.targetFingerprint(), req.targetFingerprint())
                && attempt.idempotencyModeSnapshot() == req.idempotencyMode();
    }

    private boolean matchesRetryRequest(ToolAttemptDto attempt, ToolAttemptAdmissionRequest req) {
        return attempt.sessionId().equals(req.sessionId())
                && attempt.toolName().equals(req.toolName())
                && attempt.actionFingerprint().equals(req.actionFingerprint())
                && attempt.fingerprintVersion() == req.fingerprintVersion()
                && Objects.equals(attempt.targetFingerprint(), req.targetFingerprint())
                && attempt.idempotencyModeSnapshot() == req.idempotencyMode();
    }

    private boolean matchesPrecedingAttempt(ToolAttemptDto preceding, ToolAttemptAdmissionRequest req) {
        return preceding.workspaceId().equals(req.workspaceId())
                && preceding.sessionId().equals(req.sessionId())
                && preceding.operationId().equals(req.operationId())
                && preceding.toolName().equals(req.toolName())
                && preceding.actionFingerprint().equals(req.actionFingerprint())
                && preceding.fingerprintVersion() == req.fingerprintVersion()
                && Objects.equals(preceding.targetFingerprint(), req.targetFingerprint())
                && preceding.idempotencyModeSnapshot() == req.idempotencyMode();
    }
}
