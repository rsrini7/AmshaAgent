package com.amsha.persistence.fs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptResolutionDto;
import com.amsha.persistence.api.ToolAttemptResolutionStatus;

/**
 * Handles outbox leasing, acknowledgement, rescheduling, claim deletion, and retention pruning for filesystem persistence.
 */
class FsToolAttemptOutboxHandler {

    private final FsToolAttemptHelper helper;

    FsToolAttemptOutboxHandler(FsToolAttemptHelper helper) {
        this.helper = Objects.requireNonNull(helper, "helper must not be null");
    }

    List<ToolAttemptAuditOutboxDto> leaseOutboxEvents(
            String workspaceId, String leaseId, Instant leaseUntil, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        return helper.withWorkspaceLock(workspaceId, () -> {
            Instant now = Instant.now();
            List<FsToolAttemptEnvelope> envelopes = helper.loadAllEnvelopes(workspaceId);
            List<ToolAttemptAuditOutboxDto> deliverable = envelopes.stream()
                    .flatMap(e -> e.outboxEvents().stream())
                    .filter(o -> o.deliveredAt() == null
                            && !o.nextAttemptAt().isAfter(now)
                            && (o.leaseUntil() == null || o.leaseUntil().isBefore(now)))
                    .sorted(Comparator.comparing(ToolAttemptAuditOutboxDto::nextAttemptAt)
                            .thenComparing(ToolAttemptAuditOutboxDto::createdAt))
                    .limit(limit)
                    .toList();

            if (deliverable.isEmpty()) {
                return List.of();
            }
            Set<String> leasedIds =
                    deliverable.stream().map(ToolAttemptAuditOutboxDto::eventId).collect(Collectors.toSet());

            List<ToolAttemptAuditOutboxDto> result = new ArrayList<>();
            for (FsToolAttemptEnvelope env : envelopes) {
                boolean modified = false;
                List<ToolAttemptAuditOutboxDto> updated = new ArrayList<>();
                for (ToolAttemptAuditOutboxDto o : env.outboxEvents()) {
                    if (leasedIds.contains(o.eventId())) {
                        ToolAttemptAuditOutboxDto le = new ToolAttemptAuditOutboxDto(
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
                                null);
                        updated.add(le);
                        result.add(le);
                        modified = true;
                    } else {
                        updated.add(o);
                    }
                }
                if (modified) {
                    helper.saveAttemptEnvelope(new FsToolAttemptEnvelope(
                            env.version(), env.attempt(), env.locks(), env.resolutions(), updated));
                }
            }
            return result;
        });
    }

    boolean acknowledgeOutboxEvent(
            String workspaceId, String eventId, String leaseId, String deliveredAuditEventId, Instant deliveredAt) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(deliveredAuditEventId, "deliveredAuditEventId must not be null");
        Objects.requireNonNull(deliveredAt, "deliveredAt must not be null");

