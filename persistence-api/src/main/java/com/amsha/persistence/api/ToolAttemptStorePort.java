package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage port for tool attempt execution journals, durable action locks, and outbox relay
 * per Proposal §12.4. Runtime operations are workspace-scoped; callers must use the workspace
 * resolved from the active session or checkpoint context.
 */
public interface ToolAttemptStorePort {

    /**
     * Atomically allocates attempt identity, admits the attempt, creates any AT_MOST_ONCE claim,
     * and creates the ATTEMPT_ADMITTED outbox row from request metadata. A null
     * {@code retryOfAttemptId} identifies an initial admission; a retry names its preceding
     * attempt. For retries, the preceding attempt must belong to the same workspace, session,
     * operation, tool, and action fingerprint, be the latest attempt, and be eligible for retry
     * per §3 and §12.4 (prior {@link ToolAttemptStatus#SETTLED_SUCCESS} is never eligible for retry;
     * {@link ToolAttemptIdempotencyMode#AT_MOST_ONCE} permits retry only after
     * {@link ToolAttemptStatus#CANCELLED_BEFORE_LAUNCH}; {@link ToolAttemptIdempotencyMode#IDEMPOTENT}
     * permits {@link ToolAttemptStatus#SETTLED_ERROR}, {@link ToolAttemptStatus#TIMED_OUT}, and
     * {@link ToolAttemptStatus#CANCELLED_BEFORE_LAUNCH}; retries requiring resolution consumption
     * are admitted via {@link #applyResolutionAndAdmitRetry}). Repeating an admission request must
     * return the same attempt rather than allocate another. The result carries the admission outbox
     * row when newly created or when an identical attempt remains ADMITTED, so callers can recover
     * its audit acknowledgement. The caller cannot supply a complete outbox DTO because the store
     * allocates its attempt ID.
     */
    ToolAttemptAdmissionResult admit(ToolAttemptAdmissionRequest request);

    /**
     * Atomically executes a fenced compare-and-set state transition per §12.3 and §12.4.
     */
    ToolAttemptTransitionResult transition(
            String workspaceId,
            String attemptId,
            ToolAttemptStatus expectedStatus,
            long expectedFencingToken,
            ToolAttemptStatus targetStatus,
            ToolAttemptSettlementFields settlementFields,
            DurableActionLockDto optionalLock,
            List<ToolAttemptAuditOutboxDto> outboxEvents);

    /**
     * Finds an attempt by its workspace and attempt ID.
     */
    Optional<ToolAttemptDto> findAttempt(String workspaceId, String attemptId);

    /**
     * Finds the latest attempt for a logical operation in a workspace.
     */
    Optional<ToolAttemptDto> findLatestAttemptByOperation(String workspaceId, String operationId);

    /**
     * Finds all attempts associated with a session in a workspace.
     */
    List<ToolAttemptDto> findAttemptsBySession(String workspaceId, String sessionId);

    /**
     * Finds an active durable action lock by lock ID.
     */
    Optional<DurableActionLockDto> findActiveLock(String workspaceId, String lockId);

    /**
     * Finds applicable active durable action locks matching candidate action or target fingerprints.
     */
    List<DurableActionLockDto> findApplicableLocks(
            String workspaceId, String sessionId, String actionFingerprint, String targetFingerprint);

    /**
     * Finds an AT_MOST_ONCE terminal execution result eligible for replay.
     */
    Optional<ToolAttemptDto> findAtMostOnceResult(
            String workspaceId, String sessionId, String toolName, int fingerprintVersion, String actionFingerprint);

    /**
     * Finds an AT_MOST_ONCE action claim.
     */
    Optional<ToolActionClaimDto> findClaim(
            String workspaceId, String sessionId, String toolName, int fingerprintVersion, String actionFingerprint);

    /**
     * Privileged metadata-only enumeration of known workspace IDs for startup recovery. Callers
     * must re-enter each workspace context before reading or mutating workspace rows.
     */
    Set<String> listWorkspaceIds();

    /**
     * Prepares an authorized resolution and enqueues its authorization outbox event per §12.4 and §12.7.
     */
    void prepareResolution(ToolAttemptResolutionDto resolution, ToolAttemptAuditOutboxDto authorizationOutboxEvent);