        return helper.withWorkspaceLock(workspaceId, () -> {
            for (FsToolAttemptEnvelope env : helper.loadAllEnvelopes(workspaceId)) {
                boolean found = false;
                List<ToolAttemptAuditOutboxDto> updated = new ArrayList<>();
                for (ToolAttemptAuditOutboxDto o : env.outboxEvents()) {
                    if (o.eventId().equals(eventId) && leaseId.equals(o.leaseId())) {
                        updated.add(new ToolAttemptAuditOutboxDto(
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
                                null,
                                null,
                                deliveredAt,
                                deliveredAuditEventId));
                        found = true;
                    } else {
                        updated.add(o);
                    }
                }
                if (found) {
                    helper.saveAttemptEnvelope(new FsToolAttemptEnvelope(
                            env.version(), env.attempt(), env.locks(), env.resolutions(), updated));
                    return true;
                }
            }
            return false;
        });
    }

    boolean rescheduleOutboxEvent(
            String workspaceId, String eventId, String leaseId, Instant nextAttemptAt, int deliveryAttempts) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");

        return helper.withWorkspaceLock(workspaceId, () -> {
            for (FsToolAttemptEnvelope env : helper.loadAllEnvelopes(workspaceId)) {
                boolean found = false;
                List<ToolAttemptAuditOutboxDto> updated = new ArrayList<>();
                for (ToolAttemptAuditOutboxDto o : env.outboxEvents()) {
                    if (o.eventId().equals(eventId) && leaseId.equals(o.leaseId())) {
                        updated.add(new ToolAttemptAuditOutboxDto(
                                o.eventId(),
                                o.idempotencyKey(),
                                o.workspaceId(),
                                o.sessionId(),
                                o.attemptId(),
                                o.resolutionId(),
                                o.eventKind(),
                                o.safeMetadataJson(),
                                o.createdAt(),
                                deliveryAttempts,
                                nextAttemptAt,
                                null,
                                null,
                                null,
                                null));
                        found = true;
                    } else {
                        updated.add(o);
                    }
                }
                if (found) {
                    helper.saveAttemptEnvelope(new FsToolAttemptEnvelope(
                            env.version(), env.attempt(), env.locks(), env.resolutions(), updated));
                    return true;
                }
            }
            return false;
        });
    }

    Optional<ToolAttemptAuditOutboxDto> findOutboxEvent(String workspaceId, String eventId) {
        return helper.withWorkspaceLock(
                workspaceId,
                () -> helper.loadAllEnvelopes(workspaceId).stream()
                        .flatMap(e -> e.outboxEvents().stream())
                        .filter(o -> o.eventId().equals(eventId)
                                || o.idempotencyKey().equals(eventId))
                        .findFirst());
    }

    long pruneTerminalBefore(String workspaceId, Instant cutoff) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");

        return helper.withWorkspaceLock(workspaceId, () -> {
            FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(workspaceId);
            Set<String> claimedAttemptIds = claimsEnv.claims().stream()
                    .map(ToolActionClaimDto::attemptId)
                    .collect(Collectors.toSet());

            List<FsToolAttemptEnvelope> envelopes = helper.loadAllEnvelopes(workspaceId);
            Set<String> activeLockAttemptIds = envelopes.stream()
                    .flatMap(e -> e.locks().stream())
                    .filter(l -> l.status() == DurableActionLockStatus.ACTIVE)
                    .map(DurableActionLockDto::originAttemptId)
                    .collect(Collectors.toSet());

            Set<String> pendingResAttemptIds = envelopes.stream()
                    .flatMap(e -> e.resolutions().stream())
                    .filter(r -> r.resolutionStatus() == ToolAttemptResolutionStatus.PENDING)
                    .map(ToolAttemptResolutionDto::originAttemptId)
                    .collect(Collectors.toSet());

            long prunedAttempts = 0;
            for (FsToolAttemptEnvelope env : envelopes) {
                ToolAttemptDto a = env.attempt();
                boolean isTerminal = a.status().isTerminal();
                boolean isOldEnough = a.settledAt() != null && a.settledAt().isBefore(cutoff);
                boolean isProtected = claimedAttemptIds.contains(a.attemptId())
                        || activeLockAttemptIds.contains(a.attemptId())
                        || pendingResAttemptIds.contains(a.attemptId());

                if (isTerminal && isOldEnough && !isProtected) {
                    helper.deleteAttemptFile(workspaceId, a.attemptId());
                    prunedAttempts++;
                } else {
                    List<ToolAttemptAuditOutboxDto> prunedOutbox = env.outboxEvents().stream()
                            .filter(o ->
                                    o.deliveredAt() == null || !o.deliveredAt().isBefore(cutoff))
                            .toList();
                    List<DurableActionLockDto> prunedLocks = env.locks().stream()
                            .filter(l -> l.status() == DurableActionLockStatus.ACTIVE
                                    || l.clearedAt() == null
                                    || !l.clearedAt().isBefore(cutoff))
                            .toList();
                    List<ToolAttemptResolutionDto> prunedRes = env.resolutions().stream()
                            .filter(r -> r.resolutionStatus() == ToolAttemptResolutionStatus.PENDING
                                    || r.consumedAt() == null
                                    || !r.consumedAt().isBefore(cutoff))
                            .toList();

                    if (prunedOutbox.size() != env.outboxEvents().size()
                            || prunedLocks.size() != env.locks().size()
                            || prunedRes.size() != env.resolutions().size()) {
                        helper.saveAttemptEnvelope(new FsToolAttemptEnvelope(
                                env.version(), env.attempt(), prunedLocks, prunedRes, prunedOutbox));
                    }
                }
            }
            return prunedAttempts;
        });
    }

    long deleteClaimsBySession(String workspaceId, String sessionId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        return helper.withWorkspaceLock(workspaceId, () -> {
            FsWorkspaceClaimsEnvelope claimsEnv = helper.loadClaimsEnvelope(workspaceId);
            List<ToolActionClaimDto> retained = claimsEnv.claims().stream()
                    .filter(c -> !c.sessionId().equals(sessionId))
                    .toList();
            long count = (long) claimsEnv.claims().size() - retained.size();
            if (count > 0) {
                helper.saveClaimsEnvelope(new FsWorkspaceClaimsEnvelope(claimsEnv.version(), workspaceId, retained));
            }
            return count;
        });
    }
}