    /**
     * Atomically applies an acknowledged non-retry resolution, clearing the lock and enqueuing
     * ACTION_RESOLVED per Proposal §8.1, §12.4, and §12.7. This method is restricted to non-retry
     * resolutions (such as read-only postcondition verifications or operator acknowledgements of
     * uncertainty that release a lock without admitting a retry). The store must reject one-shot
     * retry approval resolutions; retry approvals must be consumed exclusively via
     * {@link #applyResolutionAndAdmitRetry} so approval cannot be consumed without admitting the retry.
     *
     * @param workspaceId the workspace identifier
     * @param resolutionId the authorized non-retry resolution identifier
     * @param lockId the active lock identifier to clear
     * @param consumedAt the timestamp of resolution consumption
     * @param resolvedOutboxEvent the ACTION_RESOLVED outbox event to enqueue
     * @return true if the resolution was applied and lock cleared; false if already applied, stale,
     *         or rejected as a retry resolution
     */
    boolean applyResolution(
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent);

    /**
     * Atomically consumes an acknowledged retry resolution, clears or resolves the associated lock,
     * enqueues ACTION_RESOLVED, admits the retry attempt, and enqueues ATTEMPT_ADMITTED in a single
     * store transaction per Proposal §8.1, §12.4, and §12.7. Returns the admission result for the
     * newly admitted retry attempt (or identical existing attempt on replay).
     *
     * @param workspaceId the workspace identifier
     * @param resolutionId the authorized resolution identifier
     * @param lockId the active lock identifier to resolve and consume
     * @param consumedAt the timestamp of resolution consumption
     * @param resolvedOutboxEvent the ACTION_RESOLVED outbox event to enqueue
     * @param retryRequest the retry attempt admission request
     * @return the admission result for the admitted retry attempt
     */
    ToolAttemptAdmissionResult applyResolutionAndAdmitRetry(
            String workspaceId,
            String resolutionId,
            String lockId,
            Instant consumedAt,
            ToolAttemptAuditOutboxDto resolvedOutboxEvent,
            ToolAttemptAdmissionRequest retryRequest);

    /**
     * Finds an authorized resolution by resolution ID.
     */
    Optional<ToolAttemptResolutionDto> findResolution(String workspaceId, String resolutionId);

    /**
     * Finds an authorized resolution by lock ID.
     */
    Optional<ToolAttemptResolutionDto> findResolutionByLockId(String workspaceId, String lockId);

    /**
     * Claims a leased batch of pending outbox events for delivery in one workspace.
     */
    List<ToolAttemptAuditOutboxDto> leaseOutboxEvents(
            String workspaceId, String leaseId, Instant leaseUntil, int limit);

    /**
     * Acknowledges successful delivery of an outbox event, recording the delivered HMAC audit event ID.
     */
    boolean acknowledgeOutboxEvent(
            String workspaceId, String eventId, String leaseId, String deliveredAuditEventId, Instant deliveredAt);

    /**
     * Reschedules an undelivered outbox event with backoff.
     */
    boolean rescheduleOutboxEvent(
            String workspaceId, String eventId, String leaseId, Instant nextAttemptAt, int deliveryAttempts);

    /**
     * Finds an outbox event by event ID.
     */
    Optional<ToolAttemptAuditOutboxDto> findOutboxEvent(String workspaceId, String eventId);

    /**
     * Prunes retention-eligible terminal attempts, locks, resolutions, and delivered outbox rows
     * in dependency order per §12.4.
     */
    long pruneTerminalBefore(String workspaceId, Instant cutoff);

    /**
     * Permanently deletes all AT_MOST_ONCE claims associated with a session in a workspace upon
     * true permanent session deletion (e.g. GDPR purge or administrative eradication) per §12.4.
     * Claims survive session resets (such as {@code /reset}, {@code /resethard}, and
     * {@code WorkspaceManager.resetSessionState()}) and retention pruning for the entire lifetime
     * of the session to preserve the AT_MOST_ONCE replay fence. Session reset flows must never call
     * this operation. Once claims are permanently deleted through this method, terminal attempts
     * previously referenced by those claims become eligible for retention pruning.
     *
     * @param workspaceId the workspace identifier
     * @param sessionId the session identifier
     * @return the count of claims deleted
     */
    long deleteClaimsBySession(String workspaceId, String sessionId);
}
